package mill.bsp.newbsp

import java.io.{
  ByteArrayInputStream,
  ByteArrayOutputStream,
  OutputStream,
  PrintStream,
  PrintWriter
}
import java.util.concurrent.{CancellationException, CompletableFuture}

import scala.jdk.CollectionConverters._
import scala.util.Try
import scala.util.control.NonFatal

import ch.epfl.scala.bsp4j._
import com.google.gson.JsonObject
import mainargs.Flag
import mill.{Agg, BuildInfo, PathRef, T}
import mill.api.{DummyTestReporter, Logger, Result, Strict}
import mill.bsp.{
  BspConfigJson,
  BspTestReporter,
  MillBspLogger,
  TaskParameters,
  Utils
}
import mill.define.Segment.Label
import mill.eval.Evaluator
import mill.main.{MainModule, MainRunner, RunScript}
import mill.scalalib.{JavaModule, ScalaModule, TestModule}
import mill.util.{DummyLogger, PrintLogger}
import org.eclipse.lsp4j.jsonrpc.Launcher
import os.Path

class BspServer(
    serverConfig: BspServerConfig,
    runner: MainRunner,
    projectDir: Path,
    debugStream: Option[OutputStream] = None,
    stopMill: () => Unit,
    stopBspServer: () => Unit
) extends BuildServer
    with JavaBuildServer
    with ScalaBuildServer
    with MillModuleSupport { server =>

  object log {
    val debugOut = server.debugStream.map {
      case s: PrintStream => s
      case s              => new PrintStream(s)
    }
    def debug(msg: String): Unit = debugOut.foreach { _.println(msg) }
  }

  // Helper
  protected def completable[T] = new CompletableFuture[T]()
  protected def completed[T](result: T) = {
    val future = new CompletableFuture[T]()
    future.complete(result)
    future
  }
  private[this] def completable[V](f: => V): CompletableFuture[V] = {
    val future = new CompletableFuture[V]()
    val start = System.currentTimeMillis()
    try {
      future.complete(f)
      log.debug(s"took ${System.currentTimeMillis() - start} milliseconds")
    } catch {
      case NonFatal(e) => future.completeExceptionally(e)
    }
    future
  }

  private[this] var cachedEvaluator: Option[Evaluator] = None

  override def withEvaluator[T](f: Evaluator => T): T = {
    val evaluator: Evaluator = cachedEvaluator match {
      case Some(e) => e
      case None =>
        log.debug("Instantiating new evaluator instance ...")
        synchronized {
          val main = runner.initMain(false)

          val logger: Logger = debugStream match {
            case None => DummyLogger
            case Some(debugStream) =>
              val ps = new PrintStream(debugStream)
              PrintLogger(
                colored = true,
                disableTicker = false,
                colors = ammonite.util.Colors.Default,
                outStream = ps,
                infoStream = ps,
                errStream = ps,
                inStream = new ByteArrayInputStream(Array()),
                debugEnabled = true,
                context = ""
              )
          }

          val evaluator: Try[Evaluator] = RunScript.initEvaluator(
            home = projectDir / "out" / "$ammonite",
            wd = projectDir / "out",
            path = projectDir / "build.sc",
            instantiateInterpreter = main.instantiateInterpreter(),
            stateCache = None,
            log = logger,
            env = sys.env,
            keepGoing = serverConfig.keepGoing.value,
            systemProperties = sys.props.toMap,
            threadCount = serverConfig.threadCount
          )
          cachedEvaluator = evaluator.toOption
          log.debug("New evaluator instance created")
          evaluator.get
        }
    }

    f(evaluator)
  }

  ////////////////////
  // BuildServer API

  private[this] var connectedClient: Option[BuildClient] = None

  override def onConnectWithClient(client: BuildClient): Unit = {
    log.debug(s"onConnectWithClient ${client}")
    connectedClient = Some(client)
  }

  // TODO: Scan current build for supported languages (introduce new module to support extensions)
  override def buildInitialize(
      params: InitializeBuildParams
  ): CompletableFuture[InitializeBuildResult] =
    completable {
      val mutableCapabilities = new BuildServerCapabilities()
      mutableCapabilities.setCompileProvider(
        new CompileProvider(BspServer.languages.asJava)
      )
      mutableCapabilities.setRunProvider(
        new RunProvider(BspServer.languages.asJava)
      )
      mutableCapabilities.setTestProvider(
        new TestProvider(BspServer.languages.asJava)
      )
      mutableCapabilities.setDependencySourcesProvider(true)
      mutableCapabilities.setInverseSourcesProvider(true)
      mutableCapabilities.setResourcesProvider(true)

      //TODO: for now it's false, but will try to support this later
      mutableCapabilities.setBuildTargetChangedProvider(false)

      mutableCapabilities.setCanReload(true)

      log.debug(s"buildInitialize ${params}")

      new InitializeBuildResult(
        BspServer.displayName,
        mill.BuildInfo.millVersion,
        BspServer.bspVersion,
        mutableCapabilities
      )
    }

  override def onBuildInitialized(): Unit = {
    log.debug(s"onBuildInitialized")
    // Nice to know
  }

  override def buildShutdown(): CompletableFuture[AnyRef] = completable {
    log.debug("buildShutdown")
    log.debug("Dropping evaluator instance ...")
    cachedEvaluator = None
    stopMill()
    null.asInstanceOf[AnyRef]
  }

  override def onBuildExit(): Unit = completable {
    log.debug("onBuildExit")
    stopBspServer()
  }

  override def workspaceBuildTargets()
      : CompletableFuture[WorkspaceBuildTargetsResult] = completable {
    log.debug("workspaceBuildTargets")
    val modules = getModules()
    val targets = getTargets(modules)
    new WorkspaceBuildTargetsResult(targets.asJava)
  }

  override def workspaceReload(): CompletableFuture[AnyRef] = completable {
    log.debug(s"workspaceReload")
    log.debug("Reloading workspace ...")
    cachedEvaluator = None
    // trigger re-creation of Evaluator
    withEvaluator { evaluator =>
      log.debug("Workspace reloaded")
    }
    null
  }

  override def buildTargetSources(
      sourcesParams: SourcesParams
  ): CompletableFuture[SourcesResult] = completable {
    withEvaluator { evaluator =>
      val modules = getModules()

      val millBuildTargetId = getMillBuildTargetId()

      def sourceItem(source: Path, generated: Boolean) = {
        val file = source.toIO
        new SourceItem(
          file.toURI.toString,
          if (file.isFile) SourceItemKind.FILE else SourceItemKind.DIRECTORY,
          generated
        )
      }

      val items =
        sourcesParams.getTargets.asScala.foldLeft(Seq.empty[SourcesItem]) {
          (items, targetId) =>
            val newItem =
              if (targetId == millBuildTargetId)
                new SourcesItem(
                  targetId,
                  Seq(
                    sourceItem(
                      evaluator.rootModule.millSourcePath / "src",
                      generated = false
                    )
                  ).asJava // Intellij needs one
                )
              else {
                val module = getModule(targetId, modules)
                val sources = evaluateInformativeTask(
                  module.sources,
                  Seq.empty[PathRef]
                )
                  .map(p => sourceItem(p.path, generated = false))
                val generatedSources = evaluateInformativeTask(
                  module.generatedSources,
                  Seq.empty[PathRef]
                )
                  .map(p => sourceItem(p.path, generated = true))

                new SourcesItem(targetId, (sources ++ generatedSources).asJava)
              }

            items :+ newItem
        }

      new SourcesResult(items.asJava)
    }
  }

  override def buildTargetInverseSources(
      inverseSourcesParams: InverseSourcesParams
  ): CompletableFuture[InverseSourcesResult] = completable {
    val modules = getModules()

    val targets = modules
      .filter(m =>
        evaluateInformativeTask(
          m.allSourceFiles,
          Seq.empty[PathRef]
        )
          .map(_.path.toIO.toURI.toString)
          .contains(inverseSourcesParams.getTextDocument.getUri)
      )
      .map(getTargetId)

    new InverseSourcesResult(targets.asJava)
  }

  override def buildTargetDependencySources(
      dependencySourcesParams: DependencySourcesParams
  ): CompletableFuture[DependencySourcesResult] = completable {
    val modules = getModules()
    val millBuildTargetId = getMillBuildTargetId()

    val items = dependencySourcesParams.getTargets.asScala
      .foldLeft(Seq.empty[DependencySourcesItem]) { (items, targetId) =>
        val all =
          if (targetId == millBuildTargetId)
            getMillBuildClasspath(sources = true)
          else {
            val module = getModule(targetId, modules)
            val sources = evaluateInformativeTask(
              module.resolveDeps(
                T.task(
                  module.transitiveCompileIvyDeps() ++ module
                    .transitiveIvyDeps()
                ),
                sources = true
              ),
              Agg.empty[PathRef]
            )
            val unmanaged = evaluateInformativeTask(
              module.unmanagedClasspath,
              Agg.empty[PathRef]
            )

            (sources ++ unmanaged)
              .map(_.path.toIO.toURI.toString)
              .iterator
              .toSeq
          }
        items :+ new DependencySourcesItem(targetId, all.asJava)
      }

    new DependencySourcesResult(items.asJava)
  }

  override def buildTargetResources(
      resourcesParams: ResourcesParams
  ): CompletableFuture[ResourcesResult] = completable {
    val modules = getModules()
    val millBuildTargetId = getMillBuildTargetId()

    val items = resourcesParams.getTargets.asScala
      .filter(_ != millBuildTargetId)
      .foldLeft(Seq.empty[ResourcesItem]) { (items, targetId) =>
        val resources = evaluateInformativeTask(
          getModule(targetId, modules).resources,
          Seq.empty[PathRef]
        )
          .filter(pathRef => os.exists(pathRef.path))

        items :+ new ResourcesItem(
          targetId,
          resources.map(_.path.toNIO.toUri.toString).asJava
        )
      }

    new ResourcesResult(items.asJava)
  }

  override def buildTargetCompile(
      compileParams: CompileParams
  ): CompletableFuture[CompileResult] = completable {
    withEvaluator { evaluator =>
      val modules = getModules()
      val millBuildTargetId = getMillBuildTargetId()

      val params = TaskParameters.fromCompileParams(compileParams)
      val taskId = params.hashCode()
      val compileTasks = Strict.Agg(
        params.getTargets.distinct
          .filter(_ != millBuildTargetId)
          .map(getModule(_, modules).compile): _*
      )
      val result = evaluator.evaluate(
        compileTasks,
        Utils.getBspLoggedReporterPool(
          params,
          modules,
          evaluator,
          connectedClient.get
        ),
        DummyTestReporter,
        new MillBspLogger(connectedClient.get, taskId, evaluator.baseLogger)
      )
      val compileResult = new CompileResult(Utils.getStatusCode(result))
      compileResult.setOriginId(compileParams.getOriginId)
      compileResult // TODO: See in what form IntelliJ expects data about products of compilation in order to set data field
    }
  }

  override def buildTargetTest(
      testParams: TestParams
  ): CompletableFuture[TestResult] = completable {
    withEvaluator { evaluator =>
      val client = connectedClient.get

      val modules = getModules()
      val targets = getTargets(modules)
      val millBuildTargetId = getMillBuildTargetId()

      val params = TaskParameters.fromTestParams(testParams)
      val argsMap =
        try {
          val scalaTestParams = testParams.getData.asInstanceOf[JsonObject]
          (for (
            testItem <- scalaTestParams
              .get("testClasses")
              .getAsJsonArray
              .asScala
          )
            yield (
              testItem.getAsJsonObject
                .get("target")
                .getAsJsonObject
                .get("uri")
                .getAsString,
              testItem.getAsJsonObject
                .get("classes")
                .getAsJsonArray
                .asScala
                .map(elem => elem.getAsString)
                .toSeq
            )).toMap
        } catch {
          case _: Exception =>
            (for (targetId <- testParams.getTargets.asScala)
              yield (targetId.getUri, Seq.empty[String])).toMap
        }

      val overallStatusCode = testParams.getTargets.asScala
        .filter(_ != millBuildTargetId)
        .foldLeft(StatusCode.OK) { (overallStatusCode, targetId) =>
          getModule(targetId, modules) match {
            case testModule: TestModule =>
              val testTask = testModule.testLocal(argsMap(targetId.getUri): _*)

              // notifying the client that the testing of this build target started
              val taskStartParams =
                new TaskStartParams(new TaskId(testTask.hashCode().toString))
              taskStartParams.setEventTime(System.currentTimeMillis())
              taskStartParams.setMessage("Testing target: " + targetId)
              taskStartParams.setDataKind(TaskDataKind.TEST_TASK)
              taskStartParams.setData(new TestTask(targetId))
              client.onBuildTaskStart(taskStartParams)

              val testReporter =
                new BspTestReporter(
                  client,
                  targetId,
                  new TaskId(testTask.hashCode().toString),
                  Seq.empty[String]
                )

              val results = evaluator.evaluate(
                Strict.Agg(testTask),
                Utils
                  .getBspLoggedReporterPool(params, modules, evaluator, client),
                testReporter,
                new MillBspLogger(
                  client,
                  testTask.hashCode,
                  evaluator.baseLogger
                )
              )
              val statusCode = Utils.getStatusCode(results)

              // Notifying the client that the testing of this build target ended
              val taskFinishParams = new TaskFinishParams(
                new TaskId(testTask.hashCode().toString),
                statusCode
              )
              taskFinishParams.setEventTime(System.currentTimeMillis())
              taskFinishParams.setMessage(
                "Finished testing target" + targets
                  .find(_.getId == targetId)
                  .fold("")(t => s": ${t.getDisplayName}")
              )
              taskFinishParams.setDataKind(TaskDataKind.TEST_REPORT)
              taskFinishParams.setData(testReporter.getTestReport)
              client.onBuildTaskFinish(taskFinishParams)

              (statusCode, overallStatusCode) match {
                case (StatusCode.ERROR, _) | (_, StatusCode.ERROR) =>
                  StatusCode.ERROR
                case (StatusCode.CANCELLED, _) => StatusCode.CANCELLED
                case (StatusCode.OK, _)        => StatusCode.OK
              }

            case _ => overallStatusCode
          }
        }

      val testResult = new TestResult(overallStatusCode)
      params.getOriginId match {
        case None     => testResult
        case Some(id) =>
          // TODO: Add the messages from mill to the data field?
          testResult.setOriginId(id)
          testResult
      }
    }
  }

  override def buildTargetRun(
      runParams: RunParams
  ): CompletableFuture[RunResult] = completable {
    withEvaluator { evaluator =>
      val modules = getModules()

      val params = TaskParameters.fromRunParams(runParams)
      val module = getModule(params.getTargets.head, modules)
      val args = params.getArguments.getOrElse(Seq.empty[String])
      val runTask = module.run(args: _*)
      val runResult = evaluator.evaluate(
        Strict.Agg(runTask),
        Utils.getBspLoggedReporterPool(
          params,
          modules,
          evaluator,
          connectedClient.get
        ),
        logger = new MillBspLogger(
          connectedClient.get,
          runTask.hashCode(),
          evaluator.baseLogger
        )
      )
      val response = runResult.results(runTask) match {
        case _: Result.Success[Any] => new RunResult(StatusCode.OK)
        case _                      => new RunResult(StatusCode.ERROR)
      }
      params.getOriginId match {
        case Some(id) => response.setOriginId(id)
        case None     =>
      }

      response
    }
  }

  override def buildTargetCleanCache(
      cleanCacheParams: CleanCacheParams
  ): CompletableFuture[CleanCacheResult] = completable {
    withEvaluator { evaluator =>
      evaluator.rootModule match {
        case mainModule: MainModule =>
          val modules = getModules()
          val millBuildTargetId = getMillBuildTargetId()

          val (msg, cleaned) = cleanCacheParams.getTargets.asScala
            .filter(_ != millBuildTargetId)
            .foldLeft(("", true)) { case ((msg, cleaned), targetId) =>
              val module = getModule(targetId, modules)
              val cleanTask = mainModule.clean(
                evaluator,
                Seq(s"${module.millModuleSegments.render}.compile"): _*
              )
              val cleanResult = evaluator.evaluate(
                Strict.Agg(cleanTask),
                logger = new MillBspLogger(
                  connectedClient.get,
                  cleanTask.hashCode,
                  evaluator.baseLogger
                )
              )
              if (cleanResult.failing.keyCount > 0)
                (
                  msg + s" Target ${module.millModuleSegments.render} could not be cleaned. See message from mill: \n" +
                    (cleanResult.results(cleanTask) match {
                      case fail: Result.Failure[Any] => fail.msg + "\n"
                      case _                         => "could not retrieve message"
                    }),
                  false
                )
              else {
                val outDir = Evaluator
                  .resolveDestPaths(
                    evaluator.outPath,
                    module.millModuleSegments ++ Seq(Label("compile"))
                  )
                  .out
                while (os.exists(outDir)) Thread.sleep(10)

                (
                  msg + s"${module.millModuleSegments.render} cleaned \n",
                  cleaned
                )
              }
            }
          new CleanCacheResult(msg, cleaned)

        case _ =>
          new CleanCacheResult(
            "Nothing cleaned. Could not find MainModule.",
            /* cleaned*/ false
          )
      }
    }
  }

  ////////////////////////
  // JavaBuildServer API

  override def buildTargetJavacOptions(
      javacOptionsParams: JavacOptionsParams
  ): CompletableFuture[JavacOptionsResult] = completable {
    withEvaluator { evaluator =>
      val modules = getModules()
      val millBuildTargetId = getMillBuildTargetId()

      val items = javacOptionsParams.getTargets.asScala
        .foldLeft(Seq.empty[JavacOptionsItem]) { (items, targetId) =>
          val newItem =
            if (targetId == millBuildTargetId) {
              val classpath = getMillBuildClasspath(sources = false)
              Some(
                new JavacOptionsItem(
                  targetId,
                  Seq.empty.asJava,
                  classpath.iterator.toSeq.asJava,
                  evaluator.outPath.toNIO.toUri.toString
                )
              )
            } else
              getModule(targetId, modules) match {
                case m: JavaModule =>
                  val options = evaluateInformativeTask(
                    m.javacOptions,
                    Seq.empty[String]
                  ).toList
                  val classpath = evaluateInformativeTask(
                    m.compileClasspath,
                    Agg.empty[PathRef]
                  )
                    .map(_.path.toNIO.toUri.toString)
                  val classDirectory = (Evaluator
                    .resolveDestPaths(
                      evaluator.outPath,
                      m.millModuleSegments ++ Seq(Label("compile"))
                    )
                    .dest / "classes").toNIO.toUri.toString

                  Some(
                    new JavacOptionsItem(
                      targetId,
                      options.asJava,
                      classpath.iterator.toSeq.asJava,
                      classDirectory
                    )
                  )
                case _ => None
              }

          items ++ newItem
        }

      new JavacOptionsResult(items.asJava)
    }
  }

  /////////////////////////
  // ScalaBuildServer API
  override def buildTargetScalacOptions(
      scalacOptionsParams: ScalacOptionsParams
  ): CompletableFuture[ScalacOptionsResult] = completable {
    withEvaluator { evaluator =>
      val modules = getModules()
      val millBuildTargetId = getMillBuildTargetId()

      val items = scalacOptionsParams.getTargets.asScala
        .foldLeft(Seq.empty[ScalacOptionsItem]) { (items, targetId) =>
          val newItem =
            if (targetId == millBuildTargetId) {
              val classpath = getMillBuildClasspath(sources = false)
              Some(
                new ScalacOptionsItem(
                  targetId,
                  Seq.empty.asJava,
                  classpath.iterator.toSeq.asJava,
                  evaluator.outPath.toNIO.toUri.toString
                )
              )
            } else
              getModule(targetId, modules) match {
                case m: ScalaModule =>
                  val options = evaluateInformativeTask(
                    m.scalacOptions,
                    Seq.empty[String]
                  ).toList
                  val classpath = evaluateInformativeTask(
                    m.compileClasspath,
                    Agg.empty[PathRef]
                  )
                    .map(_.path.toNIO.toUri.toString)
                  val classDirectory = (Evaluator
                    .resolveDestPaths(
                      evaluator.outPath,
                      m.millModuleSegments ++ Seq(Label("compile"))
                    )
                    .dest / "classes").toNIO.toUri.toString

                  Some(
                    new ScalacOptionsItem(
                      targetId,
                      options.asJava,
                      classpath.iterator.toSeq.asJava,
                      classDirectory
                    )
                  )
                case _ => None
              }

          items ++ newItem
        }

      new ScalacOptionsResult(items.asJava)
    }
  }

  override def buildTargetScalaTestClasses(
      scalaTestClassesParams: ScalaTestClassesParams
  ): CompletableFuture[ScalaTestClassesResult] = completable {
    withEvaluator { evaluator =>
      val modules = getModules()

      val items =
        scalaTestClassesParams.getTargets.asScala.foldLeft(
          Seq.empty[ScalaTestClassesItem]
        ) { (items, targetId) =>
          val newItem = getModule(targetId, modules) match {
            case module: TestModule =>
              val testClasses: Seq[String] = evaluateInformativeTask(
                T.task {
                  Utils.getTestClasses(module, evaluator).toList
                },
                Seq.empty[String]
              )
              Some(new ScalaTestClassesItem(targetId, testClasses.asJava))
            case _: JavaModule =>
              None // TODO: maybe send a notification that this target has no test classes
          }

          items ++ newItem
        }

      new ScalaTestClassesResult(items.asJava)
    }
  }

  override def buildTargetScalaMainClasses(
      scalaMainClassesParams: ScalaMainClassesParams
  ): CompletableFuture[ScalaMainClassesResult] = completable {
    withEvaluator { evaluator =>
      val modules = getModules()
      val millBuildTargetId = getMillBuildTargetId()

      val items = scalaMainClassesParams.getTargets.asScala
        .filter(_ != millBuildTargetId)
        .foldLeft(Seq.empty[ScalaMainClassesItem]) { (items, targetId) =>
          val module = getModule(targetId, modules)
          val scalaMainClasses = getTaskResult(module.finalMainClassOpt) match {
            case result: Result.Success[_] =>
              result.asSuccess.get.value match {
                case mainClass: Right[String, String] =>
                  Seq(
                    new ScalaMainClass(
                      mainClass.value,
                      Seq.empty[String].asJava,
                      evaluateInformativeTask(
                        module.forkArgs,
                        Seq.empty[String]
                      ).toList.asJava
                    )
                  )
                case msg: Left[String, String] =>
                  val messageParams =
                    new ShowMessageParams(MessageType.WARNING, msg.value)
                  messageParams.setOriginId(scalaMainClassesParams.getOriginId)
                  connectedClient.get.onBuildShowMessage(
                    messageParams
                  ) // tell the client that no main class was found or specified
                  Seq.empty[ScalaMainClass]
              }
            case _ => Seq.empty[ScalaMainClass]
          }

          items :+ new ScalaMainClassesItem(targetId, scalaMainClasses.asJava)
        }

      new ScalaMainClassesResult(items.asJava)
    }
  }
}

