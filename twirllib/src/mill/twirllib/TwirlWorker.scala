package mill
package twirllib

import java.io.File
import java.net.URLClassLoader
import java.util

import ammonite.ops.{Path, ls}
import mill.eval.PathRef
import mill.scalalib.CompilationResult

import scala.io.Codec
import scala.reflect.runtime.universe._
import scala.util.Properties

class TwirlWorker {

  private var twirlInstanceCache = Option.empty[(Long, TwirlWorkerApi)]
  import scala.collection.JavaConverters._

  private def twirl(twirlClasspath: Agg[Path]) = {
    val classloaderSig = twirlClasspath.map(p => p.toString().hashCode + p.mtime.toMillis).sum
    twirlInstanceCache match {
      case Some((sig, instance)) if sig == classloaderSig => instance
      case _ =>
        //callScalaAPI(twirlClasspath, classloaderSig)
        callJavaAPI(twirlClasspath, classloaderSig)
    }
  }

  private def callJavaAPI(twirlClasspath: Agg[Path], classloaderSig: Long) = {
    val cl = new URLClassLoader(twirlClasspath.map(_.toIO.toURI.toURL).toArray)
    val twirlCompilerClass = cl.loadClass("play.japi.twirl.compiler.TwirlCompiler")
    // Use the Java API (available in Twirl 1.3+)
    // Using reflection on a method with "Seq[String] = Nil" parameter type does not seem to work.

    // REMIND: Unable to call the compile method with a primitive boolean
    // codec and inclusiveDot will not be available
    val compileMethod = twirlCompilerClass.getMethod("compile",
      classOf[File],
      classOf[File],
      classOf[File],
      classOf[String],
      classOf[util.Collection[String]],
      classOf[util.List[String]])

    val instance = new TwirlWorkerApi {
      override def compileTwirl(source: File,
                                sourceDirectory: File,
                                generatedDirectory: File,
                                formatterType: String,
                                additionalImports: Seq[String] = Nil,
                                constructorAnnotations: Seq[String] = Nil,
                                codec: Codec = Codec(Properties.sourceEncoding),
                                inclusiveDot: Boolean = false) {
        val o = compileMethod.invoke(null, source,
          sourceDirectory,
          generatedDirectory,
          formatterType,
          additionalImports.asJava,
          constructorAnnotations.asJava)
      }
    }
    twirlInstanceCache = Some((classloaderSig, instance))
    instance
  }

  private def callScalaAPI(twirlClasspath: Agg[Path], classloaderSig: Long) = {
    val cl = new URLClassLoader(twirlClasspath.map(_.toIO.toURI.toURL).toArray)
    val runtime = runtimeMirror(cl)
    val moduleMirror = runtime.reflectModule(runtime.staticModule("play.twirl.compiler.TwirlCompiler"))
    val moduleInstance = moduleMirror.instance
    val instanceMirror = runtime.reflect(moduleInstance)
    val compileSymbol = instanceMirror.symbol.typeSignature.member(TermName("compile")).asMethod
    // FIXME: type erasure, unable to call "play.twirl.compiler.TwirlCompiler.compile" function
    // https://github.com/playframework/twirl/blob/dd56444cf9ea2f95ef3961d3af911561f846df50/compiler/src/main/scala/play/twirl/compiler/TwirlCompiler.scala#L167
    val instance = new TwirlWorkerApi {
      override def compileTwirl(source: File,
                                sourceDirectory: File,
                                generatedDirectory: File,
                                formatterType: String,
                                additionalImports: Seq[String] = Nil,
                                constructorAnnotations: Seq[String] = Nil,
                                codec: Codec = Codec(Properties.sourceEncoding),
                                inclusiveDot: Boolean = false) {

        // scala.MatchError: List() (of class scala.collection.immutable.Nil$)
        // at scala.reflect.runtime.JavaMirrors$JavaMirror$DerivedValueClassMetadata.unboxer$lzycompute(JavaMirrors.scala:270)
        // at scala.reflect.runtime.JavaMirrors$JavaMirror$DerivedValueClassMetadata.unboxer(JavaMirrors.scala:269)
        // at scala.reflect.runtime.JavaMirrors$JavaMirror$MethodMetadata.paramUnboxers(JavaMirrors.scala:416)
        // at scala.reflect.runtime.JavaMirrors$JavaMirror$JavaTransformingMethodMirror.apply(JavaMirrors.scala:435)
        instanceMirror.reflectMethod(compileSymbol)(source,
          sourceDirectory,
          generatedDirectory,
          formatterType,
          additionalImports,
          constructorAnnotations,
          codec,
          inclusiveDot)
      }
    }
    twirlInstanceCache = Some((classloaderSig, instance))
    instance
  }

  def compile(twirlClasspath: Agg[Path], sourceDirectories: Seq[Path], dest: Path)
             (implicit ctx: mill.util.Ctx): mill.eval.Result[CompilationResult] = {
    val compiler = twirl(twirlClasspath)

    def compileTwirlDir(inputDir: Path) {
      ls.rec(inputDir).filter(_.name.matches(".*.scala.(html|xml|js|txt)"))
        .foreach { template =>
          val extFormat = twirlExtensionFormat(template.name)
          compiler.compileTwirl(template.toIO,
            inputDir.toIO,
            dest.toIO,
            s"play.twirl.api.$extFormat"
          )
        }
    }

    sourceDirectories.foreach(compileTwirlDir)

    val zincFile = ctx.dest / 'zinc
    val classesDir = ctx.dest / 'html

    mill.eval.Result.Success(CompilationResult(zincFile, PathRef(classesDir)))
  }

  private def twirlExtensionFormat(name: String) =
    if (name.endsWith("html")) "HtmlFormat"
    else if (name.endsWith("xml")) "XmlFormat"
    else if (name.endsWith("js")) "JavaScriptFormat"
    else "TxtFormat"
}

trait TwirlWorkerApi {
  def compileTwirl(source: File,
                   sourceDirectory: File,
                   generatedDirectory: File,
                   formatterType: String,
                   additionalImports: Seq[String] = Nil,
                   constructorAnnotations: Seq[String] = Nil,
                   codec: Codec = Codec(Properties.sourceEncoding),
                   inclusiveDot: Boolean = false)
}

object TwirlWorkerApi {

  def twirlWorker = new TwirlWorker()
}
