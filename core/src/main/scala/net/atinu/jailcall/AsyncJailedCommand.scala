package net.atinu.jailcall

import scala.concurrent.Future
import scala.util.control.NonFatal

trait DefaultCommandNaming extends NamedCommand {
  override val cmdKey = CommandKey(buildNameFromClass)

  private def buildNameFromClass = try {
    this.getClass.getSimpleName
  } catch {
    case NonFatal(_) => "anonymous-cmd"
  }
}

object AsyncJailedCommand {

  def apply[T](key: String, exec: => Future[T]): AsyncJailedExecution[T] = new AsyncJailedExecution[T] {
    def cmdKey = key.asKey

    def execute = exec
  }

  def withCmdFallback[T](key: String, exec: => Future[T], fb: => JailedExecution[T]): AsyncJailedExecution[T] with CmdFallback =
    new AsyncJailedExecution[T] with CmdFallback {
      def cmdKey = key.asKey

      def execute = exec

      def fallback = fb
    }
}

abstract class AsyncJailedCommand[T] extends AsyncJailedExecution[T] with DefaultCommandNaming

object SyncJailedCommand {

  def apply[T](key: String, exec: => T): SyncJailedExecution[T] = new SyncJailedExecution[T] {
    def cmdKey = key.asKey

    def execute = exec
  }

  def withCmdFallback[T](key: String, exec: => T, fb: => JailedExecution[T]): SyncJailedExecution[T] with CmdFallback =
    new SyncJailedExecution[T] with CmdFallback {
      def cmdKey = key.asKey

      def execute = exec

      def fallback = fb
    }

}

abstract class SyncJailedCommand[T] extends SyncJailedExecution[T] with DefaultCommandNaming
