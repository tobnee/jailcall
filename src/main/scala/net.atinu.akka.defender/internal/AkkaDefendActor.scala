package net.atinu.akka.defender.internal

import akka.actor.{Actor, ActorLogging, Props}
import akka.pattern.CircuitBreaker
import net.atinu.akka.defender.internal.AkkaDefendActor.{FallbackAction, MsgKeyConf}
import net.atinu.akka.defender._

import scala.concurrent.{Future, Promise}

private[defender] class AkkaDefendActor extends Actor with ActorLogging {

  import akka.pattern.pipe
  import context.dispatcher

  val msgKeyToConf = collection.mutable.Map.empty[String, MsgKeyConf]

  val rootConfig = context.system.settings.config
  val cbConfigBuilder = new CircuitBreakerConfigBuilder(rootConfig)
  val cbBuilder = new CircuitBreakerBuilder(cbConfigBuilder, context.system.scheduler)

  def receive = {
    case msg: DefendCommand[_] =>
      val MsgKeyConf(cb) = getOrUpdateConf(msg)
      callAsync(msg, cb) pipeTo sender()

    case msg: SyncDefendCommand[_] =>
      val MsgKeyConf(cb) = getOrUpdateConf(msg)
      callSync(msg, cb) pipeTo sender()

    case FallbackAction(promise, msg) =>
      val MsgKeyConf(cb) = getOrUpdateConf(msg)
      promise.completeWith(callAsync(msg, cb))
  }

  def getOrUpdateConf(msg: NamedCommand[_]): MsgKeyConf = {
    msgKeyToConf.getOrElseUpdate(msg.cmdKey, newMsgKeyBasedConfig(msg.cmdKey))
  }

  def callSync(msg: SyncDefendCommand[_], cb: CircuitBreaker): Future[Any] = {
    execFlow(msg, cb, Future.apply(msg.execute)(context.dispatcher))
  }

  def callAsync(msg: DefendCommand[_], cb: CircuitBreaker): Future[Any] = {
    execFlow(msg, cb, msg.execute)
  }

  def execFlow(msg: NamedCommand[_], cb: CircuitBreaker, execute: => Future[Any]): Future[Any] = {
    val exec = cb.withCircuitBreaker(execute)
    val execOrFallback = fallback(msg, exec)
    execOrFallback
  }

  def fallback(msg: NamedCommand[_], exec: Future[Any]): Future[Any] = msg match {
    case static: StaticFallback[_] => exec.fallbackTo(Future.successful(static.fallback))
    case dynamic: CmdFallback[_] =>
      exec.fallbackTo {
        val fallbackPromise = Promise.apply[Any]()
        self ! FallbackAction(fallbackPromise, dynamic.fallback)
        fallbackPromise.future
      }
    case _ => exec
  }

  private def newMsgKeyBasedConfig(msgKey: String) = {
    MsgKeyConf(cbBuilder.createCb(msgKey, log))
  }
}

object AkkaDefendActor {

  private[internal] case class MsgKeyConf(circuitBreaker: CircuitBreaker)

  private[internal] case object GetKeyConfigs

  private[internal] case class FallbackAction(fallbackPromise: Promise[Any], cmd: DefendCommand[_])

  def props = Props(new AkkaDefendActor)
}
