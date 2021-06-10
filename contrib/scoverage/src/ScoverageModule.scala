package mill
package contrib
package scoverage

import coursier.{MavenRepository, Repository}
import mill.api.{Loose,PathRef}
import mill.contrib.scoverage.api.ScoverageReportWorkerApi.ReportType
import mill.define.{Command, Persistent, Sources, Target, Task}
import mill.scalalib.{Dep, DepSyntax, JavaModule, Lib, ScalaModule}


/** Adds targets to a [[mill.scalalib.ScalaModule]] to create test coverage reports.
 *
 * This module allows you to generate code coverage reports for Scala projects with
 * [[https://github.com/scoverage Scoverage]] via the
 * [[https://github.com/scoverage/scalac-scoverage-plugin scoverage compiler plugin]].
 *
 * To declare a module for which you want to generate coverage reports you can
 * Extends the `mill.contrib.scoverage.ScoverageModule` trait when defining your
 * Module. Additionally, you must define a submodule that extends the
 * `ScoverageTests` trait that belongs to your instance of `ScoverageModule`.
 *
 * {{{
 * // You have to replace VERSION
 * import $ivy.`com.lihaoyi::mill-contrib-buildinfo:VERSION`
 * import mill.contrib.scoverage.ScoverageModule
 *
 * Object foo extends ScoverageModule  {
 *   def scalaVersion = "2.12.9"
 *   def scoverageVersion = "1.4.0"
 *
 *   object test extends ScoverageTests {
 *     def ivyDeps = Agg(ivy"org.scalatest::scalatest:3.0.5")
 *     def testFrameworks = Seq("org.scalatest.tools.Framework")
 *   }
 * }
 * }}}
 *
 * In addition to the normal tasks available to your Scala module, Scoverage
 * Modules introduce a few new tasks and changes the behavior of an existing one.
 *
 * - mill foo.scoverage.compile      # compiles your module with test instrumentation
 *                                 # (you don't have to run this manually, running the test task will force its invocation)
 *
 * - mill foo.test                   # tests your project and collects metrics on code coverage
 * - mill foo.scoverage.htmlReport   # uses the metrics collected by a previous test run to generate a coverage report in html format
 * - mill foo.scoverage.xmlReport    # uses the metrics collected by a previous test run to generate a coverage report in xml format
 *
 * The measurement data by default is available at `out/foo/scoverage/dataDir/dest/`,
 * the html report is saved in `out/foo/scoverage/htmlReport/dest/`,
 * and the xml report is saved in `out/foo/scoverage/xmlReport/dest/`.
 */
trait ScoverageModule extends ScalaModule { outer: ScalaModule =>
  /**
    * The Scoverage version to use.
    */
  def scoverageVersion: T[String]

  /** decide if the ivy dependency is full-Scala-specific for the given scoverage version. */
  def needsFullScalaVersion(scoverageVersion: String): Boolean = {
    scoverageVersion.split("[.]", 3) match {
      // versions before 1.4.3 are not full-Scala-version specific
      case Array("0", _, _) | Array("1", "0" | "1" | "3", _) | Array("1", "4", "0" | "1" | "2") => false
      case _ => true
    }
  }

  def scoverageRuntimeDep: T[Dep] = T {
    if(needsFullScalaVersion(scoverageVersion()))
      ivy"org.scoverage:::scalac-scoverage-runtime:${scoverageVersion()}"
    else
      ivy"org.scoverage::scalac-scoverage-runtime:${scoverageVersion()}"
  }
  def scoveragePluginDep: T[Dep] = T {
    if(needsFullScalaVersion(scoverageVersion()))
      ivy"org.scoverage:::scalac-scoverage-plugin:${scoverageVersion()}"
    else
      ivy"org.scoverage::scalac-scoverage-plugin:${scoverageVersion()}"
  }

  def toolsClasspath = T {
    scoverageReportWorkerClasspath() ++ scoverageClasspath()
  }

