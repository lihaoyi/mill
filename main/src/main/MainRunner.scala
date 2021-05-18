package mill.main
import java.io.{InputStream, PrintStream}

import ammonite.Main
import ammonite.interp.Interpreter
import ammonite.compiler.iface.Preprocessor
import ammonite.util.Util.CodeSource
import ammonite.util._
import mill.eval.Evaluator
import mill.api.PathRef
import mill.util.PrintLogger

import scala.annotation.tailrec
import ammonite.runtime.ImportHook
import mill.define.Segments

/**
  * Customized version of [[ammonite.MainRunner]], allowing us to run Mill
  * `build.sc` scripts with mill-specific tweaks such as a custom
  * `scriptCodeWrapper` or with a persistent evaluator between runs.
  */
class MainRunner(val config: ammonite.main.Config,
                 mainInteractive: Boolean,
                 disableTicker: Boolean,
                 outprintStream: PrintStream,
                 errPrintStream: PrintStream,
                 stdIn: InputStream,
                 stateCache0: Option[Evaluator.State] = None,
                 env : Map[String, String],
                 setIdle: Boolean => Unit,
                 debugLog: Boolean,
                 keepGoing: Boolean,
                 systemProperties: Map[String, String],
                 threadCount: Option[Int],
                 ringBell: Boolean,
                 wd: os.Path)
  extends ammonite.MainRunner(
    config, outprintStream, errPrintStream,
    stdIn, outprintStream, errPrintStream,
    wd
  ){

  var stateCache  = stateCache0

  override def watchAndWait(watched: Seq[(ammonite.interp.Watchable, Long)]) = {
    val (watchedPaths, watchedValues) = watched.partitionMap {
      case (ammonite.interp.Watchable.Path(p), _) => Left(())
      case (_, _) => Right(())
    }
    val watchedValueStr = if (watchedValues.isEmpty) "" else s" and ${watchedValues.size} other values"
    printInfo(s"Watching for changes to ${watchedPaths.size} paths$watchedValueStr... (Ctrl-C to exit)")
    def statAll() = watched.forall{ case (file, lastMTime) =>
      file.poll() == lastMTime
    }
    setIdle(true)
    while(statAll()) Thread.sleep(100)
    setIdle(false)
  }

  /**
    * Custom version of [[watchLoop]] that lets us generate the watched-file
    * signature only on demand, so if we don't have config.watch enabled we do
    * not pay the cost of generating it
    */
  @tailrec final def watchLoop2[T](isRepl: Boolean,
                                   printing: Boolean,
                                   run: Main => (Res[T], () => Seq[(ammonite.interp.Watchable, Long)])): Boolean = {
    val (result, watched) = run(initMain(isRepl))

    val success = handleWatchRes(result, printing)
    if (ringBell){
      if (success) println("\u0007")
      else {
        println("\u0007")
        Thread.sleep(250)
        println("\u0007")
      }
    }
    if (!config.core.watch.value) success
    else{
      watchAndWait(watched())
      watchLoop2(isRepl, printing, run)
    }
  }


  val colored = config.core.color.getOrElse(mainInteractive)

  override val colors = if(colored) Colors.Default else Colors.BlackWhite
  override def runScript(scriptPath: os.Path, scriptArgs: List[String]) =
    watchLoop2(
      isRepl = false,
      printing = true,
      (mainCfg: Main) => {
        val logger = PrintLogger(
          colored,
          disableTicker,
          colors,
          outprintStream,
          errPrintStream,
          errPrintStream,
          stdIn,
          debugEnabled = debugLog,
          context = ""
        )
        logger.debug(s"Using explicit system properties: ${systemProperties}")

        val (result, interpWatched) = RunScript.runScript(
          config.core.home,
          mainCfg.wd,
          scriptPath,
          mainCfg.instantiateInterpreter(),
          scriptArgs,
          stateCache,
          logger,
          env,
          keepGoing = keepGoing,
          systemProperties = systemProperties,
          threadCount = threadCount
        )

        result match{
          case Res.Success(data) =>
            val (eval, evalWatches, res) = data

            stateCache = Some(Evaluator.State(eval.rootModule, eval.classLoaderSig, eval.workerCache, interpWatched))
            val watched = () => {
              val alreadyStale = evalWatches.exists(p => p.sig != PathRef(p.path, p.quick).sig)
              // If the file changed between the creation of the original
              // `PathRef` and the current moment, use random junk .sig values
              // to force an immediate re-run. Otherwise calculate the
              // pathSignatures the same way Ammonite would and hand over the
              // values, so Ammonite can watch them and only re-run if they
              // subsequently change
              if (alreadyStale) evalWatches.map(p =>
                (ammonite.interp.Watchable.Path(p.path), util.Random.nextLong())
              )
              else evalWatches.map(p =>
                (ammonite.interp.Watchable.Path(p.path), ammonite.interp.Watchable.pathSignature(p.path))
              )
            }
            (Res(res), () => interpWatched ++ watched())
          case _ => (result, () => interpWatched)
        }
      }
    )

  override def handleWatchRes[T](res: Res[T], printing: Boolean) = {
    res match{
      case Res.Success(value) => true
      case _ => super.handleWatchRes(res, printing)
    }
  }

  override def initMain(isRepl: Boolean): Main = {
    val hooks = ImportHook.defaults + (Seq("ivy") -> MillIvyHook)
    super.initMain(isRepl).copy(
      scriptCodeWrapper = CustomCodeWrapper,
      // Ammonite does not properly forward the wd from CliConfig to Main, so
      // force forward it outselves
      wd = wd,
      importHooks = hooks
    )
  }

  object CustomCodeWrapper extends ammonite.compiler.iface.CodeWrapper {
    def apply(code: String,
              source: CodeSource,
              imports: ammonite.util.Imports,
              printCode: String,
              indexedWrapperName: ammonite.util.Name,
              extraCode: String): (String, String, Int) = {
      import source.pkgName
      val wrapName = indexedWrapperName.backticked
      val path = source.path
        .map(_ / os.up)
        .getOrElse(wd)
      val literalPath = pprint.Util.literalize(path.toString)
      val foreign = if (path != wd) {
        // Computing a path in "out" that uniquely reflects the location
        // of the foreign module relatively to the current build.
        val relative = path.relativeTo(wd)
        // Encoding the number of `/..`
        val ups = if (relative.ups > 0) Seq(s"up-${relative.ups}") else Seq()
        val segs = Seq("foreign-modules") ++ ups ++ relative.segments
        val segsList = segs.map(pprint.Util.literalize(_)).mkString(", ")
        s"Some(_root_.mill.define.Segments.labels($segsList))"
      }
      else "None"

      val top = s"""
        |package ${pkgName.head.encoded}
        |package ${Util.encodeScalaSourcePath(pkgName.tail)}
        |$imports
        |import _root_.mill._
        |object $wrapName
        |extends _root_.mill.define.BaseModule(os.Path($literalPath), foreign0 = $foreign)(
        |  implicitly, implicitly, implicitly, implicitly, mill.define.Caller(())
        |)
        |with $wrapName{
        |  // Stub to make sure Ammonite has something to call after it evaluates a script,
        |  // even if it does nothing...
        |  def $$main() = Iterator[String]()
        |
        |  // Need to wrap the returned Module in Some(...) to make sure it
        |  // doesn't get picked up during reflective child-module discovery
        |  def millSelf = Some(this)
        |
        |  implicit lazy val millDiscover: _root_.mill.define.Discover[this.type] = _root_.mill.define.Discover[this.type]
        |}
        |
        |sealed trait $wrapName extends _root_.mill.main.MainModule{
        |""".stripMargin
      val bottom = "\n}"

      (top, bottom, 1)
    }
  }
}
