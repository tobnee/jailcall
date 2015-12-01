package net.atinu.akka.defender


object DefendCommandKey {

  def apply(_name: String) = new DefaultDefendCommandKey {
    override def name: String = _name
  }
}

trait DefendCommandKey {
  def name: String
}

trait DefaultDefendCommandKey extends DefendCommandKey {
  override def toString = name
}
