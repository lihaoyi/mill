package mill.bsp.newbsp

import java.net.URL
import java.nio.file.Paths

import scala.jdk.CollectionConverters._
import scala.util.{DynamicVariable, Try}

import ammonite.runtime.SpecialClassLoader
import ch.epfl.scala.bsp4j._
import coursier.Resolve
import mill._
import mill.api.Result.Success
import mill.api.{PathRef, Strict}
import mill.define._
import mill.eval.{Evaluator, _}
import mill.scalajslib.ScalaJSModule
import mill.scalalib.Lib.{depToDependency, resolveDependencies, scalaRuntimeIvyDeps}
import mill.scalalib._
import mill.scalalib.api.Util
import mill.scalanativelib._
import os.{Path}

/**
 * Utilities for translating the mill build into
 * BSP information like BuildTargets and BuildTargetIdentifiers
 */
trait MillModuleSupport {

  def withEvaluator[T](f: Evaluator => T): T

  private[this] val scopedEvaluator =
    new DynamicVariable[Option[Evaluator]](None)
  protected def withScopedEvaluator[T](f: Evaluator => T): T = {
    scopedEvaluator.value match {
      case None =>
        withEvaluator { evaluator =>
          scopedEvaluator.withValue(Some(evaluator)) {
            f(scopedEvaluator.value.get)
          }
        }
      case Some(evaluator) =>
        f(evaluator)
    }
  }

  /**
   * Resolve all the mill modules contained in the project
   */
  def getModules(): Seq[JavaModule] = withScopedEvaluator { evaluator =>
    evaluator.rootModule.millInternal.segmentsToModules.values.collect {
      case m: JavaModule => m
    }.toSeq
  }

  /**
   * Resolve a mill modules given a target identifier
   */
  def getModule(
      targetId: BuildTargetIdentifier,
      modules: Seq[JavaModule]
  ): JavaModule =
    modules
      .find(getTargetId(_) == targetId)
      .getOrElse(
        throw new IllegalArgumentException(
          s"No module found for target id ${targetId.getUri}"
        )
      )

  /**
   * Compute mapping between all the JavaModules contained in the
   * working directory ( has to be a mill-based project ) and
   * BSP BuildTargets ( mill modules correspond one-to-one to
   * bsp build targets ).
   *
   * @param modules            All JavaModules contained in the working
   *                           directory of the mill project
   * @return JavaModule -> BuildTarget mapping
   */
  def getTargets(modules: Seq[JavaModule]): Seq[BuildTarget] =
    withScopedEvaluator { evaluator =>
      val targets = modules.map(module => getTarget(module))
      val millBuildTarget = getMillBuildTarget(modules)

      millBuildTarget +: targets
    }

  def getMillBuildTargetId(): BuildTargetIdentifier = withScopedEvaluator {
    evaluator =>
      new BuildTargetIdentifier(
        evaluator.rootModule.millSourcePath.toNIO.toUri.toString
      )
  }

  /**
   * Compute the BuildTarget for the Mill build (build.sc files)
   *
   * @return the Mill BuildTarget
   */
  def getMillBuildTarget(modules: Seq[JavaModule]): BuildTarget =
    withScopedEvaluator { evaluator =>
      val target = new BuildTarget(
        getMillBuildTargetId(),
        Seq.empty[String].asJava,
        Seq("scala").asJava,
        Seq.empty[BuildTargetIdentifier].asJava,
        new BuildTargetCapabilities(false, false, false)
      )
      target.setBaseDirectory(
        evaluator.rootModule.millSourcePath.toNIO.toUri.toString
      )
      target.setDataKind(BuildTargetDataKind.SCALA)
      target.setTags(
        Seq(BuildTargetTag.LIBRARY, BuildTargetTag.APPLICATION).asJava
      )
      target.setDisplayName("mill-build")

      val scalaOrganization = "org.scala-lang"
      val scalaLibDep =
        scalaRuntimeIvyDeps(scalaOrganization, BuildInfo.scalaVersion)

      val repos = Evaluator
        .evalOrElse(
          evaluator,
          T.traverse(modules)(_.repositoriesTask),
          Seq.empty
        )
        .flatten
        .distinct

      val classpath: Seq[PathRef] = Evaluator
        .evalOrElse(
          evaluator,
          T.task {
            resolveDependencies(
              repos,
              depToDependency(_, BuildInfo.scalaVersion),
              scalaLibDep,
              ctx = Some(T.ctx)
            )
          },
          Agg.empty
        )
        .iterator
        .toSeq

      target.setData(
        new ScalaBuildTarget(
          scalaOrganization,
          BuildInfo.scalaVersion,
          Util.scalaBinaryVersion(BuildInfo.scalaVersion),
          ScalaPlatform.JVM,
          classpath.map(_.path.toNIO.toUri.toString).asJava
        )
      )

      target
    }

  def getMillBuildClasspath(
      sources: Boolean
  ): Seq[String] = withScopedEvaluator { evaluator =>
    val classpath = Try(
      evaluator.rootModule.getClass.getClassLoader
        .asInstanceOf[SpecialClassLoader]
    ).fold(
      _ => Seq.empty,
      _.allJars
    )

    val millJars = resolveDependencies(
      Resolve.defaultRepositories,
      depToDependency(_, BuildInfo.scalaVersion),
      BuildInfo.millEmbeddedDeps.map(d => ivy"$d"),
      sources = sources
    ).asSuccess.toSeq.flatMap(_.value).map(_.path.toNIO.toUri.toURL)

    val all = classpath ++ millJars
    val binarySource =
      if (sources) all.filter(url => isSourceJar(url))
      else all.filter(url => !isSourceJar(url))
    binarySource
      .filter(url => os.exists(Path(Paths.get(url.toURI()))))
      .map(_.toURI.toString)
  }

