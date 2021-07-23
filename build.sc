import $file.ci.shared
import $file.ci.upload
import $ivy.`org.scalaj::scalaj-http:2.4.2`
import $ivy.`de.tototec::de.tobiasroeser.mill.vcs.version_mill0.9:0.1.1`
import $ivy.`net.sourceforge.htmlcleaner:htmlcleaner:2.24`
import java.nio.file.attribute.PosixFilePermission

import coursier.maven.MavenRepository
import de.tobiasroeser.mill.vcs.version.VcsVersion
import mill._
import mill.define.Target.ctx
import mill.define.{Source, Sources, Target, Task}
import mill.scalalib._
import mill.scalalib.publish._
import mill.modules.Jvm
import os.RelPath

object Settings {
  val pomOrg = "com.lihaoyi"
  val githubOrg = "com-lihaoyi"
  val githubRepo = "mill"
  val projectUrl = s"https://github.com/${githubOrg}/${githubRepo}"
  val docUrl = "https://com-lihaoyi.github.io/mill"
  // the exact branches containing a doc root
  val docBranches = Seq()
  // the exact tags containing a doc root
  val docTags = Seq("0.9.6", "0.9.7", "0.9.8", "0.9.9")
}

object Deps {

  // The Scala version to use
  val scalaVersion = "2.13.6"
  // The Scala 2.12.x version to use for some workers
  val workerScalaVersion212 = "2.12.13"

  object Scalajs_0_6 {
    val scalajsJsEnvs =  ivy"org.scala-js::scalajs-js-envs:0.6.33"
    val scalajsSbtTestAdapter =  ivy"org.scala-js::scalajs-sbt-test-adapter:0.6.33"
    val scalajsTools = ivy"org.scala-js::scalajs-tools:0.6.33"
  }

  object Scalajs_1 {
    val scalajsEnvJsdomNodejs =  ivy"org.scala-js::scalajs-env-jsdom-nodejs:1.1.0"
    val scalajsEnvNodejs =  ivy"org.scala-js::scalajs-env-nodejs:1.2.0"
    val scalajsEnvPhantomjs =  ivy"org.scala-js::scalajs-env-phantomjs:1.0.0"
    val scalajsSbtTestAdapter = ivy"org.scala-js::scalajs-sbt-test-adapter:1.6.0"
    val scalajsLinker = ivy"org.scala-js::scalajs-linker:1.6.0"
  }

  object Scalanative_0_4 {
    val scalanativeTools = ivy"org.scala-native::tools:0.4.0"
    val scalanativeUtil = ivy"org.scala-native::util:0.4.0"
    val scalanativeNir = ivy"org.scala-native::nir:0.4.0"
    val scalanativeTestRunner = ivy"org.scala-native::test-runner:0.4.0"
  }

  val acyclic = ivy"com.lihaoyi::acyclic:0.2.0"
  val ammonite = ivy"com.lihaoyi:::ammonite:2.4.0"
  // Exclude trees here to force the version of we have defined. We use this
  // here instead of a `forceVersion()` on scalametaTrees since it's not
  // respected in the POM causing issues for Coursier Mill users.
  val ammoniteExcludingTrees = ammonite.exclude(
    "org.scalameta" -> "trees_2.13"
  )
  val asciidoctorj = ivy"org.asciidoctor:asciidoctorj:2.4.3"
  val bloopConfig = ivy"ch.epfl.scala::bloop-config:1.4.6-33-1c6f6712"
  val coursier = ivy"io.get-coursier::coursier:2.0.16"
  val flywayCore = ivy"org.flywaydb:flyway-core:6.5.7"
  val graphvizJava = ivy"guru.nidi:graphviz-java:0.18.1"
  // Warning: Avoid ipcsocket version 1.3.0, as it caused many failures on CI
  val ipcsocket = ivy"org.scala-sbt.ipcsocket:ipcsocket:1.0.1"
  val ipcsocketExcludingJna = ipcsocket.exclude(
    "net.java.dev.jna" -> "jna",
    "net.java.dev.jna" -> "jna-platform"
  )
  object jetty {
    val version = "8.2.0.v20160908"
    val server = ivy"org.eclipse.jetty:jetty-server:${version}"
    val websocket =  ivy"org.eclipse.jetty:jetty-websocket:${version}"
  }
  val javaxServlet = ivy"org.eclipse.jetty.orbit:javax.servlet:3.0.0.v201112011016"
  val jgraphtCore = ivy"org.jgrapht:jgrapht-core:1.5.1"

  val jna = ivy"net.java.dev.jna:jna:5.8.0"
  val jnaPlatform = ivy"net.java.dev.jna:jna-platform:5.8.0"

  val junitInterface = ivy"com.github.sbt:junit-interface:0.13.2"
  val lambdaTest = ivy"de.tototec:de.tobiasroeser.lambdatest:0.7.1"
  val osLib = ivy"com.lihaoyi::os-lib:0.7.8"
  val testng = ivy"org.testng:testng:7.4.0"
  val sbtTestInterface = ivy"org.scala-sbt:test-interface:1.0"
  val scalaCheck = ivy"org.scalacheck::scalacheck:1.15.4"
  def scalaCompiler(scalaVersion: String) = ivy"org.scala-lang:scala-compiler:${scalaVersion}"
  val scalafmtDynamic = ivy"org.scalameta::scalafmt-dynamic:2.7.5"
  val scalametaTrees = ivy"org.scalameta::trees:4.4.24"
  def scalaReflect(scalaVersion: String) = ivy"org.scala-lang:scala-reflect:${scalaVersion}"
  def scalacScoveragePlugin = ivy"org.scoverage::scalac-scoverage-plugin:1.4.1"
  val sourcecode = ivy"com.lihaoyi::sourcecode:0.2.7"
  val upickle = ivy"com.lihaoyi::upickle:1.4.0"
  val utest = ivy"com.lihaoyi::utest:0.7.10"
  val windowsAnsi = ivy"io.github.alexarchambault.windows-ansi:windows-ansi:0.0.3"
  val zinc = ivy"org.scala-sbt::zinc:1.5.7"
  val bsp = ivy"ch.epfl.scala:bsp4j:2.0.0-M14"
  val jarjarabrams = ivy"com.eed3si9n.jarjarabrams::jarjar-abrams-core:0.3.1"
}

