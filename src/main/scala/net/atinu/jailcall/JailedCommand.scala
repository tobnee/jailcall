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

object JailedCommand {

  def apply[T](key: String, exec: => Future[T]): AsyncJailedExecution[T] = new AsyncJailedExecution[T] {
    def cmdKey = key.asKey

    def execute = exec
  }

  def applyWithStaticFallback[T](key: String, exec: => Future[T], fb: => T): AsyncJailedExecution[T] with StaticFallback[T] =
    new AsyncJailedExecution[T] with StaticFallback[T] {
      def cmdKey = key.asKey

      def execute = exec

      def fallback = fb
    }

  def applyWithCmdFallback[T](key: String, exec: => Future[T], fb: => JailedExecution[T, _]): AsyncJailedExecution[T] with CmdFallback[T] =
    new AsyncJailedExecution[T] with CmdFallback[T] {
      def cmdKey = key.asKey

      def execute = exec

      def fallback = fb
    }
}

abstract class JailedCommand[T] extends AsyncJailedExecution[T] with DefaultCommandNaming

object SyncJailedCommand {

  def apply[T](key: String, exec: => T): SyncJailedExecution[T] = new SyncJailedExecution[T] {
    def cmdKey = key.asKey

    def execute = exec
  }

  def applyWithStaticFallback[T](key: String, exec: => T, fb: => T): SyncJailedExecution[T] with StaticFallback[T] =
    new SyncJailedExecution[T] with StaticFallback[T] {
      def cmdKey = key.asKey

      def execute = exec

      def fallback = fb
    }

  def applyWithCmdFallback[T](key: String, exec: => T, fb: => JailedExecution[T, _]): SyncJailedExecution[T] with CmdFallback[T] =
    new SyncJailedExecution[T] with CmdFallback[T] {
      def cmdKey = key.asKey

      def execute = exec

      def fallback = fb
    }

}

abstract class SyncJailedCommand[T] extends SyncJailedExecution[T] with DefaultCommandNaming
