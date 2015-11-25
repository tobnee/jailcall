package net.atinu.akka.defender.internal

import java.util.concurrent.TimeUnit

import akka.actor.Scheduler
import akka.dispatch.{MessageDispatcher, Dispatchers}
import akka.event.LoggingAdapter
import akka.pattern.CircuitBreaker
import com.typesafe.config.Config

import scala.concurrent.duration._

private[internal] case class MsgConfig(cbConfig: CircuitBreakerConfig, dispatcherName: Option[String])

private[internal] case class CircuitBreakerConfig(maxFailures: Int, callTimeout: FiniteDuration, resetTimeout: FiniteDuration)

private[internal] class MsgConfigBuilder(config: Config) {

  def loadConfigForKey(key: String) = {
    MsgConfig(loadCbConfig(key), loadDispatcherConfig(key))
  }

  private def loadDispatcherConfig(key: String) = {
    loadConfigString(cmdKeyDispatcherConfigPath(key))
  }

  private val defaultCbConfig =
    loadCbConfigForKey("defender.circuit-breaker.default")
      .getOrElse(throw new IllegalStateException("reference.conf is not in sync with CircuitBreakerConfigBuilder"))

  private def loadCbConfig(key: String): CircuitBreakerConfig =
    loadCbConfigForKey(cmdKeyCBConfigPath(key)).getOrElse(defaultCbConfig)

  private def cmdKeyCBConfigPath(key: String) = s"defender.command.$key.circuit-breaker"

  private def cmdKeyDispatcherConfigPath(key: String) = s"defender.command.$key.dispatcher"

  private def loadCbConfigForKey(key: String) = {
    val cfg = loadConfig(key)
    cfg.map { cbConfig =>
      CircuitBreakerConfig(
        maxFailures = cbConfig.getInt("max-failures"),
        callTimeout = loadFiniteDuration(cbConfig, "call-timeout", TimeUnit.MILLISECONDS),
        resetTimeout = loadFiniteDuration(cbConfig, "reset-timeout", TimeUnit.SECONDS)
      )
    }
  }

  private def loadConfig(key: String): Option[Config] = {
    if (config.hasPath(key)) Some(config.getConfig(key)) else None
  }

  private def loadConfigString(key: String): Option[String] = {
    if (config.hasPath(key)) Some(config.getString(key)) else None
  }

  private def loadFiniteDuration(cfg: Config, key: String, timeUnit: TimeUnit) = {
    FiniteDuration.apply(cfg.getDuration(key, timeUnit), timeUnit)
  }
}

private[internal] class CircuitBreakerBuilder(scheduler: Scheduler) {

  def createCb(msgKey: String, cfg: CircuitBreakerConfig, log: LoggingAdapter): CircuitBreaker = {
    createCustomCb(msgKey, cfg, log)
  }

  private def createCustomCb(msgKey: String, cbConfig: CircuitBreakerConfig, log: LoggingAdapter) = {
    CircuitBreaker.create(scheduler,
      maxFailures = cbConfig.maxFailures,
      callTimeout = cbConfig.callTimeout,
      resetTimeout = cbConfig.resetTimeout)
      .onClose(log.debug("circuit breaker for command {} is closed again", msgKey))
      .onHalfOpen(log.debug("circuit breaker for command {} is half open, wait for first call to succeed", msgKey))
      .onOpen(log.debug("circuit breaker for command {} is open for {}", msgKey, cbConfig.resetTimeout))
  }
}

private[internal] class DispatcherLookup(dispatchers: Dispatchers) {

  def lookupDispatcher(dispatcherName: String): MessageDispatcher = {
    if(dispatchers.hasDispatcher(dispatcherName)) dispatchers.lookup(dispatcherName)
    else dispatchers.defaultGlobalDispatcher
  }
}




