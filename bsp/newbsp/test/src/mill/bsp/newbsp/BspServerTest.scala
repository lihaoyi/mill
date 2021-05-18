package mill.bsp.newbsp

import mill.bsp.newbsp.BspServer.BspServerExit
import org.scalatest.freespec.AnyFreeSpec

class BspServerTest() extends AnyFreeSpec {

  private[this] object log {
    val show =
      Seq("1", "on", "true").contains(sys.env.getOrElse("TEST_DEBUG", "false"))
    def debug(msg: String) = if (show) println(msg)
  }

  "The BspServer" - {

    "should support --help option" in {
      assert(intercept[BspServerExit] {
        BspServer.run(Array("--help"))
      }.code === 0)
    }

    "should generate a connection JSON file with --install option" in {
      val tmpDir = os.temp.dir(deleteOnExit = false)
      assert(!os.exists(tmpDir / ".bsp" / "mill.json"))
      val args = Seq("--dir", tmpDir.toString(), "--install")
      log.debug(s"args: ${args}")
      val ex = intercept[BspServerExit] {
        BspServer.run(args)
      }
      assert(ex.code === 0)
      assert(
        os.exists(tmpDir / ".bsp" / "mill.json"),
        "Expected generated .bsp/mill.json file"
      )
      // we only cleanup in case of success, makes debugging easier
      os.remove.all(tmpDir)
    }
  }

}
