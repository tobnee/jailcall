package net.atinu

import akka.actor.{ Status, ActorRef }
import net.atinu.jailcall.common.BaseJailcallExecutionException

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NoStackTrace

package object jailcall {

  implicit class StringToCommandKey(val name: String) extends AnyVal {
    def asKey = CommandKey.apply(name)
  }

  /**
   * A specific exception type which signals that jailcall should not include this failed message in any error statistic
   */
  sealed trait BadRequestException extends Throwable

  class BadRequestExceptionWithCause(message: String, cause: Throwable) extends RuntimeException(message, cause) with BadRequestException

  class SimpleBadRequestException(message: String) extends RuntimeException(message) with BadRequestException

  object BadRequestException {

    def apply(message: String): BadRequestException = new SimpleBadRequestException(message)

    def apply(message: String, cause: Throwable): BadRequestException = new BadRequestExceptionWithCause(message, cause)

  }

  sealed trait ResultCategory {
    def isBadRequest: Boolean

    def isSuccess: Boolean = !isBadRequest
  }

  object IsBadRequest extends ResultCategory {
    override def isBadRequest = true
  }

  object IsSuccess extends ResultCategory {
    override def isBadRequest = false

  }

  object JailcallExecutionResult {

    def fromResult[T](res: T) = JailcallExecutionResult(res, None)

    def fromResult[T](res: T, originalSender: ActorRef) = JailcallExecutionResult(res, Some(originalSender))

    /**
     * Extract the value from the result
     */
    object Success {
      def unapply[T](res: JailcallExecutionResult[T]): JailcallValue[T] =
        new JailcallValue(res.result)

      class JailcallValue[T](val value: T) extends AnyRef {
        def isEmpty: Boolean = false
        def get: T = value
      }

    }

    /**
     * Extract the value and the original sender from the result. Requires request to be made with context.
     */
    object SuccessWithContext {
      class JailcallValue[T](val value: (T, ActorRef)) extends AnyRef {
        def isEmpty: Boolean = value == null || value._2 == null
        def get: (T, ActorRef) = value
      }

      def unapply[T](res: JailcallExecutionResult[T]): JailcallValue[T] =
        new JailcallValue(res.result, res.originalSender.orNull)
    }

    /**
     * Extract the exception from the result
     */
    object Failure {

      def unapply(err: Status.Failure) = new JailcallValue(err)

      class JailcallValue(val value: Status.Failure) extends AnyRef {
        def isEmpty: Boolean = value.cause match {
          case e: JailcallExecutionException => false
          case _ => true
        }
        def get: Throwable = value.cause.asInstanceOf[JailcallExecutionException].failure
      }
    }

    /**
     * Extract the exception and the original sender from the result. Requires request to be made with context.
     */
    object FailureWithContext {
      def unapply(e: Status.Failure): Option[(Throwable, ActorRef)] = e.cause match {
        case e: JailcallExecutionException => e.originalSender.map(s => (e.failure, s))
        case _ => None
      }

      class JailcallValue(val value: Status.Failure) extends AnyRef {
        def isEmpty: Boolean = value.cause match {
          case e: JailcallExecutionException => e.originalSender.isEmpty
          case _ => false
        }
        def get: (Throwable, ActorRef) = {
          val ex = value.cause.asInstanceOf[JailcallExecutionException]
          (ex.failure, ex.originalSender.get)
        }
      }
    }
  }

  /**
   * The result of a sucessful execution in jailcall
   */
  case class JailcallExecutionResult[T](result: T, originalSender: Option[ActorRef] = None)

  /**
   * The result of a failed execution in jailcall
   *
   * @param failure
   * @param originalSender
   */
  case class JailcallExecutionException(failure: Throwable, val originalSender: Option[ActorRef] = None)
    extends BaseJailcallExecutionException(failure)
}