def millVersion = T { VcsVersion.vcsState().format() }
def millLastTag = T { VcsVersion.vcsState().lastTag.get }
def baseDir = build.millSourcePath

trait MillPublishModule extends PublishModule {
  override def artifactName = "mill-" + super.artifactName()
  def publishVersion = millVersion()
  def pomSettings = PomSettings(
    description = artifactName(),
    organization = Settings.pomOrg,
    url = Settings.projectUrl,
    licenses = Seq(License.MIT),
    versionControl = VersionControl.github(Settings.githubOrg, Settings.githubRepo),
    developers = Seq(
      Developer("lihaoyi", "Li Haoyi","https://github.com/lihaoyi"),
      Developer("lefou", "Tobias Roeser", "https://github.com/lefou")
    )
  )
  override def javacOptions = Seq("-source", "1.8", "-target", "1.8", "-encoding", "UTF-8")
}

trait MillCoursierModule extends CoursierModule {
  override def repositoriesTask = T.task {
    super.repositoriesTask() ++ Seq(
      MavenRepository(
        "https://oss.sonatype.org/content/repositories/releases")
    )
  }
}

trait MillApiModule
    extends MillPublishModule
    with ScalaModule
    with MillCoursierModule {
  def scalaVersion = Deps.scalaVersion
//  def compileIvyDeps = Agg(Deps.acyclic)
//  def scalacOptions = Seq("-P:acyclic:force")
//  def scalacPluginIvyDeps = Agg(Deps.acyclic)

}

trait MillModule extends MillApiModule { outer =>
  override def scalacPluginClasspath =
    super.scalacPluginClasspath() ++ Seq(main.moduledefs.jar())

  def testArgs = T{ Seq.empty[String] }
  def testIvyDeps: T[Agg[Dep]] = Agg(Deps.utest)

  val test = new Tests(implicitly)
  class Tests(ctx0: mill.define.Ctx) extends mill.Module()(ctx0) with super.Tests {
    override def forkArgs = T{ testArgs() }
    override def moduleDeps =
      if (this == main.test) Seq(main)
      else Seq(outer, main.test)
    override def ivyDeps: T[Agg[Dep]] = outer.testIvyDeps()
    override def testFramework = "mill.UTestFramework"
    override def scalacPluginClasspath =
      super.scalacPluginClasspath() ++ Seq(main.moduledefs.jar())
  }
}


object main extends MillModule {
  override def moduleDeps = Seq(core, client)
  override def ivyDeps = Agg(
    Deps.windowsAnsi
  )

  override def compileIvyDeps = Agg(
    Deps.scalaReflect(scalaVersion())
  )

  override def generatedSources = T {
    Seq(PathRef(shared.generateCoreSources(T.ctx.dest)))
  }
  override def testArgs = Seq(
    "-DMILL_VERSION=" + publishVersion(),
  )
  override val test = new Tests(implicitly)
  class Tests(ctx0: mill.define.Ctx) extends super.Tests(ctx0){
    override def generatedSources = T {
      Seq(PathRef(shared.generateCoreTestSources(T.ctx.dest)))
    }
  }
  object api extends MillApiModule {
    override def ivyDeps = Agg(
      Deps.osLib,
      Deps.upickle,
      Deps.sbtTestInterface
    )
  }
  object core extends MillModule {
    override def moduleDeps = Seq(moduledefs, api)

    override def compileIvyDeps = Agg(
      Deps.scalaReflect(scalaVersion())
    )

    override def ivyDeps = Agg(
      Deps.ammoniteExcludingTrees,
      Deps.scalametaTrees,
      Deps.coursier,
      // Necessary so we can share the JNA classes throughout the build process
      Deps.jna,
      Deps.jnaPlatform,
      Deps.jarjarabrams
    )

    override def generatedSources = T {
      val dest = T.ctx.dest
      writeBuildInfo(dest, scalaVersion(), publishVersion(), T.traverse(dev.moduleDeps)(_.publishSelfDependency)())
      shared.generateCoreSources(dest)
      Seq(PathRef(dest))
    }

    def writeBuildInfo(dir : os.Path, scalaVersion: String, millVersion: String, artifacts: Seq[Artifact]) = {
      val code = s"""
        |package mill
        |
        |object BuildInfo {
        |  val scalaVersion = "$scalaVersion"
        |  val millVersion = "$millVersion"
        |  /** Dependency artifacts embedded in mill by default. */
        |  val millEmbeddedDeps = ${artifacts.map(artifact => s""""${artifact.group}:${artifact.id}:${artifact.version}"""")}
        |}
      """.stripMargin.trim

      os.write(dir / "BuildInfo.scala", code)
    }
  }

  object moduledefs extends MillPublishModule with ScalaModule {
    def scalaVersion = Deps.scalaVersion
    override def ivyDeps = Agg(
      Deps.scalaCompiler(scalaVersion()),
      Deps.sourcecode
    )
  }

  object client extends MillPublishModule {
    override def ivyDeps = Agg(
      Deps.ipcsocketExcludingJna
    )
    object test extends Tests with TestModule.Junit4 {
      override def ivyDeps = T{ Agg(
        Deps.junitInterface,
        Deps.lambdaTest
      )}
    }
  }

