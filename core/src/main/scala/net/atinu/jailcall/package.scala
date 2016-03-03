package net.atinu

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

}