package net.atinu.akka.defender

import scala.concurrent.Future

trait DefaultCommandNaming[T] extends NamedCommand[T] {
  // TODO: cache cmdKeys
  override val cmdKey = DefendCommandKey(buildNameFromClass)

  private def buildNameFromClass =
    this.getClass.getSimpleName
}

object DefendCommand {

  def apply[T](key: String, exec: => Future[T]): DefendExecution[T] = new DefendExecution[T] {
    def cmdKey = key.asKey

    def execute = exec
  }

  def applyWithStaticFallback[T](key: String, exec: => Future[T], fb: => T): DefendExecution[T] with StaticFallback[T] =
    new DefendExecution[T] with StaticFallback[T] {
      def cmdKey = key.asKey

      def execute = exec

      def fallback = fb
    }

  def applyWithCmdFallback[T](key: String, exec: => Future[T], fb: => NamedCommand[T]): DefendExecution[T] with CmdFallback[T] =
    new DefendExecution[T] with CmdFallback[T] {
      def cmdKey = key.asKey

      def execute = exec

      def fallback = fb
    }
}

abstract class DefendCommand[T] extends DefendExecution[T] with DefaultCommandNaming[T] {


}

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

  def applyWithCmdFallback[T](key: String, exec: => T, fb: => NamedCommand[T]): SyncDefendExecution[T] with CmdFallback[T] =
    new SyncDefendExecution[T] with CmdFallback[T] {
      def cmdKey = key.asKey

      def execute = exec

      def fallback = fb
    }

}

abstract class SyncDefendCommand[T] extends SyncDefendExecution[T] with DefaultCommandNaming[T] {

}