  object graphviz extends MillModule{
    override def moduleDeps = Seq(main, scalalib)

    override def ivyDeps = Agg(
      Deps.graphvizJava,
      Deps.jgraphtCore
    )
    override def testArgs = Seq(
      "-DMILL_GRAPHVIZ=" + runClasspath().map(_.path).mkString(",")
    )
  }
}


object scalalib extends MillModule {
  override def moduleDeps = Seq(main, scalalib.api)

  override def ivyDeps = Agg(
    Deps.sbtTestInterface,
    Deps.scalafmtDynamic
  )

  def genTask(m: ScalaModule) = T.task{
    Seq(m.jar(), m.sourceJar()) ++
    m.runClasspath()
  }

  override def generatedSources = T{
    val dest = T.ctx.dest
    val artifacts = T.traverse(dev.moduleDeps)(_.publishSelfDependency)()
    os.write(dest / "Versions.scala",
      s"""package mill.scalalib
        |
        |/**
        | * Dependency versions.
        | * Generated from mill in build.sc.
        | */
        |object Versions {
        |  /** Version of Ammonite. */
        |  val ammonite = "${Deps.ammonite.dep.version}"
        |  /** Version of Zinc. */
        |  val zinc = "${Deps.zinc.dep.version}"
        |}
        |
        |""".stripMargin)
    super.generatedSources() ++ Seq(PathRef(dest))
  }

  override def testIvyDeps = super.testIvyDeps() ++ Agg(Deps.scalaCheck)
  def testArgs = T{
    val genIdeaArgs =
      genTask(main.moduledefs)() ++
      genTask(main.core)() ++
      genTask(main)() ++
      genTask(scalalib)() ++
      genTask(scalajslib)() ++
      genTask(scalanativelib)()

    worker.testArgs() ++
    main.graphviz.testArgs() ++
    Seq(
      "-Djna.nosys=true",
      "-DMILL_BUILD_LIBRARIES=" + genIdeaArgs.map(_.path).mkString(","),
      "-DMILL_SCALA_LIB=" + runClasspath().map(_.path).mkString(",")
    )
  }
  object backgroundwrapper extends MillPublishModule{
    override def ivyDeps = Agg(
      Deps.sbtTestInterface
    )
    def testArgs = T{
      Seq(
        "-DMILL_BACKGROUNDWRAPPER=" + runClasspath().map(_.path).mkString(",")
      )
    }
  }
  object api extends MillApiModule {
    override def moduleDeps = Seq(main.api)
  }
  object worker extends MillApiModule {

    override def moduleDeps = Seq(scalalib.api)

    override def ivyDeps = Agg(
      Deps.zinc
    )
    def testArgs = T{Seq(
      "-DMILL_SCALA_WORKER=" + runClasspath().map(_.path).mkString(",")
    )}

    override def generatedSources = T{
      val dest = T.ctx.dest
      val artifacts = T.traverse(dev.moduleDeps)(_.publishSelfDependency)()
      os.write(dest / "Versions.scala",
        s"""package mill.scalalib.worker
           |
           |/**
           | * Dependency versions.
           | * Generated from mill in build.sc.
           | */
           |object Versions {
           |  /** Version of Zinc. */
           |  val zinc = "${Deps.zinc.dep.version}"
           |}
           |
           |""".stripMargin)
      super.generatedSources() ++ Seq(PathRef(dest))
    }
  }
}


object scalajslib extends MillModule {

  override def moduleDeps = Seq(scalalib, scalajslib.api)

  override def testArgs = T{
    val mapping = Map(
      "MILL_SCALAJS_WORKER_0_6" -> worker("0.6").compile().classes.path,
      "MILL_SCALAJS_WORKER_1" -> worker("1").compile().classes.path
    )
    Seq("-Djna.nosys=true") ++
    scalalib.worker.testArgs() ++
    scalalib.backgroundwrapper.testArgs() ++
    (for((k, v) <- mapping.to(Seq)) yield s"-D$k=$v")
  }

  def generatedBuildInfo = T {
    val dir = T.dest
    val resolve = resolveCoursierDependency()
    val packageNames = Seq("mill", "scalajslib")
    val className = "ScalaJSBuildInfo"
    def formatDep(dep: Dep) = {
      val d = resolve(dep)
      s"${d.module.organization.value}:${d.module.name.value}:${d.version}"
    }
    val content =
      s"""package ${packageNames.mkString(".")}
         |/** Generated by mill at built-time. */
         |object ${className} {
         |  object Deps {
         |    val jettyWebsocket = "${formatDep(Deps.jetty.websocket)}"
         |    val jettyServer = "${formatDep(Deps.jetty.server)}"
         |    val javaxServlet = "${formatDep(Deps.javaxServlet)}"
         |    val scalajsEnvNodejs = "${formatDep(Deps.Scalajs_1.scalajsEnvNodejs)}"
         |    val scalajsEnvJsdomNodejs = "${formatDep(Deps.Scalajs_1.scalajsEnvJsdomNodejs)}"
         |    val scalajsEnvPhantomJs = "${formatDep(Deps.Scalajs_1.scalajsEnvPhantomjs)}"
         |  }
         |}
         |""".stripMargin
    os.write(dir / packageNames / s"${className}.scala" , content, createFolders = true)
    PathRef(dir)
  }

  override def generatedSources: Target[Seq[PathRef]] = Seq(generatedBuildInfo())

