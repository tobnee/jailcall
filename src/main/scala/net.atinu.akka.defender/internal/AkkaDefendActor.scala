package net.atinu.akka.defender.internal

import akka.actor.{Actor, ActorLogging, Props}
import akka.pattern.CircuitBreaker
import net.atinu.akka.defender.DefendCommand
import net.atinu.akka.defender.internal.AkkaDefendActor.MsgKeyConf

private[defender] class AkkaDefendActor extends Actor with ActorLogging {

  import akka.pattern.pipe
  import context.dispatcher

  val msgKeyToConf = collection.mutable.Map.empty[String, MsgKeyConf]

  val rootConfig = context.system.settings.config
  val cbConfigBuilder = new CircuitBreakerConfigBuilder(rootConfig)
  val cbBuilder = new CircuitBreakerBuilder(cbConfigBuilder, context.system.dispatcher, context.system.scheduler)

  def receive = {
    case msg: DefendCommand[_] =>
      val MsgKeyConf(cb) = msgKeyToConf.getOrElseUpdate(msg.cmdKey, newMsgKeyBasedConfig(msg.cmdKey))
      cb.withCircuitBreaker(msg.execute) pipeTo sender()
  }

  private def newMsgKeyBasedConfig(msgKey: String) = {
    MsgKeyConf(cbBuilder.createCb(msgKey, log))
  }

}

object AkkaDefendActor {

  private[internal] case class MsgKeyConf(circuitBreaker: CircuitBreaker)

  private[internal] case object GetKeyConfigs

  def props = Props(new AkkaDefendActor)
}
