package mill

import java.io.{InputStream, PrintStream}
import java.util.Locale

import scala.jdk.CollectionConverters._
import scala.util.Properties
import io.github.retronym.java9rtexport.Export
import mainargs.{Flag, Leftover, arg}
import mill.eval.Evaluator
import mill.api.DummyInputStream

case class MillConfig(
  ammoniteCore: ammonite.main.Config.Core,
  @arg(doc = """Run Mill in interactive mode and start a build REPL. In this mode, no
    mill server will be used. Must be the first argument.""")
  repl: Flag,
  @arg(
    name = "no-server",
    doc = """Run Mill in interactive mode, suitable for opening REPLs and taking user
      input. In this mode, no mill server will be used. Must be the first argument.""")
  noServer: Flag,
  @arg(
    short = 'i',
    doc = """Run Mill in interactive mode, suitable for opening REPLs and taking user
      input. In this mode, no mill server will be used. Must be the first argument.""")
  interactive: Flag,
  @arg(
    name = "version",
    short = 'v',
    doc = "Show mill version and exit.")
  showVersion: Flag,
  @arg(
    name = "bell",
    short = 'b',
    doc = "Ring the bell once if the run completes successfully, twice if it fails.")
  ringBell: Flag,
  @arg(
    name = "disable-ticker",
    doc = "Disable ticker log (e.g. short-lived prints of stages and progress bars)")
  disableTicker: Flag,
  @arg(
    name = "debug",
    short = 'd',
    doc = "Show debug output on STDOUT")
  debugLog: Flag,
  @arg(
    name = "keep-going",
    short = 'k',
    doc = "Continue build, even after build failures")
  keepGoing: Flag,
  @arg(
    name = "define",
    short = 'D',
    doc = "Define (or overwrite) a system property")
  extraSystemProperties: Map[String, String],
  @arg(
    name = "jobs",
    short = 'j',
    doc = """Allow processing N targets in parallel. Use 1 to disable parallel and 0 to
      use as much threads as available processors."""
  )
  threadCountRaw: Option[Int],
  @arg(
    name = "rest",
    doc = """The name of the targets you want to build, followed by any parameters
      you wish to pass to those targets."""
  )
  leftoverArgs: Leftover[String]
)

object MillMain {

  def main(args: Array[String]): Unit = {

    if (Properties.isWin && System.console() != null)
      io.github.alexarchambault.windowsansi.WindowsAnsi.setup()

    val (result, _) = main0(
      args,
      None,
      ammonite.Main.isInteractive(),
      System.in,
      System.out,
      System.err,
      System.getenv().asScala.toMap,
      b => (),
      initialSystemProperties = Map()
    )
    System.exit(if (result) 0 else 1)
  }