  object api extends MillApiModule {
    override def moduleDeps = Seq(main.api)
    override def ivyDeps = Agg(Deps.sbtTestInterface)
  }
  object worker extends Cross[WorkerModule]("0.6", "1")
  class WorkerModule(scalajsWorkerVersion: String) extends MillApiModule{
    override def moduleDeps = Seq(scalajslib.api)
    override def ivyDeps = scalajsWorkerVersion match {
      case "0.6" =>
        Agg(
          Deps.Scalajs_0_6.scalajsTools,
          Deps.Scalajs_0_6.scalajsSbtTestAdapter,
          Deps.Scalajs_0_6.scalajsJsEnvs,
          Deps.jetty.websocket,
          Deps.jetty.server,
          Deps.javaxServlet
        )
      case "1" =>
        Agg(
          Deps.Scalajs_1.scalajsLinker,
          Deps.Scalajs_1.scalajsSbtTestAdapter,
          Deps.Scalajs_1.scalajsEnvNodejs,
          Deps.Scalajs_1.scalajsEnvJsdomNodejs,
          Deps.Scalajs_1.scalajsEnvPhantomjs,
          Deps.jetty.websocket,
          Deps.jetty.server,
          Deps.javaxServlet
        )
    }
  }
}


object contrib extends MillModule {
  object testng extends JavaModule with MillModule {
    // pure Java implementation
    override def artifactSuffix: T[String] = ""
    override def scalaLibraryIvyDeps: Target[Agg[Dep]] = T{ Agg.empty[Dep] }
    override def ivyDeps = Agg(
      Deps.sbtTestInterface,
      Deps.testng
    )
    override def testArgs = T{
      Seq(
        "-DMILL_SCALA_LIB=" + scalalib.runClasspath().map(_.path).mkString(","),
        "-DMILL_TESTNG_LIB=" + runClasspath().map(_.path).mkString(","),
      ) ++ scalalib.worker.testArgs()
    }
    override def docJar: T[PathRef] = super[JavaModule].docJar
    override val test = new Tests(implicitly)
    class Tests(ctx0: mill.define.Ctx) extends super.Tests(ctx0) {
      override def compileModuleDeps = Seq(scalalib)
    }
  }

  object twirllib extends MillModule {
    override def compileModuleDeps = Seq(scalalib)
  }

  object playlib extends MillModule {
    override def moduleDeps = Seq(twirllib, playlib.api)
    override def compileModuleDeps = Seq(scalalib)

    override def testArgs = T {
      val mapping = Map(
        "MILL_CONTRIB_PLAYLIB_ROUTECOMPILER_WORKER_2_6" -> worker("2.6").assembly().path,
        "MILL_CONTRIB_PLAYLIB_ROUTECOMPILER_WORKER_2_7" -> worker("2.7").assembly().path,
        "MILL_CONTRIB_PLAYLIB_ROUTECOMPILER_WORKER_2_8" -> worker("2.8").assembly().path
      )

      scalalib.worker.testArgs() ++
        scalalib.backgroundwrapper.testArgs() ++
        (for ((k, v) <- mapping.to(Seq)) yield s"-D$k=$v")
    }

    object api extends MillPublishModule

    object worker extends Cross[WorkerModule]("2.6", "2.7", "2.8")
    class WorkerModule(playBinary: String) extends MillApiModule {
      override def sources = T.sources {
        // We want to avoid duplicating code as long as the Play APIs allow.
        // But if newer Play versions introduce incompatibilities,
        // just remove the shared source dir for that worker and implement directly.
        Seq(PathRef(millSourcePath / os.up / "src-shared")) ++ super.sources()
      }
      override def scalaVersion = playBinary match {
        case "2.6" => Deps.workerScalaVersion212
        case _ => Deps.scalaVersion
      }
      override def moduleDeps = Seq(playlib.api)
      def playVersion = playBinary match {
        case "2.6" => "2.6.25"
        case "2.7" => "2.7.9"
        case "2.8" => "2.8.8"
      }
      override def ivyDeps = Agg(
        Deps.osLib,
        ivy"com.typesafe.play::routes-compiler::$playVersion"
      )
    }
  }

  object scalapblib extends MillModule {
    override def compileModuleDeps = Seq(scalalib)
  }

  object scoverage extends MillModule {
    object api extends MillApiModule {
      override def compileModuleDeps = Seq(main.api)
    }

    override def moduleDeps = Seq(scoverage.api)
    override def compileModuleDeps = Seq(scalalib)

    override def testArgs = T {
      val mapping = Map(
        "MILL_SCOVERAGE_REPORT_WORKER" -> worker.compile().classes.path
      )
      scalalib.worker.testArgs() ++
        scalalib.backgroundwrapper.testArgs() ++
        (for ((k, v) <- mapping) yield s"-D$k=$v")
    }

    // So we can test with buildinfo in the classpath
    override val test = new Tests(implicitly)
    class Tests(ctx0: mill.define.Ctx) extends super.Tests(ctx0) {
      override def moduleDeps = super.moduleDeps :+ contrib.buildinfo
    }

    object worker extends MillApiModule {
      override def moduleDeps = Seq(scoverage.api)
      override def compileIvyDeps = T{
        Agg(
          // compile-time only, need to provide the correct scoverage version runtime
          Deps.scalacScoveragePlugin,
          // provided by mill runtime
          Deps.osLib
        )
      }
    }
  }

  object buildinfo extends MillModule {
    override def compileModuleDeps = Seq(scalalib)
    // why do I need this?
    override def testArgs = T{
      Seq("-Djna.nosys=true") ++
      scalalib.worker.testArgs() ++
      scalalib.backgroundwrapper.testArgs()
    }
   }

