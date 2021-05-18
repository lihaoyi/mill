package mill.eval

import java.net.URLClassLoader
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import ammonite.runtime.SpecialClassLoader
import mainargs.MainData
import scala.util.DynamicVariable

import mill.api.{BuildProblemReporter, DummyTestReporter, Strict, TestReporter}
import mill.api.Result.{Aborted, OuterStack, Success}
import mill.api.Strict.Agg
import mill.define.{Ctx => _, _}
import mill.util
import mill.util._
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

case class Labelled[T](task: NamedTask[T],
                       segments: Segments){
  def format = task match{
    case t: Target[T] => Some(t.readWrite.asInstanceOf[upickle.default.ReadWriter[T]])
    case _ => None
  }
  def writer = task match{
    case t: mill.define.Command[T] => Some(t.writer.asInstanceOf[upickle.default.Writer[T]])
    case t: Target[T] => Some(t.readWrite.asInstanceOf[upickle.default.ReadWriter[T]])
    case _ => None
  }
}

/**
  * Evaluate tasks.
  * @param home
  * @param outPath The output base path.
  * @param externalOutPath The output base path to use for external modules.
  * @param rootModule The projects root module.
  * @param baseLogger
  * @param classLoaderSig
  * @param workerCache Mutable worker cache.
  * @param env
  * @param failFast If `true` the first failing task will fail the evaluation.
  *                 If `false`, it tries to evaluate all tasks, running longer and reporting possibly more than one failure.
  * @param threadCount If a [[Some]] the explicit number of threads to use for parallel task evaluation,
  *                    or [[None]] to use n threads where n is the number of available logical processors.
  */