  def scoverageClasspath = T {
    resolveDeps(T.task { Agg(scoveragePluginDep()) })()
  }

  def scoverageReportWorkerClasspath = T {
    val workerKey = "MILL_SCOVERAGE_REPORT_WORKER"
    mill.modules.Util.millProjectModule(
      workerKey,
      s"mill-contrib-scoverage-worker",
      repositoriesTask(),
      resolveFilter = _.toString.contains("mill-contrib-scoverage-worker")
    )
  }

  val scoverage: ScoverageData = new ScoverageData(implicitly)
  class ScoverageData(ctx0: mill.define.Ctx) extends Module()(ctx0) with ScalaModule {

    def doReport(reportType: ReportType): Task[Unit] = T.task {
      ScoverageReportWorker
        .scoverageReportWorker()
        .bridge(toolsClasspath().map(_.path))
        .report(reportType, allSources().map(_.path), Seq(data().path))
    }

    /**
      * The persistent data dir used to store scoverage coverage data.
      * Use to store coverage data at compile-time and by the various report targets.
      */
    def data: Persistent[PathRef] = T.persistent {
      // via the persistent target, we ensure, the dest dir doesn't get cleared
      PathRef(T.dest)
    }

    override def generatedSources: Target[Seq[PathRef]] = T{ outer.generatedSources() }
    override def allSources: Target[Seq[PathRef]] = T{ outer.allSources() }
    override def moduleDeps: Seq[JavaModule] = outer.moduleDeps
    override def compileModuleDeps: Seq[JavaModule] = outer.compileModuleDeps
    override def sources: Sources = T.sources { outer.sources() }
    override def resources: Sources = T.sources { outer.resources() }
    override def scalaVersion = T{ outer.scalaVersion() }
    override def repositories: Seq[Repository] = outer.repositories
    override def repositoriesTask: Task[Seq[Repository]] = T.task { outer.repositoriesTask() }
    override def compileIvyDeps: Target[Loose.Agg[Dep]] = T{ outer.compileIvyDeps() }
    override def ivyDeps: Target[Loose.Agg[Dep]] = T{ outer.ivyDeps() ++ Agg(outer.scoverageRuntimeDep()) }
    override def unmanagedClasspath: Target[Loose.Agg[PathRef]] = T{ outer.unmanagedClasspath() }
    /** Add the scoverage scalac plugin. */
    override def scalacPluginIvyDeps: Target[Loose.Agg[Dep]] = T{ outer.scalacPluginIvyDeps() ++ Agg(outer.scoveragePluginDep()) }
    /** Add the scoverage specific plugin settings (`dataDir`). */
    override def scalacOptions: Target[Seq[String]] = T{ outer.scalacOptions() ++ Seq(s"-P:scoverage:dataDir:${data().path.toIO.getPath()}") }

    def htmlReport(): Command[Unit] = T.command { doReport(ReportType.Html) }
    def xmlReport(): Command[Unit] = T.command { doReport(ReportType.Xml) }
    def consoleReport(): Command[Unit] = T.command { doReport(ReportType.Console) }

    override def skipIdea = outer.skipIdea
  }

  trait ScoverageTests extends outer.Tests {
    override def upstreamAssemblyClasspath = T {
      super.upstreamAssemblyClasspath() ++
      resolveDeps(T.task{Agg(outer.scoverageRuntimeDep())})()
    }
    override def compileClasspath = T {
      super.compileClasspath() ++
      resolveDeps(T.task{Agg(outer.scoverageRuntimeDep())})()
    }
    override def runClasspath = T {
      super.runClasspath() ++
      resolveDeps(T.task{Agg(outer.scoverageRuntimeDep())})()
    }

    // Need the sources compiled with scoverage instrumentation to run.
    override def moduleDeps: Seq[JavaModule] = Seq(outer.scoverage)
  }
}