  object proguard extends MillModule {
    override def compileModuleDeps = Seq(scalalib)
    override def testArgs = T {
      Seq(
        "-DMILL_SCALA_LIB=" + scalalib.runClasspath().map(_.path).mkString(","),
        "-DMILL_PROGUARD_LIB=" + runClasspath().map(_.path).mkString(",")
      ) ++ scalalib.worker.testArgs()
    }
  }

  object flyway extends MillModule {
    override def compileModuleDeps = Seq(scalalib)
    override def ivyDeps = Agg(Deps.flywayCore)
  }


  object docker extends MillModule {
    override def compileModuleDeps = Seq(scalalib)
  }

  object bloop extends MillModule {
    override def compileModuleDeps = Seq(scalalib, scalajslib, scalanativelib)
    override def ivyDeps = Agg(
      Deps.bloopConfig
    )
    override def testArgs = T(scalanativelib.testArgs())
    override def generatedSources = T{
      val dest = T.ctx.dest
      val artifacts = T.traverse(dev.moduleDeps)(_.publishSelfDependency)()
      os.write(dest / "Versions.scala",
        s"""package mill.contrib.bloop
           |
           |object Versions {
           |  val bloop = "${Deps.bloopConfig.dep.version}"
           |}
           |""".stripMargin)
      super.generatedSources() ++ Seq(PathRef(dest))
    }
  }

  object artifactory extends MillModule {
    override def compileModuleDeps = Seq(scalalib)
  }

  object codeartifact extends MillModule {
    override def compileModuleDeps = Seq(scalalib)
  }

  object versionfile extends MillModule {
    override def compileModuleDeps = Seq(scalalib)
  }

  object bintray extends MillModule {
    override def compileModuleDeps = Seq(scalalib)
  }

}


object scalanativelib extends MillModule {
  override def moduleDeps = Seq(scalalib, scalanativelib.api)

  override def testArgs = T{
    val mapping = Map(
      "MILL_SCALANATIVE_WORKER_0_4" -> worker("0.4").assembly().path
    )
    scalalib.worker.testArgs() ++
    scalalib.backgroundwrapper.testArgs() ++
    (for((k, v) <- mapping.to(Seq)) yield s"-D$k=$v")
  }
  object api extends MillPublishModule {
    override def ivyDeps = Agg(Deps.sbtTestInterface)
  }
  object worker extends Cross[WorkerModule]("0.4")
    class WorkerModule(scalaNativeWorkerVersion: String) extends MillApiModule {
    override def scalaVersion = Deps.workerScalaVersion212
    override def moduleDeps = Seq(scalanativelib.api)
    override def ivyDeps = scalaNativeWorkerVersion match {
      case "0.4" =>
        Agg(
          Deps.osLib,
          Deps.Scalanative_0_4.scalanativeTools,
          Deps.Scalanative_0_4.scalanativeUtil,
          Deps.Scalanative_0_4.scalanativeNir,
          Deps.Scalanative_0_4.scalanativeTestRunner
        )
    }
  }
}

object bsp extends MillModule {
  override def compileModuleDeps = Seq(scalalib, scalajslib, scalanativelib)
  override def ivyDeps = Agg(
    Deps.bsp,
    Deps.sbtTestInterface
  )
}

def testRepos = T{
  Seq(
    "MILL_ACYCLIC_REPO" ->
      shared.downloadTestRepo("lihaoyi/acyclic", "bc41cd09a287e2c270271e27ccdb3066173a8598", T.ctx.dest/"acyclic"),
    "MILL_JAWN_REPO" ->
      shared.downloadTestRepo("non/jawn", "fd8dc2b41ce70269889320aeabf8614fe1e8fbcb", T.ctx.dest/"jawn"),
    "MILL_BETTERFILES_REPO" ->
      shared.downloadTestRepo("pathikrit/better-files", "ba74ae9ef784dcf37f1b22c3990037a4fcc6b5f8", T.ctx.dest/"better-files"),
    "MILL_AMMONITE_REPO" ->
      shared.downloadTestRepo("lihaoyi/ammonite", "26b7ebcace16b4b5b4b68f9344ea6f6f48d9b53e", T.ctx.dest/"ammonite"),
    "MILL_UPICKLE_REPO" ->
      shared.downloadTestRepo("lihaoyi/upickle", "7f33085c890db7550a226c349832eabc3cd18769", T.ctx.dest/"upickle"),
    "MILL_PLAY_JSON_REPO" ->
      shared.downloadTestRepo("playframework/play-json", "0a5ba16a03f3b343ac335117eb314e7713366fd4", T.ctx.dest/"play-json"),
    "MILL_CAFFEINE_REPO" ->
      shared.downloadTestRepo("ben-manes/caffeine", "c02c623aedded8174030596989769c2fecb82fe4", T.ctx.dest/"caffeine")
  )
}

object integration extends MillModule {
  override def moduleDeps = Seq(main.moduledefs, scalalib, scalajslib, scalanativelib)
  override def testArgs = T{
    scalajslib.testArgs() ++
    scalalib.worker.testArgs() ++
    scalalib.backgroundwrapper.testArgs() ++
    scalanativelib.testArgs() ++
    Seq(
      "-DMILL_TESTNG=" + contrib.testng.runClasspath().map(_.path).mkString(","),
      "-DMILL_VERSION=" + publishVersion(),
      "-DMILL_SCALA_LIB=" + scalalib.runClasspath().map(_.path).mkString(","),
      "-Djna.nosys=true"
    ) ++
    (for((k, v) <- testRepos()) yield s"-D$k=$v")
  }
  override def forkArgs = testArgs()
}


