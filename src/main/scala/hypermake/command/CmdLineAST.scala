package hypermake.command

import hypermake.syntax._

object CmdLineAST {

  sealed abstract class Cmd
  object Cmd {
    case object Help                 extends Cmd // H
    case object Version              extends Cmd // V
    case class Run(options: Set[Opt], script: String, runOptions: Set[RunOpt], task: Subtask) extends Cmd
  }

  sealed abstract class Opt
  object Opt {
    case class Include(path: String) extends Opt // I
    case class Output(path: String)  extends Opt // O
    case class Shell(shell: String)  extends Opt // S
  }

  sealed abstract class RunOpt
  object RunOpt {
    case class NumJobs(numJobs: Int) extends RunOpt // j
    case object KeepGoing            extends RunOpt // k
    case object DryRun               extends RunOpt // n
    case object Silent               extends RunOpt // s
    case object Verbose              extends RunOpt // v
    case object Yes                  extends RunOpt // y
  }

  sealed trait Subtask
  object Subtask {

    case class Run(tasks: Seq[TaskRefN]) extends Subtask
    case class Invalidate(tasks: Seq[TaskRefN]) extends Subtask
    case class Remove(tasks: Seq[TaskRefN]) extends Subtask
    case class MarkAsDone(tasks: Seq[TaskRefN]) extends Subtask
    case class ExportShell(tasks: Seq[TaskRefN]) extends Subtask

  }

}
