package hypermake.core

import scala.collection._
import scala.collection.decorators._
import zio._
import hypermake.cli.CLI
import hypermake.collection._
import hypermake.execution._
import hypermake.semantics.SymbolTable
import hypermake.util._
import hypermake.util.Escaper._

/**
 * A job is any block of shell script that is executed by HyperMake.
 * A job can either be a task, a package, or a service.
 * @param ctx Parsing context that yielded this job
 */
abstract class Job(implicit ctx: SymbolTable) {

  import ctx._
  implicit val runtime: RuntimeContext = ctx.runtime

  def name: Name
  def env: Env

  def `case`: Case
  def inputs: Map[Name, Value]
  def inputEnvs: Map[Name, Env]
  def outputFileNames: Map[Name, Value]

  def outputs: Map[Name, Value.Output] = outputFileNames.map { case (k, v) =>
    k -> Value.Output(v.value, outputEnvs.getOrElse(k, env), this)
  }
  def outputEnvs: Map[Name, Env]

  def decorators: Seq[Call]
  def rawScript: Script

  /**
   * Path to store the output of this task, relative to the output root.
   * This is the working directory of this task if executed.
   */
  lazy val path = s"$name/${escapedArgsString(`case`.underlying)}"

  lazy val absolutePath = env.resolvePath(path)

  /** The canonical string identifier for this task. */
  lazy val id = s"$name[$argsString]"

  /** Set of dependent jobs. */
  lazy val dependentJobs: Set[Job] =
    inputs.values.map(_.dependencies).fold(Set())(_ union _)

  lazy val script: Script = Script(rawScript.script, rawScript.args ++ inputs, rawScript.outputArgs ++ outputs)(runtime)

  lazy val outputAbsolutePaths =
    outputs.keySet.makeMap { x => env.resolvePath(outputs(x).value, absolutePath) }

  /** Checks if this job is complete, i.e. job itself properly terminated and all its outputs existed. */
  def isDone: HIO[Boolean] = for {
    exitCode <- env.read(env.resolvePath("exitcode", absolutePath)).map(_.toInt).catchAll(_ => IO.succeed(-1))
    outputsExist <- checkOutputs
  } yield exitCode == 0 && outputsExist

  /** An operation that links output of dependent jobs to the working directory of this job. */
  def linkInputs: HIO[Map[String, Option[String]]] = ZIO.collectAllPar {
    for ((Name(name), (input, inputEnv)) <- inputs zipByKey inputEnvs)
      yield inputEnv.linkValue(input, inputEnv.resolvePath(name, absolutePath)).map(name -> _)
  }.map(_.toMap)

  /** An operation that checks the output of this job and the exit status of this job. */
  def checkOutputs: HIO[Boolean] = {
    ZIO.collectAll {
      for ((_, (outputPath, outputEnv)) <- outputAbsolutePaths zipByKey outputEnvs)
        yield outputEnv.exists(outputPath)
    }.map(_.forall(identity)).catchAll(_ => IO.succeed(false))
  }

  /** Writes the script and decorating calls to the working directory. */
  def writeScript(linkedArgs: Map[String, Option[String]]): HIO[Map[String, String]] = {

    def mergeArgs(args: Map[String, String], linkedArgs: Map[String, Option[String]]) = {
      args ++ linkedArgs.collect { case (k, Some(v)) => k -> v }
    }

    val scriptNames = decorators.map(_.inputScriptFilename)
    for {
      finalScript <- ZIO.foldLeft(decorators zip scriptNames)(script) { case (scr, (c, name)) =>
        env.write(absolutePath / name, scr.script) as c(scr)
      }  // wraps the script with decorator calls sequentially
      _ <- env.write(absolutePath / "script.sh", finalScript.script)
      mergedArgs = mergeArgs(finalScript.strArgs, linkedArgs)
      _ <- env.write(absolutePath / "args", mergedArgs.map { case (k, v) => s"""$k="${C.escape(v)}""""}.mkString("\n")
      )
    } yield mergedArgs
  }

  def execute(cli: CLI.Service): HIO[Boolean] = {


    val effect = for {
      _ <- env.mkdir(absolutePath)
      _ <- cli.update(this, Status.Waiting)
      _ <- env.lock(absolutePath)
      linkedArgs <- linkInputs
      args <- writeScript(linkedArgs)
      _ <- cli.update(this, Status.Running)
      (outSink, errSink) <- cli.getSinks(this)
      exitCode <- env.execute(absolutePath, runtime.SHELL, Seq("script.sh"), args, outSink, errSink)
      hasOutputs <- checkOutputs
    } yield (exitCode.code == 0) && hasOutputs
    effect.ensuring(env.unlock(absolutePath).orElseSucceed())
  }

  def executeIfNotDone(cli: CLI.Service): HIO[(Boolean, Boolean)] = for {
    done <- isDone
    (hasRun, successful) <- if (done) cli.update(this, Status.Complete) as (false, true)
      else execute(cli).map((true, _))
  } yield (hasRun, successful)

  def markAsDone(cli: CLI.Service): HIO[Boolean] = {
    val effect = for {
      _ <- env.mkdir(absolutePath)
      _ <- cli.update(this, Status.Waiting)
      _ <- env.lock(absolutePath)
      _ <- cli.update(this, Status.Running)
      _ <- ZIO.foreach_(outputAbsolutePaths zipByKey outputEnvs) { case (_, (p, e)) => e.touch(p) }
      _ <- env.write(absolutePath / "exitcode", "0")
      hasOutputs <- checkOutputs
    } yield hasOutputs
    effect.ensuring(env.unlock(absolutePath).orElseSucceed())
  }

  def forceUnlock: HIO[Unit] =
    env.forceUnlock(absolutePath)

  def removeOutputs: HIO[Unit] =
    env.delete(absolutePath)

  def argsDefault = ctx.argsDefault(`case`.underlying)

  def argsString = ctx.argsString(`case`.underlying)

  def colorfulString = {
    import fansi._
    s"${Bold.On(Color.LightBlue(name.name)).render}[${argsStringDefault(`case`.underlying)}]"
  }

  override def toString = id
  override lazy val hashCode = id.hashCode

  override def equals(o: Any) = o match {
    case that: Job => this.id == that.id
    case _ => false
  }

}