def launcherScript(shellJvmArgs: Seq[String],
                   cmdJvmArgs: Seq[String],
                   shellClassPath: Agg[String],
                   cmdClassPath: Agg[String]) = {
  val millMainClass = "mill.MillMain"
  val millClientMainClass = "mill.main.client.MillClientMain"

  mill.modules.Jvm.universalScript(
    shellCommands = {
      val jvmArgsStr = shellJvmArgs.mkString(" ")
      def java(mainClass: String, passMillJvmOpts: Boolean) = {
        val millJvmOpts = if (passMillJvmOpts) "$mill_jvm_opts" else ""
        s"""exec "$$JAVACMD" $jvmArgsStr $$JAVA_OPTS $millJvmOpts -cp "${shellClassPath.mkString(":")}" $mainClass "$$@""""
      }

      s"""if [ -z "$$JAVA_HOME" ] ; then
         |  JAVACMD="java"
         |else
         |  JAVACMD="$$JAVA_HOME/bin/java"
         |fi
         |
         |mill_jvm_opts=""
         |init_mill_jvm_opts () {
         |  if [ -z $$MILL_JVM_OPTS_PATH ] ; then
         |    mill_jvm_opts_file=".mill-jvm-opts"
         |  else
         |    mill_jvm_opts_file=$$MILL_JVM_OPTS_PATH
         |  fi
         |
         |  if [ -f "$$mill_jvm_opts_file" ] ; then
         |    while IFS= read line
         |    do
         |      case $$line in
         |        "-X"*) mill_jvm_opts="$${mill_jvm_opts} $$line"
         |      esac
         |    done <"$$mill_jvm_opts_file"
         |  fi
         |}
         |
         |# Client-server mode doesn't seem to work on WSL, just disable it for now
         |# https://stackoverflow.com/a/43618657/871202
         |if grep -qEi "(Microsoft|WSL)" /proc/version > /dev/null 2> /dev/null ; then
         |    init_mill_jvm_opts
         |    COURSIER_CACHE=.coursier ${java(millMainClass, true)}
         |else
         |    case "$$1" in
         |      -i | --interactive | --repl | --no-server )
         |        init_mill_jvm_opts
         |        ${java(millMainClass, true)}
         |        ;;
         |      *)
         |        ${java(millClientMainClass, false)}
         |        ;;
         |esac
         |fi
         |""".stripMargin
    },
    cmdCommands = {
      val jvmArgsStr = cmdJvmArgs.mkString(" ")
      def java(mainClass: String, passMillJvmOpts: Boolean) = {
        val millJvmOpts = if (passMillJvmOpts) "!mill_jvm_opts!" else ""
        s""""%JAVACMD%" $jvmArgsStr %JAVA_OPTS% $millJvmOpts -cp "${cmdClassPath.mkString(";")}" $mainClass %*"""
      }

      s"""setlocal EnableDelayedExpansion
         |set "JAVACMD=java.exe"
         |if not "%JAVA_HOME%"=="" set "JAVACMD=%JAVA_HOME%\\bin\\java.exe"
         |if "%1" == "-i" set _I_=true
         |if "%1" == "--interactive" set _I_=true
         |if "%1" == "--repl" set _I_=true
         |if "%1" == "--no-server" set _I_=true
         |
         |set "mill_jvm_opts="
         |set "mill_jvm_opts_file=.mill-jvm-opts"
         |if not "%MILL_JVM_OPTS_PATH%"=="" set "mill_jvm_opts_file=%MILL_JVM_OPTS_PATH%"
         |
         |if defined _I_ (
         |  if exist %mill_jvm_opts_file% (
         |    for /f "delims=" %%G in (%mill_jvm_opts_file%) do (
         |      set line=%%G
         |      if "!line:~0,2!"=="-X" set "mill_jvm_opts=!mill_jvm_opts! !line!"
         |    )
         |  )
         |  ${java(millMainClass, true)}
         |) else (
         |  ${java(millClientMainClass, false)}
         |)
         |endlocal
         |""".stripMargin
    }
  )
}

object dev extends MillModule {
  override def moduleDeps = Seq(scalalib, scalajslib, scalanativelib, bsp)


  def forkArgs: T[Seq[String]] =
    (
      scalalib.testArgs() ++
      scalajslib.testArgs() ++
      scalalib.worker.testArgs() ++
      scalanativelib.testArgs() ++
      scalalib.backgroundwrapper.testArgs() ++
      // Workaround for Zinc/JNA bug
      // https://github.com/sbt/sbt/blame/6718803ee6023ab041b045a6988fafcfae9d15b5/main/src/main/scala/sbt/Main.scala#L130
      Seq(
        "-Djna.nosys=true",
        "-DMILL_VERSION=" + publishVersion(),
        "-DMILL_CLASSPATH=" + runClasspath().map(_.path.toString).mkString(",")
      )
    ).distinct

  override def launcher = T{
    val isWin = scala.util.Properties.isWin
    val outputPath = T.ctx.dest / (if (isWin) "run.bat" else "run")

    os.write(outputPath, prependShellScript())

    if (!isWin) {
      os.perms.set(outputPath, "rwxrwxrwx")
    }
    PathRef(outputPath)
  }

  override def extraPublish: T[Seq[PublishInfo]] = T{ Seq(
    PublishInfo(file = assembly(), classifier = Some("assembly"), ivyConfig = "compile")
  )}

  override def assembly = T{
    val isWin = scala.util.Properties.isWin
    val millPath = T.ctx.dest / (if (isWin) "mill.bat" else "mill")
    os.move(super.assembly().path, millPath)
    PathRef(millPath)
  }

