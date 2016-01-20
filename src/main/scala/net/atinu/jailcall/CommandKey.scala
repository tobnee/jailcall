package net.atinu.jailcall

import java.util.concurrent.ConcurrentHashMap

object CommandKey {

  private val intern = new ConcurrentHashMap[String, CommandKey]()

  def apply(_name: String) =
    if (intern.containsKey(_name)) intern.get(_name)
    else {
      val key = DefaultCommandKey(_name)
      intern.put(_name, key)
      key
    }
}

trait CommandKey {
  def name: String
}

case class DefaultCommandKey(name: String) extends CommandKey {

  override def toString = name
}
