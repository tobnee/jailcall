package net.atinu.akka

package object defender {

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