  def prependShellScript = T{
    val (millArgs, otherArgs) = forkArgs().partition(arg => arg.startsWith("-DMILL") && !arg.startsWith("-DMILL_VERSION"))
    // Pass Mill options via file, due to small max args limit in Windows
    val vmOptionsFile = T.ctx.dest / "mill.properties"
    val millOptionsContent = millArgs.map(_.drop(2).replace("\\", "/")).mkString("\r\n") // drop -D prefix, replace \ with /
    os.write(vmOptionsFile, millOptionsContent)
    val jvmArgs = otherArgs ++ List(s"-DMILL_OPTIONS_PATH=$vmOptionsFile")
    val classpath = runClasspath().map(_.path.toString)
    launcherScript(
      jvmArgs,
      jvmArgs,
      classpath,
      Agg(pathingJar().path.toString) // TODO not working yet on Windows! see #791
    )
  }

  def pathingJar = T{
    // see http://todayguesswhat.blogspot.com/2011/03/jar-manifestmf-class-path-referencing.html
    // for more detailed explanation
    val isWin = scala.util.Properties.isWin
    val classpath = runClasspath().map{ pathRef =>
      val path = if (isWin) "/" + pathRef.path.toString.replace("\\", "/")
                 else pathRef.path.toString
      if (path.endsWith(".jar")) path
      else path + "/"
    }.mkString(" ")
    val manifestEntries = Map[String,String](
        java.util.jar.Attributes.Name.MANIFEST_VERSION.toString -> "1.0",
        "Created-By" -> "Scala mill",
        "Class-Path" -> classpath
      )
    mill.modules.Jvm.createJar(Agg(), mill.modules.Jvm.JarManifest(manifestEntries))
  }

  def run(args: String*) = T.command{
    args match{
      case Nil => mill.eval.Result.Failure("Need to pass in cwd as first argument to dev.run")
      case wd0 +: rest =>
        val wd = os.Path(wd0, os.pwd)
        os.makeDir.all(wd)
        mill.modules.Jvm.runSubprocess(
          Seq(launcher().path.toString) ++ rest,
          forkEnv(),
          workingDir = wd
        )
        mill.eval.Result.Success(())
    }

  }
}

object docs extends Module {

  /** Generates the mill documentation with Antora. */
  object antora extends Module {
    def npmBase: T[os.Path] = T.persistent { T.dest }
    def prepareAntora(npmDir: os.Path) = {
      Jvm.runSubprocess(
        commandArgs = Seq(
          "npm",
          "install",
          "@antora/cli",
          "@antora/site-generator-default",
          "gitlab:antora/xref-validator"
        ),
        envArgs = Map(),
        workingDir = npmDir
      )
    }
    def runAntora(npmDir: os.Path, workDir: os.Path, args: Seq[String])(implicit
        ctx: mill.api.Ctx.Log
    ) = {
      prepareAntora(npmDir)
      val cmdArgs =
        Seq(s"${npmDir}/node_modules/@antora/cli/bin/antora") ++ args
      ctx.log.debug(s"command: ${cmdArgs.mkString("'", "' '", "'")}")
      Jvm.runSubprocess(
        commandArgs = cmdArgs,
        envArgs = Map("CI" -> "true"),
        workingDir = workDir
      )
      PathRef(workDir / "build" / "site")
    }
    def sources: Source = T.source(millSourcePath)
    def supplementalFiles = T.source(millSourcePath / "supplemental-ui")
    def devAntoraSources: Target[PathRef] = T {
      val dest = T.dest
      shared.mycopy(sources().path, dest, mergeFolders = true)
      val lines = os.read(dest / "antora.yml").linesIterator.map {
        case l if l.startsWith("version:") =>
          s"version: 'master'" + "\n" + s"display-version: '${millVersion()}'"
        case l if l.startsWith("    mill-version:") =>
          s"    mill-version: '${millVersion()}'"
        case l if l.startsWith("    mill-last-tag:") =>
          s"    mill-last-tag: '${millLastTag()}'"
        case l => l
      }
      os.write.over(dest / "antora.yml", lines.mkString("\n"))
      PathRef(dest)
    }
    def githubPagesPlaybookText(authorMode: Boolean): Task[String] = T.task {
      s"""site:
         |  title: Mill
         |  url: ${Settings.docUrl}
         |  start_page: mill::Intro_to_Mill.adoc
         |
         |content:
         |  sources:
         |    - url: ${if (authorMode) baseDir else Settings.projectUrl}
         |      branches: ${ if(Settings.docBranches.isEmpty) "~"
              else Settings.docBranches.map("'" + _ + "'").mkString("[", ",", "]") }
         |      tags: ${Settings.docTags.map("'" + _ + "'").mkString("[", ",", "]")}
         |      start_path: docs/antora
         |    # the master documentation (always in author mode)
         |    - url: ${baseDir}
         |      # edit_url: ${ Settings.projectUrl }/edit/{refname}/{path}
         |      branches: HEAD
         |      start_path: ${devAntoraSources().path.relativeTo(baseDir)}
         |ui:
         |  bundle:
         |    url: https://gitlab.com/antora/antora-ui-default/-/jobs/artifacts/master/raw/build/ui-bundle.zip?job=bundle-stable
         |    snapshot: true
         |  supplemental_files: ${supplementalFiles().path.toString()}
         |
         |asciidoc:
         |  attributes:
         |    mill-github-url: ${Settings.projectUrl}
         |    mill-doc-url: ${Settings.docUrl}
         |    utest-github-url: https://github.com/com-lihaoyi/utest
         |    upickle-github-url: https://github.com/com-lihaoyi/upickle
         |
         |""".stripMargin
    }
    def githubPages = T {
      generatePages(authorMode = false)()
    }
    def localPages = T {
      generatePages(authorMode = true)()
    }
    def generatePages(authorMode: Boolean) = T.task {
      // dependency to sources
      sources()
      val docSite = T.dest
      val playbook = docSite / "antora-playbook.yml"
      val siteDir = docSite / "site"
      os.write(
        target = playbook,
        data = githubPagesPlaybookText(authorMode)(),
        createFolders = true
      )
      // check xrefs
      runAntora(
        npmDir = npmBase(),
        workDir = docSite,
        args = Seq(
          "--generator",
          "@antora/xref-validator",
          playbook.last,
          "--to-dir",
          siteDir.toString(),
          "--attribute",
          "page-pagination"
        ) ++
          Seq("--fetch").filter(_ => !authorMode)
      )
      // generate site (we can skip the --fetch now)
      runAntora(
        npmDir = npmBase(),
        workDir = docSite,
        args = Seq(
          playbook.last,
          "--to-dir",
          siteDir.toString(),
          "--attribute",
          "page-pagination"
        )
      )
      os.write(siteDir / ".nojekyll", "")
      // sanitize devAntora source URLs
      sanitizeDevUrls(siteDir, devAntoraSources().path, sources().path, baseDir)
      PathRef(siteDir)
    }
//    def htmlCleanerIvyDeps = T{ Agg(ivy"net.sourceforge.htmlcleaner:htmlcleaner:2.24")}
    def sanitizeDevUrls(
        dir: os.Path,
        sourceDir: os.Path,
        newSourceDir: os.Path,
        baseDir: os.Path
    ): Unit = {
      val pathToRemove = sourceDir.relativeTo(baseDir).toString()
      val replacePath = newSourceDir.relativeTo(baseDir).toString()
//      println(s"Cleaning relative path '${pathToRemove}' ...")
      import org.htmlcleaner._
      val cleaner = new HtmlCleaner()
      var changed = false
      os.walk(dir).foreach { file =>
        if (os.isFile(file) && file.ext == "html") {
          val node: TagNode = cleaner.clean(file.toIO)
          node.traverse { (parentNode: TagNode, htmlNode: HtmlNode) =>
            htmlNode match {
              case tag: TagNode if tag.getName() == "a" =>
                Option(tag.getAttributeByName("href")).foreach { href =>
                  val newHref = href.replace(pathToRemove, replacePath)
                  if (href != newHref) {
                    tag.removeAttribute("href")
                    tag.addAttribute("href", newHref)
                    changed = true
                    println(s"Replaced: '${href}' --> '${newHref}'")
                  }
                }
                true
              case _ => true
            }
          }
          if(changed) {
            println(s"Writing '${file}' ...")
            val newHtml = new SimpleHtmlSerializer(cleaner.getProperties()).getAsString(node)
            os.write.over(file, newHtml)
          }
        }
      }
    }
  }
}

