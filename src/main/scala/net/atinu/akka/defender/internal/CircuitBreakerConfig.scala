package net.atinu.akka.defender.internal

import java.util.concurrent.TimeUnit

import akka.actor.Scheduler
import akka.dispatch.{ Dispatchers, MessageDispatcher }
import akka.event.LoggingAdapter
import akka.pattern.AkkaDefendCircuitBreaker
import com.typesafe.config.Config
import net.atinu.akka.defender.{ AkkaDefender, DefendCommandKey }
import net.atinu.akka.defender.internal.DispatcherLookup.DispatcherHolder

import scala.concurrent.duration._

private[internal] case class MsgConfig(circuitBreaker: CircuitBreakerConfig, isolation: IsolationConfig)

private[internal] case class CircuitBreakerConfig(enabled: Boolean, requestVolumeThreshold: Int, minFailurePercent: Int, callTimeout: FiniteDuration, resetTimeout: FiniteDuration)

private[internal] case class CustomIsolationConfig(dispatcherName: String)

object IsolationConfig {

  def default = IsolationConfig(None)
}

private[internal] case class IsolationConfig(custom: Option[CustomIsolationConfig])

private[internal] class MsgConfigBuilder(config: Config) {

  def loadConfigForKey(key: DefendCommandKey): MsgConfig = {
    MsgConfig(loadCircuitBreakerConfig(key), loadDispatcherConfig(key))
  }

  private val defaultCbconfigValue = loadConfig("defender.command.default.circuit-breaker")
    .getOrElse(throw new IllegalStateException("reference.conf is not in sync with CircuitBreakerConfigBuilder"))

  private val defaultCbConfig = forceLoadCbConfigInPath(defaultCbconfigValue)

  private val defaultIsolationConfigValue = loadConfig("defender.command.default.isolation")
    .getOrElse(throw new IllegalStateException("reference.conf is not in sync with CircuitBreakerConfigBuilder"))

  private val defaultIsolationConfig = forceLoadIsolationConfig(defaultIsolationConfigValue)

  private def loadCircuitBreakerConfig(key: DefendCommandKey): CircuitBreakerConfig =
    loadCbConfigInPath(loadConfig(cmdKeyCBConfigPath(key))
      .map(_.withFallback(defaultCbconfigValue))).getOrElse(defaultCbConfig)

  private def cmdKeyCBConfigPath(key: DefendCommandKey) = s"defender.command.${key.name}.circuit-breaker"

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

  private def loadDispatcherConfig(key: DefendCommandKey): IsolationConfig = {
    loadIsolationConfig(loadConfig(cmdKeyDispatcherConfigPath(key))
      .map(_.withFallback(defaultIsolationConfigValue))).getOrElse(defaultIsolationConfig)
  }

  private def cmdKeyDispatcherConfigPath(key: DefendCommandKey) = s"defender.command.${key.name}.isolation"

  private def loadIsolationConfig(isolationConfig: Option[Config]) = {
    isolationConfig.map(cfg => forceLoadIsolationConfig(cfg))
  }

  private def forceLoadIsolationConfig(isolationConfig: Config) = {
    IsolationConfig(
      custom =
      if (isolationConfig.getBoolean("auto")) None
      else forceLoadCustomIsolationConfig(isolationConfig)
    )
  }

  private def forceLoadCustomIsolationConfig(isolationConfig: Config) = {
    loadConfigString("custom.dispatcher", isolationConfig).map { dispatcher =>
      CustomIsolationConfig(
        dispatcherName = dispatcher
      )
    }
  }

  private def loadConfig(path: String): Option[Config] = {
    if (config.hasPath(path)) Some(config.getConfig(path)) else None
  }

  private def loadConfigString(key: String): Option[String] =
    loadConfigString(key, config)

  private def loadConfigString(key: String, cfg: Config): Option[String] = {
    if (cfg.hasPath(key)) Some(cfg.getString(key)) else None
  }

  private def loadFiniteDuration(cfg: Config, key: String, timeUnit: TimeUnit) = {
    FiniteDuration.apply(cfg.getDuration(key, timeUnit), timeUnit)
  }
}
