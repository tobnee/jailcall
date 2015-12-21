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
}
