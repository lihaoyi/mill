package mill.modules

import java.io.{InputStream, PrintStream}

object InitProject {
  val initBuildSc = """import mill._, scalalib._
                      |
                      |object foo extends ScalaModule {
                      |  def scalaVersion = "3.0.0-RC3"
                      |}""".stripMargin

  val initAppScala = """@main def app =
                        |  println("Hello world!")""".stripMargin

  def initialize(args: List[String], stdin: InputStream, stdout: PrintStream, stderr: PrintStream) = {
    stdout.println("Initializing new project...")
    os.write(os.pwd / "build.sc", initBuildSc)
    os.write(os.pwd / "foo" / "src" / "app.scala", initAppScala, createFolders = true)
  }
}
