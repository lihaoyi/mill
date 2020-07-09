package mill
package contrib.webpack

import java.io._
import java.util.zip.{ZipEntry, ZipInputStream}

import ammonite.ops
import mill.{Agg, T}
import mill.define.{Sources, Target, Task}
import mill.eval.PathRef
import mill.scalajslib._
import mill.scalajslib.api.ModuleKind
import mill.util.Ctx
import os.ReadablePath

/**
  * Trait for Scala.js modules that create a webpack bundle from their NPM dependencies.
  *
  * Usage example:
  * {{{
  * import mill.contrib.ScalaJSWebpackModule
  *
  * object frontend extends mill.Cross[FrontendModule]("dev", "prod")
  *
  * class FrontendModule(jsCompilationMode: String) extends ScalaJSWebpackModule {
  *
  *   override def optimizeJs: Boolean = jsCompilationMode match {
  *     case "dev"  => false
  *     case "prod" => true
  *     case _ =>
  *       throw new UnsupportedOperationException(
  *         s"Supported module modes: ['dev', 'prod']")
  *   }
  *
  *   // Same sources and resources for "dev" and "prod"
  *   override def millSourcePath = super.millSourcePath / os.up
  *
  *   override def npmDeps = Agg(
  *     "uuid" -> "8.1.0"
  *   )
  *
  *   ...
  *
  * }
  * }}}
  *
  * How to compile: `./mill "frontend[dev].compile"`
  */
trait ScalaJSWebpackModule extends ScalaJSModule {

  def optimizeJs: Boolean

  def compiledJs: Task[PathRef] = if (optimizeJs) fullOpt else fastOpt

  // Direct npm dependencies
  def npmDeps: T[Agg[(String, String)]] = Agg.empty[(String, String)]

  // Direct npm development dependencies
  def npmDevDeps: T[Agg[(String, String)]] = Agg.empty[(String, String)]

  def webpackVersion: Target[String] = "4.43.0"

  def webpackMergeVersion: Target[String] = "4.2.2"

  def webpackCliVersion: Target[String] = "3.3.11"

  def sourceMapLoaderVersion: Target[String] = "1.0.0"

  def scalaJsFriendlySourceMapLoaderVersion: Target[String] = "0.1.5"

  override def moduleKind: T[ModuleKind] = T {
    ModuleKind.CommonJSModule
  }

  // The name of the bundle generated by webpack
  def webpackBundleFilename: Target[String] = "out-bundle.js"

  // Webpack output path for generated config files and the bundle
  def webpackOutputPath: Target[os.Path] = T.persistent(Ctx.taskCtx.dest)

  // The name of the generated webpack config file
  def webpackConfigFilename: Target[String] = "webpack.config.js"

  // Custom webpack configuration objects that get merged with the generated config
  def customWebpackConfigs: Sources = T.sources()

  // All JS dependencies
  def jsDeps: T[JsDeps] = T {
    val jsDepsFromIvyDeps =
      resolveDeps(transitiveIvyDeps)().flatMap(pathRef =>
        jsDepsFromJar(pathRef.path.toIO))
    val allJsDeps = jsDepsFromIvyDeps ++ transitiveJsDeps() ++ Agg(
      JsDeps(
        npmDeps().iterator.toList,
        devDependencies = npmDevDeps().iterator.toList)
    )
    allJsDeps.iterator.foldLeft(JsDeps.empty)(_ ++ _)
  }

  def transitiveJsDeps: Task.Sequence[JsDeps] =
    T.sequence(recursiveModuleDeps.collect {
      case mod: ScalaJSWebpackModule => mod.jsDeps
    })

  def webpackPackageSpec: Task[String] = T {
    ujson
      .Obj(
        "dependencies" -> jsDeps().dependencies,
        "devDependencies" -> (jsDeps().devDependencies ++ Seq(
          "webpack" -> webpackVersion(),
          "webpack-merge" -> webpackMergeVersion(),
          "webpack-cli" -> webpackCliVersion(),
          "source-map-loader" -> sourceMapLoaderVersion(),
          "scalajs-friendly-source-map-loader" -> scalaJsFriendlySourceMapLoaderVersion()
        ))
      )
      .render(2) + "\n"
  }

  def writeWebpackPackageSpec: Task[PathRef] = T {
    ops.write.over(webpackOutputPath() / "package.json", webpackPackageSpec())
    PathRef(webpackOutputPath() / "package.json")
  }

  def installNpmDependencies: Task[PathRef] = T {
    writeWebpackPackageSpec()
    print(ops.%%("npm", "install", "--no-fund")(webpackOutputPath()).out.string)
    PathRef(webpackOutputPath())
  }

  def writeWebpackBundleSources: Task[PathRef] = T {
    jsDeps().jsSources foreach {
      case (n, s) => ops.write.over(webpackOutputPath() / n, s)
    }
    PathRef(webpackOutputPath())
  }

  def writeWebpackResources: Task[PathRef] = T {
    resources() foreach { resourcePath: PathRef =>
      os.copy.over(resourcePath.path, webpackOutputPath())
    }
    PathRef(webpackOutputPath())
  }

  def writeWebpackEntryPoint: Task[PathRef] = T {
    val jsFilename = compiledJs().path.segments.toSeq.last
    val sourceMapFilename = s"$jsFilename.map"
    val entryPointJs = webpackOutputPath() / jsFilename
    val sourceMapPath = compiledJs().path / os.up / sourceMapFilename
    if (os.exists(sourceMapPath)) {
      ops.cp.over(sourceMapPath, webpackOutputPath() / sourceMapFilename)
    }
    ops.cp.over(compiledJs().path, entryPointJs)
    PathRef(entryPointJs)
  }