  def main0(
    args: Array[String],
    stateCache: Option[Evaluator.State],
    mainInteractive: Boolean,
    stdin: InputStream,
    stdout: PrintStream,
    stderr: PrintStream,
    env: Map[String, String],
    setIdle: Boolean => Unit,
    initialSystemProperties: Map[String, String]
  ): (Boolean, Option[Evaluator.State]) = {

    val millHome = mill.api.Ctx.defaultHome
    val parser = mainargs.ParserForClass[MillConfig]
    val customName = "Mill Build Tool"
    val customDoc =  "usage: mill [mill-options] [target [target-options]]"
    if (args.take(1).toSeq == Seq("--help")){
      stdout.println(parser.helpText(customName = customName, customDoc = customDoc))
      (true, None)
    }else parser.constructEither(
      args,
      allowRepeats = true, autoPrintHelpAndExit = None,
      customName = customName, customDoc = customDoc
    ) match{
      case Left(msg) =>
        stderr.println(msg)
        (false, None)
      case Right(config) =>
        if ((config.interactive.value || config.repl.value || config.noServer.value) &&
            stdin == DummyInputStream){
          stderr.println("-i/--interactive/--repl/--no-server must be passed in as the first argument")
          (false, None)
        }else if (Seq(config.interactive.value, config.repl.value, config.noServer.value).count(identity) > 1){
          stderr.println("Only one of -i/--interactive, --repl, or --no-server may be given")
          (false, None)
        }else if (config.showVersion.value){
          def p(k: String, d: String = "<unknown>") = System.getProperty(k, d)
          stdout.println(
            s"""Mill Build Tool version ${p("MILL_VERSION", "<unknown mill version>")}
               |Java version: ${p("java.version", "<unknown Java version")}, vendor: ${p("java.vendor", "<unknown Java vendor")}, runtime: ${p("java.home", "<unknown runtime")}
               |Default locale: ${Locale.getDefault()}, platform encoding: ${p("file.encoding", "<unknown encoding>")}
               |OS name: "${p("os.name")}", version: ${p("os.version")}, arch: ${p("os.arch")}""".stripMargin
          )
          (true, None)
        } else {
          val useRepl = config.repl.value || (config.interactive.value && config.leftoverArgs.value.isEmpty)
          val (success, nextStateCache) =
            if (config.repl.value && config.leftoverArgs.value.nonEmpty ) {
              stderr.println("No target may be provided with the --repl flag")
              (false, stateCache)
            } else if ( config.leftoverArgs.value.isEmpty && config.noServer.value) {
              stderr.println("A target must be provided when not starting a build REPL")
              (false, stateCache)
            } else if ( useRepl && stdin == DummyInputStream ) {
              stderr.println("Build REPL needs to be run with the -i/--interactive/--repl flag")
              (false, stateCache)
            }else{
              if ( useRepl && config.interactive.value ) {
                stderr.println("WARNING: Starting a build REPL without --repl is deprecated")
              }
              val systemProps = initialSystemProperties ++ config.extraSystemProperties

              val threadCount = config.threadCountRaw match{
                case None => Some(1)
                case Some(0) => None
                case Some(n) => Some(n)
              }
              val ammConfig = ammonite.main.Config(
                core = config.ammoniteCore,
                predef = ammonite.main.Config.Predef(
                  predefCode =
                    if (!useRepl) ""
                    else s"""import $$file.build, build._
                            |implicit val replApplyHandler = mill.main.ReplApplyHandler(
                            |  os.Path(${pprint.apply(config.ammoniteCore.home.toIO.getCanonicalPath.replace("$", "$$")).plainText}),
                            |  ${config.disableTicker.value},
                            |  interp.colors(),
                            |  repl.pprinter(),
                            |  build.millSelf.get,
                            |  build.millDiscover,
                            |  debugLog = ${config.debugLog.value},
                            |  keepGoing = ${config.keepGoing.value},
                            |  systemProperties = ${systemProps.toSeq.map(p => s""""${p._1}" -> "${p._2}"""").mkString("Map[String,String](", ",", ")")},
                            |  threadCount = ${threadCount}
                            |)
                            |repl.pprinter() = replApplyHandler.pprinter
                            |import replApplyHandler.generatedEval._
                            |""".stripMargin,
                  noHomePredef = Flag()
                ),
                repl = ammonite.main.Config.Repl(
                  banner = "",
                  noRemoteLogging = Flag(),
                  classBased = Flag()
                )
              )



              val runner = new mill.main.MainRunner(
                ammConfig,
                mainInteractive,
                config.disableTicker.value,
                stdout, stderr, stdin,
                stateCache,
                env,
                setIdle,
                debugLog = config.debugLog.value,
                keepGoing = config.keepGoing.value,
                systemProperties = systemProps,
                threadCount = threadCount,
                ringBell = config.ringBell.value,
                wd = os.pwd
              )

              if (mill.main.client.Util.isJava9OrAbove) {
                val rt = config.ammoniteCore.home / Export.rtJarName
                if (!os.exists(rt)) {
                  runner.printInfo(s"Preparing Java ${System.getProperty("java.version")} runtime; this may take a minute or two ...")
                  Export.rtTo(rt.toIO, false)
                }
              }

              if (useRepl) {
                runner.printInfo("Loading...")
                (runner.watchLoop(isRepl = true, printing = false, _.run()), runner.stateCache)
              } else {
                (runner.runScript(os.pwd / "build.sc", config.leftoverArgs.value.toList), runner.stateCache)
              }
            }
          if (config.ringBell.value){
            if (success) println("\u0007")
            else {
              println("\u0007")
              Thread.sleep(250)
              println("\u0007")
            }
          }
          (success, nextStateCache)
        }
    }
  }
}
