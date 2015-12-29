package net.atinu.akka.defender.internal

import java.util.concurrent.TimeUnit

import akka.ConfigurationException
import com.typesafe.config.Config
import net.atinu.akka.defender.DefendCommandKey

import scala.concurrent.duration._
import scala.util.Try

private[internal] case class MsgConfig(circuitBreaker: CircuitBreakerConfig, isolation: IsolationConfig, metrics: MetricsConfig)

private[internal] case class MetricsConfig(rollingStatsWindowDuration: FiniteDuration, rollingStatsBuckets: Int)

private[internal] case class CircuitBreakerConfig(enabled: Boolean, requestVolumeThreshold: Int, minFailurePercent: Int, callTimeout: FiniteDuration, resetTimeout: FiniteDuration)

private[internal] case class CustomIsolationConfig(dispatcherName: String)

object IsolationConfig {

  def default = IsolationConfig(None)

  def fromDispatcherName(name: String) = IsolationConfig(Some(CustomIsolationConfig(name)))
}

private[internal] case class IsolationConfig(custom: Option[CustomIsolationConfig])

private[internal] class MsgConfigBuilder(config: Config) {

  def loadConfigForKey(key: DefendCommandKey): Try[MsgConfig] = {
    Try {
      MsgConfig(loadCircuitBreakerConfig(key), loadDispatcherConfig(key), loadMetricsConfig(key))
    }
  }

  private val defaultCbConfigValue = loadConfigDefault("defender.command.default.circuit-breaker")

  private val defaultCbConfig = forceLoadCbConfigInPath(defaultCbConfigValue)

  private val defaultIsolationConfigValue = loadConfigDefault("defender.command.default.isolation")

  private val defaultIsolationConfig = forceLoadIsolationConfig(defaultIsolationConfigValue)

  private val defaultMetricsConfigValue = loadConfigDefault("defender.command.default.metrics")

  private val defaultMetricsConfig = forceLoadMetricsConfig(defaultMetricsConfigValue)

  private def loadCircuitBreakerConfig(key: DefendCommandKey): CircuitBreakerConfig =
    loadCbConfigInPath(loadConfig(cmdKeyCBConfigPath(key))
      .map(_.withFallback(defaultCbConfigValue))).getOrElse(defaultCbConfig)

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

  private def loadMetricsConfig(key: DefendCommandKey): MetricsConfig =
    loadMetricsConfigInPath(loadConfig(cmdKeyMetricsConfigPath(key))
      .map(_.withFallback(defaultMetricsConfigValue)))
      .collect {
        case mc if (mc.rollingStatsWindowDuration.toMillis % mc.rollingStatsBuckets) == 0 => mc
        case _ =>
          throw new ConfigurationException("rolling-stats.window should be divisible by rolling-stats.buckets")
      }.getOrElse(defaultMetricsConfig)

  private def loadMetricsConfigInPath(cfg: Option[Config]): Option[MetricsConfig] = {
    cfg.map { cbConfig => forceLoadMetricsConfig(cbConfig) }
  }

  private def forceLoadMetricsConfig(config: Config): MetricsConfig = {
    MetricsConfig(
      rollingStatsWindowDuration = loadFiniteDuration(config, "rolling-stats.window", TimeUnit.MILLISECONDS),
      rollingStatsBuckets = config.getInt("rolling-stats.buckets")
    )
  }

  private def cmdKeyMetricsConfigPath(key: DefendCommandKey) = s"defender.command.${key.name}.metrics"

  private def loadConfigDefault(key: String) = loadConfig(key)
    .getOrElse(throw new ConfigurationException("reference.conf is not in sync with CircuitBreakerConfigBuilder"))

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