  def webpackConfig: Task[String] = T {
    val entry = webpackOutputPath() / compiledJs().path.segments.toSeq.last
    val customConfigs = customWebpackConfigs().map { cfg =>
      cfg.toString -> readStringFromInputStream(cfg.path.getInputStream).trim
    }
    val generatedCfgName = "generatedWebpackCfg"
    val generatedCfg =
      s"""|const merge = require('webpack-merge');
          |
          |// Webpack config generated by ScalaJSWebpackModule
          |const $generatedCfgName = {
          |  "mode": "${if (optimizeJs) "production" else "development"}",
          |  "devtool": "${if (optimizeJs) "eval" else "source-map"}",
          |  "entry": "$entry",
          |  "output": {
          |    "path": "${webpackOutputPath()}",
          |    "filename": "${webpackBundleFilename()}"
          |  },
          |  "module": {
          |    "rules": [
          |       // Scala JS source map support
          |       {
          |           "test": /\\.js$$/,
          |           "use": ["scalajs-friendly-source-map-loader"],
          |           "enforce": "pre"
          |       },
          |    ]
          |  }
          |};
          |""".stripMargin
    val customCfgSnippets = customConfigs.zipWithIndex.map {
      case ((filename, cfg), i) =>
        val customCfgName = s"customWebpackCfg$i"
        val customCfgString =
          s"""|// Custom webpack config from '$filename', defined in ScalaJSWebpackModule
              |const $customCfgName = $cfg;
              |""".stripMargin
        customCfgName -> customCfgString
    }
    if (customCfgSnippets.isEmpty) {
      s"""|$generatedCfg
          |module.exports = $generatedCfgName;
          |""".stripMargin
    } else {
      s"""|$generatedCfg
          |${customCfgSnippets
        .map { case (_, cfgString) => cfgString }
        .mkString("\n")}
          |module.exports = merge($generatedCfgName, ${customCfgSnippets
        .map { case (cfgName, _) => cfgName }
        .mkString(",")});
          |""".stripMargin
    }
  }

  def writeWebpackConfig: Task[PathRef] = T {
    val configFilePath = webpackOutputPath() / webpackConfigFilename()
    ops.write.over(configFilePath, webpackConfig())
    PathRef(configFilePath)
  }

  def webpackBundle: Target[PathRef] = T.persistent {
    writeWebpackBundleSources()
    writeWebpackResources()
    installNpmDependencies()
    writeWebpackConfig()
    writeWebpackEntryPoint()
    print(
      ops
        .%%(
          "node",
          webpackOutputPath() / "node_modules" / "webpack" / "bin" / "webpack",
          "--bail",
          "--profile",
          "--config",
          webpackConfigFilename())(webpackOutputPath())
        .out
        .string)
    PathRef(webpackOutputPath())
  }

  @scala.annotation.tailrec
  private def readStringFromInputStream(
    in: InputStream,
    buffer: Array[Byte] = new Array[Byte](8192),
    out: ByteArrayOutputStream = new ByteArrayOutputStream
  ): String = {
    val byteCount = in.read(buffer)
    if (byteCount < 0) {
      out.toString
    } else {
      out.write(buffer, 0, byteCount)
      readStringFromInputStream(in, buffer, out)
    }
  }

  private def collectZipEntries[R](jar: File)(
    f: PartialFunction[(ZipEntry, ZipInputStream), R]
  ): List[R] = {
    val stream = new ZipInputStream(
      new BufferedInputStream(new FileInputStream(jar)))
    try Iterator
      .continually(stream.getNextEntry)
      .takeWhile(_ != null)
      .map(_ -> stream)
      .collect(f)
      .toList
    finally stream.close()
  }

  private def jsDepsFromJar(jar: File): Seq[JsDeps] = {
    collectZipEntries(jar) {
      case (zipEntry, stream) if zipEntry.getName == "NPM_DEPENDENCIES" =>
        val contentsAsJson = ujson.read(readStringFromInputStream(stream)).obj

        def dependenciesOfType(key: String): List[(String, String)] =
          contentsAsJson
            .getOrElse(key, ujson.Arr())
            .arr
            .flatMap(_.obj.map {
              case (s: String, v: ujson.Value) => s -> v.str
            })
            .toList

        JsDeps(
          dependenciesOfType("compileDependencies") ++ dependenciesOfType(
            "compile-dependencies"),
          dependenciesOfType("compileDevDependencies") ++ dependenciesOfType(
            "compile-devDependencies")
        )
      case (zipEntry, stream)
        if zipEntry.getName.endsWith(".js") && !zipEntry.getName.startsWith(
          "scala/") =>
        JsDeps(
          jsSources = Map(zipEntry.getName -> readStringFromInputStream(stream))
        )
    }
  }

}

case class JsDeps(
  dependencies: Seq[(String, String)] = Nil,
  devDependencies: Seq[(String, String)] = Nil,
  jsSources: Map[String, String] = Map.empty
) {

  def ++(that: JsDeps): JsDeps =
    JsDeps(
      dependencies ++ that.dependencies,
      devDependencies ++ that.devDependencies,
      jsSources ++ that.jsSources)
}

object JsDeps {

  lazy val empty: JsDeps = JsDeps()

  implicit def rw: upickle.default.ReadWriter[JsDeps] =
    upickle.default.macroRW
}
