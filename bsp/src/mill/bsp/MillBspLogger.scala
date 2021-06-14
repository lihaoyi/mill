package mill.bsp

import ammonite.util.Colors
import ch.epfl.scala.bsp4j._
import java.io.{ ByteArrayOutputStream, InputStream, PrintStream }
import java.nio.charset.Charset
import mill.bsp.MillBspLogger.tickerPattern
import mill.util.ColorLogger

object MillBspLogger {
  /**
   * Creates a BSP-specialized logger class which sends `task-progress`
   * notifications ( upon the invocation of the `ticker` method ) and
   * `show-message` notifications ( for each error or information
   * being logged ).
   *
   * @param client the client to send notifications to, also the
   *               client that initiated a request which triggered
   *               a mill task evaluation
   * @param taskId unique ID of the task being evaluated
   * @param inputStream The input stream to read input from
   */
  def createBspLogger(client: BuildClient, taskId: Int, inputStream: InputStream): ColorLogger = {
    val bspErrorStream = new MillBspClientPrintStream(client, MessageType.INFORMATION, new ByteArrayOutputStream())
    val bspOutputStream = new MillBspClientPrintStream(client, MessageType.INFORMATION, new ByteArrayOutputStream())
    new MillBspLogger(client, taskId, inputStream, bspOutputStream, bspErrorStream)
  }

  private val tickerPattern = """\[(?<progress>\d+)/(?<total>\d+)] (?<unit>.\S+)\s+$""".r
}

private class MillBspLogger(
                      client: BuildClient,
                      taskId: Int,
                      inputStream: InputStream,
                      outStream: PrintStream,
                      errStream: PrintStream) extends ColorLogger {

  override def colored: Boolean = false

  override def colors: Colors = Colors.BlackWhite

  override def errorStream: PrintStream = errStream

  override def outputStream: PrintStream = outStream

  override def inStream: InputStream = inputStream

  override def info(s: String): Unit = {
    client.onBuildShowMessage(new ShowMessageParams(MessageType.INFORMATION, s))
  }

  override def error(s: String): Unit = {
    client.onBuildShowMessage(new ShowMessageParams(MessageType.ERROR, s))
  }

  override def ticker(s: String): Unit = {
    try {
      client.onBuildShowMessage(new ShowMessageParams(MessageType.INFORMATION, s))
      tickerPattern.findFirstMatchIn(s).foreach { ticker =>
        val params = new TaskProgressParams(new TaskId(taskId.toString))
        params.setEventTime(System.currentTimeMillis())
        params.setMessage(s)
        params.setUnit(ticker.group("unit"))
        params.setProgress(ticker.group("progress").toLong)
        params.setTotal(ticker.group("total").toLong)
        client.onBuildTaskProgress(params)
      }
    } catch {
      case e: Exception =>
    }
  }

  override def debug(s: String): Unit = {
    client.onBuildShowMessage(new ShowMessageParams(MessageType.LOG, s))
  }
}

class MillBspClientPrintStream(client: BuildClient, messageType: MessageType, val byteOutput: ByteArrayOutputStream) extends PrintStream(byteOutput, true, Charset.defaultCharset().name()) {
  override def flush(): Unit = synchronized {
    super.flush()
    val message = byteOutput.toString(Charset.defaultCharset().name())
    byteOutput.reset()
    client.onBuildShowMessage(new ShowMessageParams(messageType, message))
  }
}
