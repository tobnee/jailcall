package net.atinu.akka.defender.internal

import java.util.concurrent.TimeUnit

import akka.actor.Scheduler
import akka.dispatch.{ Dispatchers, MessageDispatcher }
import akka.event.LoggingAdapter
import akka.pattern.AkkaDefendCircuitBreaker
import com.typesafe.config.Config
import net.atinu.akka.defender.DefendCommandKey
import net.atinu.akka.defender.internal.DispatcherLookup.DispatcherHolder

import scala.concurrent.duration._

private[internal] case class MsgConfig(cbConfig: CircuitBreakerConfig, dispatcherName: Option[String])

private[internal] case class CircuitBreakerConfig(enabled: Boolean, requestVolumeThreshold: Int, minFailurePercent: Int, callTimeout: FiniteDuration, resetTimeout: FiniteDuration)

private[internal] class MsgConfigBuilder(config: Config) {

  def loadConfigForKey(key: DefendCommandKey): MsgConfig = {
    MsgConfig(loadCircuitBreakerConfig(key), loadDispatcherConfig(key))
  }

  private def loadDispatcherConfig(key: DefendCommandKey) = {
    loadConfigString(cmdKeyDispatcherConfigPath(key))
  }

  private val defaultCbconfigValue = loadConfig("defender.circuit-breaker.default")
    .getOrElse(throw new IllegalStateException("reference.conf is not in sync with CircuitBreakerConfigBuilder"))

  private val defaultCbConfig = forceLoadCbConfigInPath(defaultCbconfigValue)

  private def loadCircuitBreakerConfig(key: DefendCommandKey): CircuitBreakerConfig =
    loadCbConfigInPath(loadConfig(cmdKeyCBConfigPath(key)).map(_.withFallback(defaultCbconfigValue))).getOrElse(defaultCbConfig)

  private def cmdKeyCBConfigPath(key: DefendCommandKey) = s"defender.command.${key.name}.circuit-breaker"

  private def cmdKeyDispatcherConfigPath(key: DefendCommandKey) = s"defender.command.${key.name}.dispatcher"

  private def loadCbConfigInPath(cfg: Option[Config]) = {
    cfg.map { cbConfig => forceLoadCbConfigInPath(cbConfig) }
  }

  private def forceLoadCbConfigInPath(cbConfig: Config): CircuitBreakerConfig = {
    CircuitBreakerConfig(
      enabled = cbConfig.getBoolean("enabled"),
      requestVolumeThreshold = cbConfig.getInt("request-volume-threshold"),
      minFailurePercent = cbConfig.getInt("min-failure-percent"),
      callTimeout = loadFiniteDuration(cbConfig, "call-timeout", TimeUnit.MILLISECONDS),
      resetTimeout = loadFiniteDuration(cbConfig, "reset-timeout", TimeUnit.SECONDS)
    )
  }

  private def loadConfig(path: String): Option[Config] = {
    if (config.hasPath(path)) Some(config.getConfig(path)) else None
  }

  private def loadConfigString(key: String): Option[String] = {
    if (config.hasPath(key)) Some(config.getString(key)) else None
  }

  private def loadFiniteDuration(cfg: Config, key: String, timeUnit: TimeUnit) = {
    FiniteDuration.apply(cfg.getDuration(key, timeUnit), timeUnit)
  }
}

private[internal] class CircuitBreakerBuilder(scheduler: Scheduler) {

  def createCb(msgKey: DefendCommandKey, cfg: CircuitBreakerConfig, log: LoggingAdapter): AkkaDefendCircuitBreaker = {
    createCbFromConfig(msgKey, cfg, log)
  }

  private def createCbFromConfig(msgKey: DefendCommandKey, cbConfig: CircuitBreakerConfig, log: LoggingAdapter) = {
    AkkaDefendCircuitBreaker(
      scheduler,
      maxFailures = cbConfig.minFailurePercent,
      callTimeout = cbConfig.callTimeout,
      resetTimeout = cbConfig.resetTimeout
    )
      .onClose(log.info("circuit breaker for command {} is closed again", msgKey))
      .onHalfOpen(log.info("circuit breaker for command {} is half open, wait for first call to succeed", msgKey))
      .onOpen(log.warning("circuit breaker for command {} is open for {}", msgKey, cbConfig.resetTimeout))
  }
}

private[internal] class DispatcherLookup(dispatchers: Dispatchers) {

  def lookupDispatcher(msgKey: DefendCommandKey, msgConfig: MsgConfig, log: LoggingAdapter): DispatcherHolder = {
    msgConfig.dispatcherName match {
      case Some(dispatcherName) if dispatchers.hasDispatcher(dispatcherName) =>
        DispatcherHolder(dispatchers.lookup(dispatcherName), isDefault = false)

      case Some(dispatcherName) =>
        log.warning(
          "dispatcher {} was configured for cmd {} but not available, fallback to default dispatcher",
          dispatcherName, msgKey.name
        )
        DispatcherHolder(dispatchers.defaultGlobalDispatcher, isDefault = true)

      case _ =>
        DispatcherHolder(dispatchers.defaultGlobalDispatcher, isDefault = true)
    }
  }
}

object DispatcherLookup {

  case class DispatcherHolder(dispatcher: MessageDispatcher, isDefault: Boolean)
}

