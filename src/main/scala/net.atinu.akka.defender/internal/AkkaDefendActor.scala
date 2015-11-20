package net.atinu.akka.defender.internal

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, Props}
import akka.pattern.CircuitBreaker
import akka.util.Timeout
import net.atinu.akka.defender.internal.AkkaDefendActor.MsgKeyConf
import net.atinu.akka.defender.{CmdFallback, DefendCommand, StaticFallback}

import scala.concurrent.Future

private[defender] class AkkaDefendActor extends Actor with ActorLogging {

  import akka.pattern.pipe
  import akka.pattern.ask
  import context.dispatcher

  val msgKeyToConf = collection.mutable.Map.empty[String, MsgKeyConf]

  val rootConfig = context.system.settings.config
  val cbConfigBuilder = new CircuitBreakerConfigBuilder(rootConfig)
  val cbBuilder = new CircuitBreakerBuilder(cbConfigBuilder, context.system.dispatcher, context.system.scheduler)

  def receive = {
    case msg: DefendCommand[_] =>
      val MsgKeyConf(cb) = msgKeyToConf.getOrElseUpdate(msg.cmdKey, newMsgKeyBasedConfig(msg.cmdKey))
      call(msg, cb) pipeTo sender()
  }

  def call(msg: DefendCommand[_], cb: CircuitBreaker): Future[Any] = {
    val exec = cb.withCircuitBreaker(msg.execute)
    val execOrFallback = fallback(msg, exec)
    execOrFallback
  }

  def fallback(msg: DefendCommand[_], exec: Future[Any]): Future[Any] =  msg match {
    case static: StaticFallback[_] => exec.fallbackTo(Future.successful(static.fallback))
    case dynamic: CmdFallback[_] =>
      // TODO: Improve
      implicit val to = Timeout.apply(30, TimeUnit.SECONDS)
      exec.fallbackTo(self ? dynamic.fallback)
    case _ => exec
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