  /**
   * Compute the BuildTarget associated with the given mill
   * JavaModule, which is any module present in the working
   * directory, but it's not the root module itself.
   *
   * @param module      any in-project mill module
   * @return inner BuildTarget
   */
  def getTarget(module: JavaModule): BuildTarget = withScopedEvaluator {
    evaluator =>
      val dataBuildTarget = computeBuildTargetData(module)
      val capabilities = getModuleCapabilities(module)
      val buildTargetTag = module match {
        case _: TestModule => Seq(BuildTargetTag.TEST)
        case _: JavaModule =>
          Seq(BuildTargetTag.LIBRARY, BuildTargetTag.APPLICATION)
      }

      val buildTarget = new BuildTarget(
        getTargetId(module),
        buildTargetTag.asJava,
        Seq("scala", "java").asJava,
        (module.moduleDeps ++ module.compileModuleDeps)
          .map(getTargetId)
          .toList
          .asJava,
        capabilities
      )
      if (module.isInstanceOf[ScalaModule]) {
        buildTarget.setDataKind(BuildTargetDataKind.SCALA)
      }
      buildTarget.setData(dataBuildTarget)
      buildTarget.setDisplayName(module.millModuleSegments.render)
      buildTarget
  }

  // obtain the capabilities of the given module ( ex: canCompile, canRun, canTest )
  private[this] def getModuleCapabilities(
      module: JavaModule
  ): BuildTargetCapabilities = {
    val canTest = module match {
      case _: TestModule => true
      case _             => false
    }

    new BuildTargetCapabilities(true, canTest, true)
  }

  /**
   * Evaluate the given task using the given mill evaluator and return
   * its result of type Result
   *
   * @param task      task to evaluate
   * @tparam T
   */
  def getTaskResult[T](task: Task[T]): Result[Any] = withScopedEvaluator {
    evaluator =>
      evaluator.evaluate(Strict.Agg(task)).results(task)
  }

  /**
   * Evaluate the given task using the given mill evaluator and return
   * its result of type T, or the default value of the evaluation failed.
   *
   * @param task         task to evaluate
   * @param defaultValue default value to return in case of failure
   * @tparam T
   */
  def evaluateInformativeTask[T](
      task: Task[T],
      defaultValue: T
  ): T = withScopedEvaluator { evaluator =>
    val evaluated = evaluator.evaluate(Strict.Agg(task)).results(task)
    evaluated match {
      case Success(_) => evaluated.asSuccess.get.value.asInstanceOf[T]
      case _          => defaultValue
    }
  }

  def getTargetId(module: JavaModule): BuildTargetIdentifier =
    new BuildTargetIdentifier(
      (module.millOuterCtx.millSourcePath / module.millModuleSegments.parts).toNIO.toUri.toString
    )

  def getTarget(
      moduleHashCode: Int,
      modules: Seq[JavaModule]
  ): Option[BuildTarget] =
    modules.find(_.hashCode == moduleHashCode).map(getTarget(_))

  def getTargetId(
      moduleHashCode: Int,
      modules: Seq[JavaModule]
  ): Option[BuildTargetIdentifier] =
    modules.find(_.hashCode == moduleHashCode).map(getTargetId)

  def isSourceJar(url: URL): Boolean = url.getFile.endsWith("-sources.jar")

  // Compute the ScalaBuildTarget from information about the given JavaModule.
  private[this] def computeBuildTargetData(
      module: JavaModule
  ): ScalaBuildTarget = {
    module match {
      case m: ScalaModule =>
        val scalaVersion =
          evaluateInformativeTask(m.scalaVersion, "")
        new ScalaBuildTarget(
          evaluateInformativeTask(m.scalaOrganization, ""),
          scalaVersion,
          Util.scalaBinaryVersion(scalaVersion),
          getScalaTargetPlatform(m),
          computeScalaLangDependencies(m)
            .map(_.path.toNIO.toUri.toString)
            .iterator
            .toSeq
            .asJava
        )
      case _: JavaModule =>
        new ScalaBuildTarget(
          "org.scala-lang",
          BuildInfo.scalaVersion,
          Util.scalaBinaryVersion(BuildInfo.scalaVersion),
          ScalaPlatform.JVM,
          Seq.empty[String].asJava
        )
      case m =>
        throw new IllegalStateException(
          s"Module type of ${m.millModuleSegments.render} not supported by BSP"
        )
    }
  }

  // Compute all relevant scala dependencies of `module`, like scala-library, scala-compiler,
  // and scala-reflect
  private[this] def computeScalaLangDependencies(
      module: ScalaModule
  ): Agg[PathRef] = {
    evaluateInformativeTask(
      module.resolveDeps(module.scalaLibraryIvyDeps),
      Agg.empty[PathRef]
    ) ++
      evaluateInformativeTask(
        module.scalacPluginClasspath,
        Agg.empty[PathRef]
      ) ++
      evaluateInformativeTask(
        module.resolveDeps(module.ivyDeps),
        Agg.empty[PathRef]
      ).filter(pathRef =>
        pathRef.path.toNIO.toUri.toString.contains("scala-compiler") ||
          pathRef.path.toNIO.toUri.toString.contains("scala-reflect") ||
          pathRef.path.toNIO.toUri.toString.contains("scala-library")
      )
  }

  // Obtain the scala platform for `module`
  private[this] def getScalaTargetPlatform(
      module: ScalaModule
  ): ScalaPlatform = {
    module match {
      case _: ScalaNativeModule => ScalaPlatform.NATIVE
      case _: ScalaJSModule     => ScalaPlatform.JS
      case _: ScalaModule       => ScalaPlatform.JVM
    }
  }
}
