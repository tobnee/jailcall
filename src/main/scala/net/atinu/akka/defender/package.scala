package net.atinu.akka

package object defender {

  implicit class StringToCommandKey(val name: String) extends AnyVal {
    def asKey = DefendCommandKey.apply(name)
  }

  sealed trait DefendBadRequestException extends Throwable

  class DefendBadRequestExceptionWithCause(message: String, cause: Throwable) extends RuntimeException(message, cause) with DefendBadRequestException

  class SimpleDefendBadRequestException(message: String) extends RuntimeException(message) with DefendBadRequestException

  object DefendBadRequestException {

    def apply(message: String): DefendBadRequestException = new SimpleDefendBadRequestException(message)

    def apply(message: String, cause: Throwable): DefendBadRequestException = new DefendBadRequestExceptionWithCause(message, cause)

  }

  sealed trait SuccessCategory {
    def isBadRequest: Boolean
    def isSuccess: Boolean = !isBadRequest
  }

  object IsBadRequest extends SuccessCategory {
    override def isBadRequest = true
  }

  object IsSuccess extends SuccessCategory {
    override def isBadRequest = false
  }
}