object BspServer {

  case class BspServerExit(code: Int, msg: Option[String] = None)
      extends Exception(msg.orNull)

  def main(args: Array[String]): Unit = {
    try run(args)
    catch {
      case BspServerExit(code, msg) =>
        msg.foreach { m => System.err.println(m) }
        System.exit(code)
      case NonFatal(x) =>
        System.exit(1)
    }
  }

  // TODO: can we get this from bsp4j?
  val bspVersion = "2.0.0"
  val displayName = "mill-bsp-2"
  val languages = Seq("scala", "java")

  def install(projectDir: os.Path) = {
    val jsonFile = projectDir / ".bsp" / "mill.json"
    if (os.exists(jsonFile)) {
      println("warning: overriding already existing bsp connection file")
    }
    val millPath = sys.props
      .get("java.class.path")
      .getOrElse(
        throw new IllegalStateException(
          "System property java.class.path not set"
        )
      )

    os.write.over(
      target = jsonFile,
      data = upickle.default.write(
        t = BspConfigJson(
          name = displayName,
          argv = Seq(millPath),
          millVersion = BuildInfo.millVersion,
          bspVersion = bspVersion,
          languages = languages
        ),
        indent = 2
      ),
      createFolders = true
    )
  }

  def run(args: Seq[String]): Unit = {
    val parser = mainargs.ParserForClass[BspServerConfig]
    val config: BspServerConfig =
      parser.constructEither(
        args = args,
        autoPrintHelpAndExit = None,
        printHelpOnExit = false
      ) match {
        case Right(c)  => c
        case Left(msg) => throw BspServerExit(1, Some(msg))
      }

    if (config.help.value) {
      throw BspServerExit(0, Some(parser.helpText()))
    }

    val projectDir =
      config.dir.map(dir => Path.apply(dir, os.pwd)).getOrElse(os.pwd)

    if (config.install.value) {
      // we only want to create the connection file and return afterwards
      install(projectDir)
      throw new BspServerExit(0)
    }

    val ammConfig = ammonite.main.Config(
      core = config.ammoniteCore,
      predef =
        ammonite.main.Config.Predef(predefCode = "", noHomePredef = Flag()),
      repl = ammonite.main.Config.Repl(
        banner = "",
        noRemoteLogging = Flag(),
        classBased = Flag()
      )
    )

    val stderr = System.err
    val stdin = new ByteArrayInputStream(Array())

    val runner = new mill.main.MainRunner(
      config = ammConfig,
      mainInteractive = false,
      disableTicker = false,
      outprintStream = stderr,
      errPrintStream = stderr,
      stdIn = stdin,
      stateCache0 = None,
      env = sys.env,
      setIdle = b => (),
      debugLog = true,
      keepGoing = config.keepGoing.value,
      systemProperties = sys.props.toMap,
      threadCount = config.threadCount,
      ringBell = false,
      wd = projectDir
    )

    val bspServer = new BspServer(
      config,
      runner = runner,
      projectDir = projectDir,
      debugStream = Some(System.err),
      stopMill = { () =>
        System.err.println("TODO: Mill shutdown")
        ()
      },
      stopBspServer = { () =>
        System.err.println("TODO: BSP Server shutdown")
        ()
      }
    )

    try {
      val launcher = new Launcher.Builder[BuildClient]()
        .setOutput(System.out)
        .setInput(System.in)
        .setLocalService(bspServer)
        .setRemoteInterface(classOf[BuildClient])
        .traceMessages(new PrintWriter((projectDir / ".bsp" / "mill.log").toIO))
        // .setExecutorService(executor)
        .create()
      // millServer.onConnectWithClient(launcher.getRemoteProxy)
      launcher.startListening().get()
    } catch {
      case _: CancellationException =>
        System.err.println("The mill server was shut down.")
      case e: Exception =>
        System.err.println(
          s"""An exception occurred while connecting to the client.
             |Cause: ${e.getCause}
             |Message: ${e.getMessage}
             |Exception class: ${e.getClass}
             |Stack Trace: ${e.getStackTrace}""".stripMargin
        )
    } finally {
      System.err.println("Shutting down executor")
      // executor.shutdown()
    }
  }
}
