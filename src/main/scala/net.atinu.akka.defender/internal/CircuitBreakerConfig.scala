package net.atinu.akka.defender.internal

import java.util.concurrent.TimeUnit

import akka.actor.Scheduler
import akka.event.LoggingAdapter
import akka.pattern.CircuitBreaker
import com.typesafe.config.Config

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

private[internal] case class CircuitBreakerConfig(maxFailures: Int, callTimeout: FiniteDuration, resetTimeout: FiniteDuration)

private[internal] class CircuitBreakerConfigBuilder(config: Config) {

  val defaultCbConfig =
    loadCbConfigForKey("defender.circuit-breaker.default")
      .getOrElse(throw new IllegalStateException("reference.conf is not in sync with CircuitBreakerConfigBuilder"))

  def loadCbConfig(key: String) =
    loadCbConfigForKey(cmdKeyConfigPath(key)).getOrElse(defaultCbConfig)

  private def cmdKeyConfigPath(key: String) = s"defender.command.$key.circuit-breaker"

  private def loadCbConfigForKey(key: String) = {
    val cfg = if(config.hasPath(key)) Some(config.getConfig(key)) else None
    cfg.map { cbConfig =>
      CircuitBreakerConfig(
        maxFailures = cbConfig.getInt("max-failures"),
        callTimeout = loadFiniteDuration(cbConfig, "call-timeout", TimeUnit.MILLISECONDS),
        resetTimeout = loadFiniteDuration(cbConfig, "reset-timeout", TimeUnit.SECONDS)
      )
    }
  }

  private def loadFiniteDuration(cfg: Config, key: String, timeUnit: TimeUnit) = {
    FiniteDuration.apply(cfg.getDuration(key, timeUnit), timeUnit)
  }
}

private[internal] class CircuitBreakerBuilder(config: CircuitBreakerConfigBuilder, defaultEc: ExecutionContext, scheduler: Scheduler) {

  def createCb(msgKey: String, log: LoggingAdapter): CircuitBreaker = {
    createCb(msgKey, log, defaultEc)
  }

  def createCb(msgKey: String, log: LoggingAdapter, ec: ExecutionContext): CircuitBreaker = {
    createCustomCb(msgKey, config.loadCbConfig(msgKey), log, ec)
  }

  private def createCustomCb(msgKey: String, cbConfig: CircuitBreakerConfig, log: LoggingAdapter, ec: ExecutionContext) = {
    new CircuitBreaker(scheduler,
      maxFailures = cbConfig.maxFailures,
      callTimeout = cbConfig.callTimeout,
      resetTimeout = cbConfig.resetTimeout)(ec)
      .onClose(log.debug("circuit breaker for command {} is closed again", msgKey))
      .onHalfOpen(log.debug("circuit breaker for command {} is half open, wait for first call to succeed", msgKey))
      .onOpen(log.debug("circuit breaker for command {} is open for {}", msgKey, cbConfig.resetTimeout))
  }
}




