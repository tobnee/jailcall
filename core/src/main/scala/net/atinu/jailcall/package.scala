package net.atinu

import akka.actor.{ Status, ActorRef }
import akka.actor.Status.Status

import scala.util.control.NoStackTrace

package object jailcall {

  implicit class StringToCommandKey(val name: String) extends AnyVal {
    def asKey = CommandKey.apply(name)
  }

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

    object Success {
      def unapply[T](res: JailcallExecutionResult[T]): Option[(T, ActorRef)] =
        res.originalSender.map(s => (res.result, s))
    }

    object Failure {
      def unapply(e: JailcallExecutionException): Option[(Throwable, ActorRef)] =
        e.originalSender.map(s => (e.failure, s))
    }

    object FailureStatus {
      def unapply(e: Status.Failure) = Status.Failure.unapply(e).flatMap {
        case e: JailcallExecutionException => e.originalSender.map(s => (e.failure, s))
        case _ => None
      }
    }
  }

  case class JailcallExecutionResult[T](result: T, originalSender: Option[ActorRef] = None)

  case class JailcallExecutionException(failure: Throwable, val originalSender: Option[ActorRef] = None)
    extends RuntimeException(failure) with NoStackTrace

}