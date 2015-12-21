package net.atinu.akka

package object defender {

  implicit class StringToCommandKey(val name: String) extends AnyVal {
    def asKey = DefendCommandKey.apply(name)
  }

  class DefendBadRequestException(message: String, cause: Throwable) extends RuntimeException(message, cause) {
    def this(message: String) = this(message, this.asInstanceOf[RuntimeException])
  }

}
