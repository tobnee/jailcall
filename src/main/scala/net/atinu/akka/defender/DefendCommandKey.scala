package net.atinu.akka.defender

import java.util.concurrent.ConcurrentHashMap

object DefendCommandKey {

  private val intern = new ConcurrentHashMap[String, DefendCommandKey]()

  def apply(_name: String) =
    if (intern.containsKey(_name)) intern.get(_name)
    else {
      val key = DefaultDefendCommandKey(_name)
      intern.put(_name, key)
      key
    }
}

trait DefendCommandKey {
  def name: String
}

case class DefaultDefendCommandKey(name: String) extends DefendCommandKey {

  override def toString = name
}
