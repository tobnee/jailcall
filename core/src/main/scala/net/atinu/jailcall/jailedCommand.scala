package net.atinu.jailcall

import scala.concurrent.Future
import scala.util.control.NonFatal

trait DefaultCommandNaming extends NamedCommand {
  override def cmdKey = CommandKey(buildNameFromClass)

  private val defaultName = "anonymous-cmd"

  private def buildNameFromClass = try {
    this.getClass.getSimpleName
  } catch {
    case NonFatal(_) => defaultName
    case e: InternalError => defaultName
  }
}

object ScalaFutureCommand {

  def apply[T](key: String, exec: => Future[T]): ScalaFutureExecution[T] = new ScalaFutureExecution[T] {
    def cmdKey = key.asKey

    def execute = exec
  }

  def withCmdFallback[T](key: String, exec: => Future[T], fb: => JailedExecution[T, _]): ScalaFutureExecution[T] with CmdFallback =
    new ScalaFutureExecution[T] with CmdFallback {
      def cmdKey = key.asKey

      def execute = exec

      def fallback = fb
    }
}

/**
 * A default [[ScalaFutureExecution]] which infers the [[CommandKey]] based on the name of the class.
 */
abstract class ScalaFutureCommand[T] extends ScalaFutureExecution[T] with DefaultCommandNaming

object BlockingCommand {

  def apply[T](key: String, exec: => T): BlockingExecution[T] = new BlockingExecution[T] {
    def cmdKey = key.asKey

    def execute = exec
  }

  def withCmdFallback[T](key: String, exec: => T, fb: => JailedExecution[T, _]): BlockingExecution[T] with CmdFallback =
    new BlockingExecution[T] with CmdFallback {
      def cmdKey = key.asKey

      def execute = exec

      def fallback = fb
    }

}

/**
 * A default [[ScalaFutureExecution]] which infers the [[CommandKey]] based on the name of the class.
 */
abstract class BlockingCommand[T] extends BlockingExecution[T] with DefaultCommandNaming
