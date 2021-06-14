package mill.util

import java.io._
import java.nio.file.{Files, StandardOpenOption}

import scala.util.DynamicVariable

import mill.api.Logger

object DummyLogger extends Logger {
  def colored = false

  object errorStream extends PrintStream(_ => ())
  object outputStream extends PrintStream(_ => ())
  val inStream = new ByteArrayInputStream(Array())

  def info(s: String) = ()
  def error(s: String) = ()
  def ticker(s: String) = ()
  def debug(s: String) = ()
}

class CallbackStream(
  wrapped: OutputStream,
  setPrintState0: PrintState => Unit
) extends OutputStream {

  private[this] var printState: PrintState = _

  private[this] def setPrintState(c: Char) = {
    printState = c match {
      case '\n' => PrintState.Newline
      case '\r' => PrintState.Newline
      case _ => PrintState.Middle
    }
    setPrintState0(printState)
  }

  override def write(b: Array[Byte]): Unit = synchronized{
    if (b.nonEmpty) setPrintState(b(b.length - 1).toChar)
    wrapped.write(b)
  }

  override def write(b: Array[Byte], off: Int, len: Int): Unit = synchronized{
    if (len != 0) setPrintState(b(off + len - 1).toChar)
    wrapped.write(b, off, len)
  }

  override def write(b: Int): Unit = synchronized{
    setPrintState(b.toChar)
    wrapped.write(b)
  }

  override def flush(): Unit = {
    wrapped.flush()
  }
}

sealed trait PrintState

object PrintState {
  case object Ticker extends PrintState
  case object Newline extends PrintState
  case object Middle extends PrintState
}
trait ColorLogger extends Logger{
  def colors: ammonite.util.Colors
}

case class PrefixLogger(out: ColorLogger, context: String, tickerContext: String = "") extends ColorLogger {
  override def colored = out.colored

  def colors = out.colors
  override val errorStream = new PrintStream(new LinePrefixOutputStream(
    colors.info()(context).render, out.errorStream
  ))
  override val outputStream = new PrintStream(new LinePrefixOutputStream(
    colors.info()(context).render, out.outputStream
  ))

  override def inStream = out.inStream

  override def info(s: String): Unit = out.info(context + s)

  override def error(s: String): Unit = out.error(context + s)

  override def ticker(s: String): Unit = out.ticker(context + tickerContext + s)

  override def debug(s: String): Unit = out.debug(context + s)
}

case class PrintLogger(
  colored: Boolean,
  disableTicker: Boolean,
  colors: ammonite.util.Colors,
  outStream: PrintStream,
  infoStream: PrintStream,
  errStream: PrintStream,
  inStream: InputStream,
  debugEnabled: Boolean,
  context: String
) extends ColorLogger {

  var printState: PrintState = PrintState.Newline

  override val errorStream = new PrintStream(
    new CallbackStream(errStream, printState = _)
  )
  override val outputStream = new PrintStream(
    new CallbackStream(outStream, printState = _)
  )

  def info(s: String) = synchronized{
    printState = PrintState.Newline
    infoStream.println(colors.info()(context + s))
  }

  def error(s: String) = synchronized{
    printState = PrintState.Newline
    errStream.println((colors.info()(context) ++ colors.error()(s)).render)
  }

  def ticker(s: String) = synchronized{
    if(!disableTicker) {
      printState match{
        case PrintState.Newline =>
          infoStream.println(colors.info()(s))
        case PrintState.Middle =>
          infoStream.println()
          infoStream.println(colors.info()(s))
        case PrintState.Ticker =>
          val p = new PrintWriter(infoStream)
          // Need to make this more "atomic"
          val nav = new ammonite.terminal.AnsiNav(p)
          nav.up(1)
          nav.clearLine(2)
          nav.left(9999)
          p.flush()

          infoStream.println(colors.info()(s))
      }
      printState = PrintState.Ticker
    }
  }

  def debug(s: String) = synchronized{
    if (debugEnabled) {
      printState = PrintState.Newline
      errStream.println(context + s)
    }
  }
}

class FileLogger(override val colored: Boolean, file: os.Path, debugEnabled: Boolean, append: Boolean = false) extends Logger {
  private[this] var outputStreamUsed: Boolean = false

  lazy val outputStream = {
    val options = Seq(
      Seq(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
      Seq(StandardOpenOption.APPEND).filter(_ => append),
      Seq(StandardOpenOption.TRUNCATE_EXISTING).filter(_ => !append && !outputStreamUsed)
    ).flatten
//    if (!append && !outputStreamUsed) os.remove.all(file)
    outputStreamUsed = true
    os.makeDir.all(file / os.up)
    new PrintStream(Files.newOutputStream(file.toNIO, options: _*))
  }

  lazy val errorStream = outputStream

  def info(s: String) = outputStream.println(s)
  def error(s: String) = outputStream.println(s)
  def ticker(s: String) = outputStream.println(s)
  def debug(s: String) = if (debugEnabled) outputStream.println(s)
  val inStream: InputStream = mill.api.DummyInputStream
  override def close() = {
    if (outputStreamUsed)
      outputStream.close()
  }
}



class MultiStream(stream1: OutputStream, stream2: OutputStream) extends PrintStream(new OutputStream {
  def write(b: Int): Unit = {
    stream1.write(b)
    stream2.write(b)
  }
  override def write(b: Array[Byte]): Unit = {
    stream1.write(b)
    stream2.write(b)
  }
  override def write(b: Array[Byte], off: Int, len: Int) = {
    stream1.write(b, off, len)
    stream2.write(b, off, len)
  }
  override def flush() = {
    stream1.flush()
    stream2.flush()
  }
  override def close() = {
    stream1.close()
    stream2.close()
  }
})

case class MultiLogger(colored: Boolean, logger1: Logger, logger2: Logger, inStream: InputStream) extends Logger {


  lazy val outputStream: PrintStream = new MultiStream(logger1.outputStream, logger2.outputStream)

  lazy val errorStream: PrintStream = new MultiStream(logger1.errorStream, logger2.errorStream)

  def info(s: String) = {
    logger1.info(s)
    logger2.info(s)
  }
  def error(s: String) = {
    logger1.error(s)
    logger2.error(s)
  }
  def ticker(s: String) = {
    logger1.ticker(s)
    logger2.ticker(s)
  }

  def debug(s: String) = {
    logger1.debug(s)
    logger2.debug(s)
  }

  override def close() = {
    logger1.close()
    logger2.close()
  }
}

/**
  * A Logger that forwards all logging to another Logger.  Intended to be
  * used as a base class for wrappers that modify logging behavior.
  */
class ProxyLogger(logger: Logger) extends Logger {
  def colored = logger.colored

  lazy val outputStream = logger.outputStream
  lazy val errorStream = logger.errorStream
  lazy val inStream = logger.inStream

  def info(s: String) = logger.info(s)
  def error(s: String) = logger.error(s)
  def ticker(s: String) = logger.ticker(s)
  def debug(s: String) = logger.debug(s)

  override def close() = logger.close()
}
