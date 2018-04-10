package mill.main

import ammonite.ops._
import mill.util.ScriptTestSuite
import utest._

object ForeignBuildsTest extends ScriptTestSuite(fork = false) {
  def workspaceSlug = "foreign-builds"
  def scriptSourcePath =
    pwd / 'main / 'test / 'resources / 'examples / 'foreign
  override def buildPath = 'project / "build.sc"

  val tests = Tests {
    initWorkspace()
    'test - {
      assert(
        eval("checkProjectPaths"),
        eval("checkInnerPaths"),
        eval("checkOuterPaths"),
        eval("checkOuterInnerPaths"),
        eval("checkProjectDests"),
        eval("checkInnerDests"),
        eval("checkOuterDests"),
        eval("checkOuterInnerDests")
      )
    }
  }
}
