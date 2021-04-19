package hypermake.semantics

import java.io.{File => JFile}
import scala.collection._
import better.files.File
import zio._
import zio.process._
import hypermake.core._
import hypermake.util._

/**
 * Encapsulates a running environment that could be local, or some remote grid.
 * Such an environment must possess a basic file system, as well as the capability
 * to run arbitrary shell script.
 */
trait Env {

  /**
   * Identifier of this environment.
   */
  def name: String

  /**
   * Separator character used to separate path components. By default this is '/'.
   */
  def separator: Char

  /**
   * Separator character used to separate a list of paths. By default this is ':'.
   */
  def pathSeparator: Char

  /**
   * Output root on this environment. Intermediate results will be stored in this directory.
   */
  def root: String

  /**
   * Resolves a path relative to the given root directory.
   */
  def resolvePath(s: String, r: String = root) = {
    val p = s.trim
    if (p startsWith separator.toString) p
    else r + separator + p
  }

  /**
   * Reads the content of a file as a string.
   */
  def read(f: String): HIO[String]

  def write(f: String, content: String): HIO[Unit]

  def mkdir(f: String): HIO[Unit]

  /**
   * Checks if a file exists on this file system.
   */
  def exists(f: String): HIO[Boolean]

  /**
   * Creates a symbolic link from [[src]] to [[dst]].
   */
  def link(src: String, dst: String): HIO[Unit]

  /**
   * Creates an empty file at the given path.
   */
  def touch(f: String): HIO[Unit]

  def delete(f: String): HIO[Unit]
  def copyFrom(src: String, srcEnv: Env, dst: String): HIO[Unit]
  def execute(wd: String, command: String, args: Seq[String]): HIO[ExitCode]

  override def toString = name

  override def equals(o: Any) = o match {
    case o: Env => name == o.name
    case _ => false
  }

}

object Env {

  def getValueByName(name: String)(implicit ctx: ParsingContext) = ctx.getValue(Name(name)).default.value
  def getScriptByName(name: String)(implicit ctx: ParsingContext) = ctx.getFunc(Name(name)).script

  def apply(name: Name)(implicit ctx: ParsingContext) = {
    if (name.name == "local")
      ctx.localEnv
    else if (ctx.envTable contains name)
      ctx.getEnv(name)
    else {
      val env = new Env.Custom(name.name)
      ctx.envTable += name -> env
      env
    }
  }

  class Local(implicit ctx: ParsingContext) extends Env {

    import ctx._

    final def name = "local"

    def separator = java.io.File.separatorChar

    def pathSeparator = java.io.File.pathSeparatorChar

    def root = ctx.envOutputRoot(Name("local"))

    def read(f: String) = IO {
      File(f).contentAsString
    }

    def write(f: String, content: String) = IO {
      File(f).writeText(content)
    }

    def mkdir(f: String) = IO {
      File(f).createDirectoryIfNotExists(createParents = true)
    }

    def exists(f: String) = IO {
      File(f).exists
    }

    def touch(f: String) = IO {
      File(f).touch()
    }

    def delete(f: String) = IO {
      File(f).delete()
    }

    def link(src: String, dst: String) = IO {
      File(src).linkTo(File(dst), symbolic = true)
    }

    override def copyFrom(src: String, srcEnv: Env, dst: String) = for {
      process: Process <- getScriptByName(s"copy_from_${srcEnv}_to_local").withArgs("src" -> src, "dst" -> dst).executeUnmanaged()
      u <- process.successfulExitCode.unit
    } yield u

    override def execute(wd: String, command: String, args: Seq[String]) = for {
      process <- zio.process.Command(command, args.toList: _ *)
        .workingDirectory( new JFile(wd))
        .stdout(ProcessOutput.FileRedirect( new JFile(s"$wd/stdout")))
        .stderr(ProcessOutput.FileRedirect( new JFile(s"$wd/stderr"))).run
      exitCode <- process.exitCode
      _ <- write(s"$wd/exitcode", exitCode.code.toString)
    } yield exitCode

  }

  class Custom(val name: String)(implicit ctx: ParsingContext) extends Env {

    import ctx._

    def separator = java.io.File.separatorChar

    def pathSeparator = java.io.File.pathSeparatorChar

    def root = getValueByName(s"${name}_root")

    def read(f: String) = for {
      process <- getScriptByName(s"${name}_read").withArgs("file" -> f).executeUnmanaged()
      stdout <- process.stdout.string
    } yield stdout

    def write(f: String, content: String) = {
      val tempScriptFile = runtime.tempFile()
      for {
        _ <- IO {
          File(tempScriptFile).write(content)
        }
        u <- copyFrom(tempScriptFile, ctx.localEnv, f)
      } yield u
    }

    def mkdir(f: String) = for {
      process <- getScriptByName(s"${name}_mkdir").withArgs("dir" -> f).executeUnmanaged()
      u <- process.successfulExitCode.unit
    } yield u

    def exists(f: String) = for {
      process <- getScriptByName(s"${name}_exists").withArgs("file" -> f).executeUnmanaged()
      exitCode <- process.exitCode
    } yield exitCode.code == 0

    def link(src: String, dst: String) = for {
      process <- getScriptByName(s"${name}_link").withArgs("src" -> src, "dst" -> dst).executeUnmanaged()
      u <- process.successfulExitCode.unit
    } yield u

    def touch(f: String) = for {
      process <- getScriptByName(s"${name}_touch").withArgs("file" -> f).executeUnmanaged()
      u <- process.successfulExitCode.unit
    } yield u

    def delete(f: String) = for {
      process <- getScriptByName(s"${name}_delete").withArgs("file" -> f).executeUnmanaged()
      u <- process.successfulExitCode.unit
    } yield u

    def copyFrom(src: String, srcEnv: Env, dst: String) = for {
      process <- getScriptByName(s"copy_from_${srcEnv}_to_$name")
        .withArgs("src" -> src, "dst" -> dst).executeUnmanaged()
      u <- process.successfulExitCode.unit
    } yield u

    def execute(wd: String, command: String, args: Seq[String]) = for {
      process <- getScriptByName(s"${name}_execute").withArgs("command" -> s"$command ${args.mkString(" ")}").executeUnmanaged(wd)
      exitCode <- process.exitCode
    } yield exitCode

  }
}