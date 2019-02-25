package mill
package testng

import ammonite.ops.{Path, cp, mkdir, pwd, rm, _}
import mill.api.Result.Exception
import mill.define.Target
import mill.scalalib._
import mill.util.{TestEvaluator, TestUtil}
import utest.framework.TestPath
import utest.{TestSuite, Tests, assert, _}

object TestNGTests extends TestSuite {

  object demo extends TestUtil.BaseModule with JavaModule {
    override def millSourcePath: os.Path =
      TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')

    object test extends super.Tests {
      def testngClasspath = T{
        mill.modules.Util.millProjectModule("MILL_TESTNG_LIB", "mill-contrib-testng", repositories)
      }

      override def runClasspath: Target[Seq[PathRef]] = T{super.runClasspath() ++ testngClasspath()}
      override def ivyDeps = T{
        super.ivyDeps() ++
          Agg(ivy"org.testng:testng:6.11",
          )
      }
      def testFrameworks = T {
        Seq("mill.testng.TestNGFramework")
      }
    }

  }

  val resourcePath: Path = pwd / 'contrib / 'testng / 'test / 'resources / "demo"

  def workspaceTest[T, M <: TestUtil.BaseModule](m: M, resourcePath: Path = resourcePath)
                                                (t: TestEvaluator => T)
                                                (implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m)
    rm(m.millSourcePath)
    rm(eval.outPath)
    mkdir(m.millSourcePath / up)
    cp(resourcePath, m.millSourcePath)
    t(eval)
  }

  def tests: Tests = Tests {
    'TestNG - {
      'demo - workspaceTest(demo) { eval =>
        val Right((result, evalCount)) = eval.apply(demo.test.testFrameworks)
        assert(
          result == Seq("mill.testng.TestNGFramework"),
          evalCount > 0
        )
        val evalRes = eval.apply(demo.test.test())
        evalRes match {
          case Left(Exception(ex, outerStack)) =>
            println(outerStack.value.mkString("\t", "\n\t\t", ""))
            println(ex.getMessage)
            ex.printStackTrace()
          case _ => ()
        }
      }
    }
  }
}