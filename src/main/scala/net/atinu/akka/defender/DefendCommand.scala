package net.atinu.akka.defender

import scala.concurrent.Future
import scala.util.control.NonFatal

trait DefaultCommandNaming extends NamedCommand {
  override val cmdKey = DefendCommandKey(buildNameFromClass)

  private def buildNameFromClass = try {
    this.getClass.getSimpleName
  } catch {
    case NonFatal(_) => "anonymous-cmd"
  }
}

object DefendCommand {

  def apply[T](key: String, exec: => Future[T]): AsyncDefendExecution[T] = new AsyncDefendExecution[T] {
    def cmdKey = key.asKey

    def execute = exec
  }

  def applyWithStaticFallback[T](key: String, exec: => Future[T], fb: => T): AsyncDefendExecution[T] with StaticFallback[T] =
    new AsyncDefendExecution[T] with StaticFallback[T] {
      def cmdKey = key.asKey

      def execute = exec

      def fallback = fb
    }

  def applyWithCmdFallback[T](key: String, exec: => Future[T], fb: => DefendExecution[T, _]): AsyncDefendExecution[T] with CmdFallback[T] =
    new AsyncDefendExecution[T] with CmdFallback[T] {
      def cmdKey = key.asKey

      def execute = exec

      def fallback = fb
    }
}

abstract class DefendCommand[T] extends AsyncDefendExecution[T] with DefaultCommandNaming

object SyncDefendCommand {

  def apply[T](key: String, exec: => T): SyncDefendExecution[T] = new SyncDefendExecution[T] {
    def cmdKey = key.asKey

    def execute = exec
  }

  def applyWithStaticFallback[T](key: String, exec: => T, fb: => T): SyncDefendExecution[T] with StaticFallback[T] =
    new SyncDefendExecution[T] with StaticFallback[T] {
      def cmdKey = key.asKey

      def execute = exec

      def fallback = fb
    }

  def applyWithCmdFallback[T](key: String, exec: => T, fb: => DefendExecution[T, _]): SyncDefendExecution[T] with CmdFallback[T] =
    new SyncDefendExecution[T] with CmdFallback[T] {
      def cmdKey = key.asKey

      def execute = exec

      def fallback = fb
    }

}

abstract class SyncDefendCommand[T] extends SyncDefendExecution[T] with DefaultCommandNaming