def assembly = T{

  val version = millVersion()
  val devRunClasspath = dev.runClasspath().map(_.path)
  val filename = if (scala.util.Properties.isWin) "mill.bat" else "mill"
  val commonArgs = Seq(
    "-DMILL_VERSION=" + version,
    // Workaround for Zinc/JNA bug
    // https://github.com/sbt/sbt/blame/6718803ee6023ab041b045a6988fafcfae9d15b5/main/src/main/scala/sbt/Main.scala#L130
    "-Djna.nosys=true"
  )
  val shellArgs = Seq("-DMILL_CLASSPATH=$0") ++ commonArgs
  val cmdArgs = Seq(""""-DMILL_CLASSPATH=%~dpnx0"""") ++ commonArgs
  os.move(
    Jvm.createAssembly(
      devRunClasspath,
      prependShellScript = launcherScript(
        shellArgs,
        cmdArgs,
        Agg("$0"),
        Agg("%~dpnx0")
      )
    ).path,
    T.ctx.dest / filename
  )
  PathRef(T.ctx.dest / filename)
}

def millBootstrap = T.sources(os.pwd / "mill")

def launcher = T{
  val outputPath = T.ctx.dest / "mill"
  val millBootstrapGrepPrefix = "\nDEFAULT_MILL_VERSION="
  os.write(
    outputPath,
    os.read(millBootstrap().head.path)
      .replaceAll(
        millBootstrapGrepPrefix + "[^\\n]+",
        millBootstrapGrepPrefix + millVersion()
      )
  )
  os.perms.set(outputPath, "rwxrwxrwx")
  PathRef(outputPath)
}

def uploadToGithub(authKey: String) = T.command{
  val vcsState = VcsVersion.vcsState()
  val label = vcsState.format()
  if(label != millVersion()) sys.error("Modified mill version detected, aborting upload")
  val releaseTag = vcsState.lastTag.getOrElse(sys.error("Incomplete git history. No tag found.\nIf on CI, make sure your git checkout job includes enough history."))

  if (releaseTag == label){
    scalaj.http.Http(s"https://api.github.com/repos/${Settings.githubOrg}/${Settings.githubRepo}/releases")
      .postData(
        ujson.write(
          ujson.Obj(
            "tag_name" -> releaseTag,
            "name" -> releaseTag
          )
        )
      )
      .header("Authorization", "token " + authKey)
      .asString
  }

  for(example <- Seq("example-1", "example-2", "example-3")) {
    os.copy(os.pwd / "example" / example, T.dest / example)
    os.copy(launcher().path, T.dest / example / "mill")
    os.proc("zip", "-r", T.dest / s"$example.zip", example).call(cwd = T.dest)
    upload.apply(T.dest / s"$example.zip", releaseTag, label + "-" + example + ".zip", authKey, Settings.githubOrg, Settings.githubRepo)
  }
  upload.apply(assembly().path, releaseTag, label + "-assembly", authKey, Settings.githubOrg, Settings.githubRepo)

  upload.apply(launcher().path, releaseTag, label, authKey, Settings.githubOrg, Settings.githubRepo)
}
