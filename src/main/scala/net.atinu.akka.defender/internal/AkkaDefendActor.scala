package net.atinu.akka.defender.internal

import java.util.concurrent.TimeUnit

import akka.actor.{ActorLogging, Actor, Props}
import akka.pattern.CircuitBreaker
import com.typesafe.config.Config
import net.atinu.akka.defender.DefendCommand
import net.atinu.akka.defender.internal.AkkaDefendActor.{CircuitBreakerConfig, MsgKeyConf}

import scala.concurrent.duration.FiniteDuration

private[defender] class AkkaDefendActor extends Actor with ActorLogging {

  import akka.pattern.pipe
  import context.dispatcher
  import scala.concurrent.duration._

  val msgKeyToConf = collection.mutable.Map.empty[String, MsgKeyConf]

  val rootConfig = context.system.settings.config
  val cbConfig = CircuitBreakerConfig(
    maxFailures = rootConfig.getInt("defender.circuit-breaker.default.max-failures"),
    callTimeout = loadFiniteDuration(rootConfig, "defender.circuit-breaker.default.call-timeout", TimeUnit.MILLISECONDS),
    resetTimeout = loadFiniteDuration(rootConfig, "defender.circuit-breaker.default.reset-timeout", TimeUnit.SECONDS)
  )

  def receive = {
    case msg: DefendCommand[_] =>
      val MsgKeyConf(cb) = msgKeyToConf.getOrElseUpdate(msg.cmdKey, newMsgKeyBasedConfig(msg.cmdKey))
      cb.withCircuitBreaker(msg.execute) pipeTo sender()
  }

  private def newMsgKeyBasedConfig(msgKey: String) = {
    MsgKeyConf(newCb(msgKey, cbConfig))
  }

  private def newCb(msgKey: String, cbConfig: CircuitBreakerConfig) = {
    new CircuitBreaker(context.system.scheduler,
      maxFailures = 5,
      callTimeout = 2.seconds,
      resetTimeout = 30.seconds)(context.dispatcher)
      .onClose(log.debug("circuit breaker for command {} is closed again", msgKey))
      .onHalfOpen(log.debug("circuit breaker for command {} is half open, wait for first call to succeed", msgKey))
      .onOpen(log.debug("circuit breaker for command {} is open", msgKey))
  }

  private def loadFiniteDuration(cfg: Config, key: String, timeUnit: TimeUnit) = {
    FiniteDuration.apply(cfg.getDuration(key, timeUnit), timeUnit)
  }
}

object AkkaDefendActor {

  private[internal] case class MsgKeyConf(circuitBreaker: CircuitBreaker)

  private[internal] case class CircuitBreakerConfig(maxFailures: Int, callTimeout: FiniteDuration, resetTimeout: FiniteDuration)

  private[internal] case object GetKeyConfigs

  def props = Props(new AkkaDefendActor)
}
