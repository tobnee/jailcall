package net.atinu.akka

package object defender {

  implicit class StringToCommandKey(val name: String) extends AnyVal {
    def asKey = DefendCommandKey.apply(name)
  }

}