case class Evaluator(
  home: os.Path,
  outPath: os.Path,
  externalOutPath: os.Path,
  rootModule: mill.define.BaseModule,
  baseLogger: Logger,
  classLoaderSig: Seq[(Either[String, java.net.URL], Long)] = Evaluator.classLoaderSig,
  workerCache: mutable.Map[Segments, (Int, Any)] = mutable.Map.empty,
  env: Map[String, String] = Evaluator.defaultEnv,
  failFast: Boolean = true,
  threadCount: Option[Int] = Some(1)
) {

  val effectiveThreadCount: Int = this.threadCount.getOrElse(Runtime.getRuntime().availableProcessors())

  import Evaluator.Evaluated

  val classLoaderSignHash = classLoaderSig.hashCode()

  /**
    * @param goals The tasks that need to be evaluated
    * @param reporter A function that will accept a module id and provide a listener for build problems in that module
    * @param testReporter Listener for test events like start, finish with success/error
    */
  def evaluate(goals: Agg[Task[_]],
               reporter: Int => Option[BuildProblemReporter] = (int: Int) => Option.empty[BuildProblemReporter],
               testReporter: TestReporter = DummyTestReporter,
               logger: Logger = baseLogger): Evaluator.Results = {
    os.makeDir.all(outPath)

    if(effectiveThreadCount > 1) parallelEvaluate(goals, effectiveThreadCount, logger, reporter, testReporter)
    else sequentialEvaluate(goals, logger, reporter, testReporter)
  }

  def sequentialEvaluate(goals: Agg[Task[_]],
                         logger: Logger,
                         reporter: Int => Option[BuildProblemReporter] = (int: Int) => Option.empty[BuildProblemReporter],
                         testReporter: TestReporter = DummyTestReporter) = {
    val (sortedGroups, transitive) = Evaluator.plan(rootModule, goals)

    val evaluated = new Agg.Mutable[Task[_]]
    val results = mutable.LinkedHashMap.empty[Task[_], Result[(Any, Int)]]
    var someTaskFailed: Boolean = false

    val timings = mutable.ArrayBuffer.empty[(Either[Task[_], Labelled[_]], Int, Boolean)]
    for (((terminal, group), i) <- sortedGroups.items().zipWithIndex) {
      if(failFast && someTaskFailed) {
        // we exit early and set aborted state for all left tasks
        group.iterator.foreach { task => results.put(task, Aborted)}

      } else {

        val contextLogger = PrefixLogger(
          out = logger,
          context = "",
          tickerContext = Evaluator.dynamicTickerPrefix.value
        )

        val startTime = System.currentTimeMillis()
        // Increment the counter message by 1 to go from 1/10 to 10/10 instead of 0/10 to 9/10
        val counterMsg = (i+1) + "/" + sortedGroups.keyCount
        val Evaluated(newResults, newEvaluated, cached) = evaluateGroupCached(
          terminal,
          group,
          results,
          counterMsg,
          reporter,
          testReporter,
          contextLogger
        )
        someTaskFailed = someTaskFailed || newResults.exists(task => !task._2.isInstanceOf[Success[_]])

        for(ev <- newEvaluated) evaluated.append(ev)
        for((k, v) <- newResults) results.put(k, v)
        val endTime = System.currentTimeMillis()

        timings.append((terminal, (endTime - startTime).toInt, cached))
      }
    }

    Evaluator.writeTimings(timings.toSeq, outPath)
    Evaluator.Results(
      goals.indexed.map(results(_).map(_._1)),
      evaluated,
      transitive,
      getFailing(sortedGroups, results),
      results.map{case (k, v) => (k, v.map(_._1))}
    )
  }

  def getFailing(sortedGroups: MultiBiMap[Either[Task[_], Labelled[Any]], Task[_]],
                 results: collection.Map[Task[_], Result[(Any, Int)]]) = {

    val failing = new util.MultiBiMap.Mutable[Either[Task[_], Labelled[_]], Result.Failing[_]]
    for((k, vs) <- sortedGroups.items()){
      failing.addAll(
        k,
        vs.items.flatMap(results.get).collect{case f: Result.Failing[_] => f.map(_._1)}
      )
    }
    failing
  }

  private[this] var warnOncePar = true

  def parallelEvaluate(goals: Agg[Task[_]],
                       threadCount: Int,
                       logger: Logger,
                       reporter: Int => Option[BuildProblemReporter] = (int: Int) => Option.empty[BuildProblemReporter],
                       testReporter: TestReporter = DummyTestReporter): Evaluator.Results = {
    if(warnOncePar) {
      logger.info(s"Using experimental parallel evaluator with $threadCount threads")
      warnOncePar = false
    }
    os.makeDir.all(outPath)
    val timeLog = new ParallelProfileLogger(outPath, System.currentTimeMillis())

    val (sortedGroups, transitive) = Evaluator.plan(rootModule, goals)

    val interGroupDeps = findInterGroupDeps(sortedGroups)
    import scala.concurrent._
    val threadPool = java.util.concurrent.Executors.newFixedThreadPool(threadCount)
    try {
      implicit val ec = new ExecutionContext {
        def execute(runnable: Runnable) = threadPool.submit(runnable)
        def reportFailure(t: Throwable): Unit = {}
      }

      val terminals = sortedGroups.keys().toVector

      val failed = new AtomicBoolean(false)
      val totalCount = terminals.size
      val count = new AtomicInteger(1)
      val futures = mutable.Map.empty[Terminal, Future[Option[Evaluated]]]

      // We walk the task graph in topological order and schedule the futures
      // to run asynchronously. During this walk, we store the scheduled futures
      // in a dictionary. When scheduling each future, we are guaranteed that the
      // necessary upstream futures will have already been scheduled and stored,
      // due to the topological order of traversal.
      for (k <- terminals){
        val deps = interGroupDeps(k)
        futures(k) = Future.sequence(deps.map(futures)).map { upstreamValues =>
          if (failed.get()) None
          else {
            val upstreamResults = upstreamValues
              .iterator
              .flatMap(_.iterator.flatMap(_.newResults))
              .toMap

            val startTime = System.currentTimeMillis()
            val threadId = timeLog.getThreadId(Thread.currentThread().getName())
            val fraction = s"${count.getAndIncrement()}/$totalCount"
            val contextLogger = PrefixLogger(
              out = logger,
              context = s"[#${if(effectiveThreadCount > 9) f"$threadId%02d" else threadId}] ",
              tickerContext = Evaluator.dynamicTickerPrefix.value
            )

            val res = evaluateGroupCached(
              k,
              sortedGroups.lookupKey(k),
              upstreamResults,
              fraction,
              reporter,
              testReporter,
              contextLogger
            )

            if (failFast && res.newResults.values.exists(_.asSuccess.isEmpty)) failed.set(true)

            val endTime = System.currentTimeMillis()
            timeLog.timeTrace(
              task = printTerm(k),
              cat = "job",
              startTime = startTime,
              endTime = endTime,
              thread = Thread.currentThread().getName(),
              cached = res.cached
            )
            Some(res)
          }
        }
      }

      val finishedOpts = terminals
        .map(t => (t, Await.result(futures(t), duration.Duration.Inf)))

      val finishedOptsMap = finishedOpts.toMap

      val results = terminals
        .flatMap{t =>
          sortedGroups.lookupKey(t).flatMap{ t0 =>
            finishedOptsMap(t) match{
              case None => Some((t0, Aborted))
              case Some(res) => res.newResults.get(t0).map(r => (t0, r))
            }
          }
        }
        .toMap

      timeLog.close()

      Evaluator.Results(
        goals.indexed.map(results(_).map(_._1)),
        finishedOpts.map(_._2).flatMap(_.toSeq.flatMap(_.newEvaluated)),
        transitive,
        getFailing(sortedGroups, results),
        results.map { case (k, v) => (k, v.map(_._1)) }
      )
    }
    finally threadPool.shutdown()
  }

  // those result which are inputs but not contained in this terminal group
  protected def evaluateGroupCached(terminal: Terminal,
    group: Agg[Task[_]],
    results: collection.Map[Task[_], Result[(Any, Int)]],
    counterMsg: String,
    zincProblemReporter: Int => Option[BuildProblemReporter],
    testReporter: TestReporter,
    logger: Logger
  ): Evaluated = {

    val externalInputsHash = scala.util.hashing.MurmurHash3.orderedHash(
      group.items.flatMap(_.inputs).filter(!group.contains(_))
        .flatMap(results(_).asSuccess.map(_.value._2))
    )

    val sideHashes = scala.util.hashing.MurmurHash3.orderedHash(
      group.iterator.map(_.sideHash)
    )

    val inputsHash = externalInputsHash + sideHashes + classLoaderSignHash

    terminal match {
      case Left(task) =>
        val (newResults, newEvaluated) = evaluateGroup(
          group,
          results,
          inputsHash,
          paths = None,
          maybeTargetLabel = None,
          counterMsg = counterMsg,
          zincProblemReporter,
          testReporter,
          logger
        )
        Evaluated(newResults, newEvaluated.toSeq, false)
      case lntRight @ Right(labelledNamedTask) =>

        val out = if (!labelledNamedTask.task.ctx.external) outPath
          else externalOutPath

        val paths = Evaluator.resolveDestPaths(
          out,
          destSegments(labelledNamedTask)
        )

        if (!os.exists(paths.out)) os.makeDir.all(paths.out)
        val cached = for{
          cached <-
            try Some(upickle.default.read[Evaluator.Cached](paths.meta.toIO))
            catch {case e: Throwable => None}

          if cached.inputsHash == inputsHash
          reader <- labelledNamedTask.format
          parsed <-
            try Some(upickle.default.read(cached.value)(reader))
            catch {case e: Throwable => None}
        } yield (parsed, cached.valueHash)

        val workerCached: Option[Any] = labelledNamedTask.task.asWorker
          .flatMap{w => synchronized{ workerCache.get(w.ctx.segments) }}
          .collect{case (`inputsHash`, v) => v}

        workerCached.map((_, inputsHash)) orElse cached match{
          case Some((v, hashCode)) =>
            val newResults = mutable.LinkedHashMap.empty[Task[_], Result[(Any, Int)]]
            newResults(labelledNamedTask.task) = Result.Success((v, hashCode))

            Evaluated(newResults, Nil, true)

          case _ =>
            // uncached
            if (labelledNamedTask.task.flushDest) os.remove.all(paths.dest)

            val targetLabel = printTerm(lntRight)

            val (newResults, newEvaluated) = Evaluator.dynamicTickerPrefix.withValue(s"[$counterMsg] $targetLabel > ") {
              evaluateGroup(
                group,
                results,
                inputsHash,
                paths = Some(paths),
                maybeTargetLabel = Some(targetLabel),
                counterMsg = counterMsg,
                zincProblemReporter,
                testReporter,
                logger
              )
            }

            newResults(labelledNamedTask.task) match{
              case Result.Failure(_, Some((v, hashCode))) =>
                handleTaskResult(v, v.##, paths.meta, inputsHash, labelledNamedTask)

              case Result.Success((v, hashCode)) =>
                handleTaskResult(v, v.##, paths.meta, inputsHash, labelledNamedTask)

              case _ =>
                // Wipe out any cached meta.json file that exists, so
                // a following run won't look at the cached metadata file and
                // assume it's associated with the possibly-borked state of the
                // destPath after an evaluation failure.
                os.remove.all(paths.meta)
            }

            Evaluated(newResults, newEvaluated.toSeq, false)
        }
    }
  }

  def destSegments(labelledTask : Labelled[_]) : Segments = {
    labelledTask.task.ctx.foreign match {
      case Some(foreignSegments) =>
        foreignSegments ++ labelledTask.segments

      case None =>
        labelledTask.segments
    }
  }


  def handleTaskResult(v: Any,
                       hashCode: Int,
                       metaPath: os.Path,
                       inputsHash: Int,
                       labelledNamedTask: Labelled[_]) = {
    labelledNamedTask.task.asWorker match{
      case Some(w) => synchronized{ workerCache(w.ctx.segments) = (inputsHash, v) }
      case None =>
        val terminalResult = labelledNamedTask
          .writer
          .asInstanceOf[Option[upickle.default.Writer[Any]]]
          .map(w => upickle.default.writeJs(v)(w) -> v)

        for((json, v) <- terminalResult){
          os.write.over(
            metaPath,
            upickle.default.stream(
              Evaluator.Cached(json, hashCode, inputsHash),
              indent = 4
            ),
            createFolders = true
          )
        }
    }
  }

  protected def evaluateGroup(group: Agg[Task[_]],
    results: collection.Map[Task[_], Result[(Any, Int)]],
    inputsHash: Int,
    paths: Option[Evaluator.Paths],
    maybeTargetLabel: Option[String],
    counterMsg: String,
    reporter: Int => Option[BuildProblemReporter],
    testReporter: TestReporter,
    logger: Logger
   ): (mutable.LinkedHashMap[Task[_], Result[(Any, Int)]], mutable.Buffer[Task[_]]) = {

    val newEvaluated = mutable.Buffer.empty[Task[_]]
    val newResults = mutable.LinkedHashMap.empty[Task[_], Result[(Any, Int)]]

    val nonEvaluatedTargets = group.indexed.filterNot(results.contains)

    // should we log progress?
    val logRun = maybeTargetLabel.isDefined && {
      val inputResults = for {
        target <- nonEvaluatedTargets
        item <- target.inputs.filterNot(group.contains)
      } yield results(item).map(_._1)
      inputResults.forall(_.isInstanceOf[Result.Success[_]])
    }

    val tickerPrefix = maybeTargetLabel.map { targetLabel =>
      val prefix = s"[$counterMsg] $targetLabel "
      if(logRun) logger.ticker(prefix)
      prefix + "| "
    }

    val multiLogger = new ProxyLogger(resolveLogger(paths.map(_.log), logger)) {
      override def ticker(s: String): Unit = {
        super.ticker(tickerPrefix.getOrElse("")+s)
      }
    }
    // This is used to track the usage of `T.dest` in more than one Task
    // But it's not really clear what issue we try to prevent here
    // Vice versa, being able to use T.dest in multiple `T.task`
    // is rather essential to split up larger tasks into small parts
    // So I like to disable this detection for now
    var usedDest = Option.empty[(Task[_], Array[StackTraceElement])]
    for (task <- nonEvaluatedTargets) {
      newEvaluated.append(task)
      val targetInputValues = task.inputs
        .map{x => newResults.getOrElse(x, results(x))}
        .collect{ case Result.Success((v, hashCode)) => v }

      val res =
        if (targetInputValues.length != task.inputs.length) Result.Skipped
        else {
          val args = new Ctx(
            targetInputValues.toArray[Any],
            () =>
//              usedDest match {
//              case Some((earlierTask, earlierStack)) if earlierTask != task =>
//                val inner = new Exception("Earlier usage of `dest`")
//                inner.setStackTrace(earlierStack)
//                throw new Exception(
//                  "`dest` can only be used in one place within each Target[T]",
//                  inner
//                )
//              case _ =>


                paths match {
                  case Some(dest) =>
                    if (usedDest.isEmpty) os.makeDir.all(dest.dest)
                    usedDest = Some((task, new Exception().getStackTrace))
                    dest.dest
                  case None =>
                    throw new Exception("No `dest` folder available here")
//                }
            },
            multiLogger,
            home,
            env,
            reporter,
            testReporter
          ) with Ctx.Jobs {
            override def jobs: Int = effectiveThreadCount
          }

          val out = System.out
          val in = System.in
          val err = System.err
          try{
            System.setIn(multiLogger.inStream)
            System.setErr(multiLogger.errorStream)
            System.setOut(multiLogger.outputStream)
            Console.withIn(multiLogger.inStream){
              Console.withOut(multiLogger.outputStream){
                Console.withErr(multiLogger.errorStream){
                  try task.evaluate(args)
                  catch { case NonFatal(e) =>
                    Result.Exception(e, new OuterStack(new Exception().getStackTrace))
                  }
                }
              }
            }
          }finally{
            System.setErr(err)
            System.setOut(out)
            System.setIn(in)
          }
        }

      newResults(task) = for(v <- res) yield {
        (v,
          if (task.isInstanceOf[Worker[_]]) inputsHash
          else v.##
        )
      }
    }

    if(!failFast) maybeTargetLabel.foreach { targetLabel =>
      val taskFailed = newResults.exists(task => !task._2.isInstanceOf[Success[_]])
      if(taskFailed) {
        logger.error(s"[${counterMsg}] ${targetLabel} failed")
      }
    }


    multiLogger.close()

    (newResults, newEvaluated)
  }

  def resolveLogger(logPath: Option[os.Path], logger: Logger): Logger = logPath match{
    case None => logger
    case Some(path) => MultiLogger(logger.colored, logger, new FileLogger(logger.colored, path, debugEnabled = true), logger.inStream)
  }

  /**
    * A terminal or terminal target is some important work unit, that in most cases has a name (Right[Labelled])
    * or was directly called by the user (Left[Task]).
    * It's a T, T.worker, T.input, T.source, T.sources, T.persistent
    */
  type Terminal = Either[Task[_], Labelled[Any]]
  /**
    * A terminal target with all it's inner tasks.
    * To implement a terminal target, one can delegate to other/inner tasks (T.task), those are contained in
    * the 2nd parameter of the tuple.
    */
  type TerminalGroup = (Terminal, Agg[Task[_]])

  // TODO: we could track the deps of the dependency chain, to prioritize tasks with longer chain
  // TODO: we could also track the number of other tasks that depends on a task to prioritize
  private def findInterGroupDeps(sortedGroups: MultiBiMap[Terminal, Task[_]]): Map[Terminal, Seq[Terminal]] = {
    sortedGroups
      .items()
      .map { case (terminal, group) =>
        terminal -> Seq.from(group)
          .flatMap(_.inputs)
          .filterNot(group.contains)
          .distinct
          .map(sortedGroups.lookupValue)
          .distinct
      }
      .toMap
  }

  def printTerm(term: Terminal): String = term match {
    case Left(task) => task.toString()
    case Right(labelledNamedTask) =>
      val Seq(first, rest @_*) = labelledNamedTask.segments.value
      val msgParts = Seq(first.asInstanceOf[Segment.Label].value) ++ rest.map{
        case Segment.Label(s) => "." + s
        case Segment.Cross(s) => "[" + s.mkString(",") + "]"
      }
      msgParts.mkString
  }

}


object Evaluator{
  case class Cached(value: ujson.Value,
                    valueHash: Int,
                    inputsHash: Int)
  object Cached{
    implicit val rw: upickle.default.ReadWriter[Cached] = upickle.default.macroRW
  }
  case class State(rootModule: mill.define.BaseModule,
                   classLoaderSig: Seq[(Either[String, java.net.URL], Long)],
                   workerCache: mutable.Map[Segments, (Int, Any)],
                   watched: Seq[(ammonite.interp.Watchable, Long)])
  // This needs to be a ThreadLocal because we need to pass it into the body of
  // the TargetScopt#read call, which does not accept additional parameters.
  // Until we migrate our CLI parsing off of Scopt (so we can pass the BaseModule
  // in directly) we are forced to pass it in via a ThreadLocal
  val currentEvaluator = new ThreadLocal[mill.eval.Evaluator]

  val defaultEnv: Map[String, String] = System.getenv().asScala.toMap

  case class Paths(out: os.Path,
                   dest: os.Path,
                   meta: os.Path,
                   log: os.Path)
  def makeSegmentStrings(segments: Segments) = segments.value.flatMap{
    case Segment.Label(s) => Seq(s)
    case Segment.Cross(values) => values.map(_.toString)
  }
  def resolveDestPaths(workspacePath: os.Path, segments: Segments, foreignSegments: Option[Segments] = None): Paths = {
    val refinedSegments = foreignSegments.map(_ ++ segments).getOrElse(segments)
    val segmentStrings = makeSegmentStrings(refinedSegments)
    val targetPath = workspacePath / segmentStrings
    Paths(targetPath, targetPath / 'dest, targetPath / "meta.json", targetPath / 'log)
  }

  // check if the build itself has changed
  def classLoaderSig = Thread.currentThread().getContextClassLoader match {
    case scl: SpecialClassLoader => scl.classpathSignature
    case ucl: URLClassLoader =>
      SpecialClassLoader.initialClasspathSignature(ucl)
    case _ => Nil
  }

  case class Timing(label: String,
                    millis: Int,
                    cached: Boolean)

  object Timing{
    implicit val readWrite: upickle.default.ReadWriter[Timing] = upickle.default.macroRW
  }

  def writeTimings(timings: Seq[(Either[Task[_], Labelled[_]], Int, Boolean)], outPath: os.Path): Unit = {
    os.write.over(
      outPath / "mill-profile.json",
      upickle.default.stream(
        timings.map { case (k, v, b) =>
          Evaluator.Timing(k.fold(_ => null, s => s.segments.render), v, b)
        },
        indent = 4
      )
    )
  }

  case class Results(rawValues: Seq[Result[Any]],
                     evaluated: Agg[Task[_]],
                     transitive: Agg[Task[_]],
                     failing: MultiBiMap[Either[Task[_], Labelled[_]], Result.Failing[_]],
                     results: collection.Map[Task[_], Result[Any]]){
    def values = rawValues.collect{case Result.Success(v) => v}
  }

  def plan(rootModule: BaseModule, goals: Agg[Task[_]]) = {
    val transitive = Graph.transitiveTargets(goals)
    val topoSorted = Graph.topoSorted(transitive)
    val sortedGroups = Graph.groupAroundImportantTargets(topoSorted){
      case t: NamedTask[Any]   =>
        val segments = t.ctx.segments
        val finalTaskOverrides = t match{
          case t: Target[_] =>
            rootModule.millInternal.segmentsToTargets.get(segments).fold(0)(_.ctx.overrides)

          case c: mill.define.Command[_] =>
            def findMatching(cls: Class[_]): Option[Seq[(Int, MainData[_, _])]] = {
              rootModule.millDiscover.value.get(cls) match{
                case Some(v) => Some(v)
                case None =>
                  cls.getSuperclass match{
                    case null => None
                    case superCls => findMatching(superCls)
                  }
              }
            }

            findMatching(c.cls) match{
              case Some(v) =>
                v.find(_._2.name == c.ctx.segment.pathSegments.head).get._1
              // For now we don't properly support overrides for external modules
              // that do not appear in the Evaluator's main Discovered listing
              case None => 0
            }

          case c: mill.define.Worker[_] => 0
        }

        val additional =
          if (finalTaskOverrides == t.ctx.overrides) Nil
          else Seq(Segment.Label("overriden")) ++ t.ctx.enclosing.split("\\.|#| ").map(Segment.Label)

        Right(Labelled(t, segments ++ additional))
      case t if goals.contains(t) => Left(t)
    }
    (sortedGroups, transitive)
  }

  case class Evaluated(newResults: collection.Map[Task[_], Result[(Any, Int)]], newEvaluated: Seq[Task[_]], cached: Boolean)

  // Increment the counter message by 1 to go from 1/10 to 10/10
  class NextCounterMsg(taskCount: Int) {
    var counter: Int = 0

    def apply(): String = {
      counter += 1
      counter + "/" + taskCount
    }
  }


  def writeTracings(tracings: Seq[TraceEvent], outPath: os.Path): Unit = {
    os.write.over(
      outPath / "mill-par-profile.json",
      upickle.default.stream(tracings, indent = 2)
    )
  }

  /**
    * Evaluate the given task `e`. In case, the task has no successful result(s), return the `default` value instead.
    */
  def evalOrElse[T](evaluator: Evaluator, e: Task[T], default: => T): T = {
    evaluator.evaluate(Agg(e)).values match {
      case Seq() => default
      case Seq(e: T) => e
    }
  }

  private val dynamicTickerPrefix = new DynamicVariable("")
}
