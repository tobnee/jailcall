package net.atinu.jailcall.internal

import java.util.concurrent.TimeUnit

import akka.ConfigurationException
import com.typesafe.config.Config
import net.atinu.jailcall.CommandKey

import scala.concurrent.duration._
import scala.util.Try

private[internal] case class MsgConfig(rawConfigValue: Config, circuitBreaker: CircuitBreakerConfig, isolation: IsolationConfig, metrics: MetricsConfig)

private[internal] case class MetricsConfig(rollingStatsWindowDuration: FiniteDuration, rollingStatsBuckets: Int)

private[internal] case class CircuitBreakerConfig(enabled: Boolean, requestVolumeThreshold: Int, minFailurePercent: Int, callTimeout: FiniteDuration, resetTimeout: FiniteDuration)

private[internal] case class CustomIsolationConfig(dispatcherName: String)

object IsolationConfig {

  def default = IsolationConfig(None)

  def fromDispatcherName(name: String) = IsolationConfig(Some(CustomIsolationConfig(name)))
}

private[internal] case class IsolationConfig(custom: Option[CustomIsolationConfig])

private[internal] class MsgConfigBuilder(config: Config) {

  def loadConfigForKey(key: CommandKey): Try[MsgConfig] = {
    Try {
      val cmdKeyConfig = loadCmdKeyConfig(key)
      MsgConfig(
        rawConfigValue = cmdKeyConfig,
        circuitBreaker = asCbConfig(cmdKeyConfig.getConfig("circuit-breaker")),
        isolation = asIsolationConfig(cmdKeyConfig.getConfig("isolation")),
        metrics = asMetricsConfig(cmdKeyConfig.getConfig("metrics"))
      )
    }
  }

  private val defaultConfigValue: Config = loadConfig("jailcall.command.default")
    .getOrElse(throw new ConfigurationException("reference.conf is not in sync with CircuitBreakerConfigBuilder"))

  private def loadCmdKeyConfig(key: CommandKey): Config =
    loadConfig(s"jailcall.command.${key.name}")
      .map(_.withFallback(defaultConfigValue))
      .getOrElse(defaultConfigValue)

  private def asCbConfig(cbConfig: Config): CircuitBreakerConfig = {
    CircuitBreakerConfig(
      enabled = cbConfig.getBoolean("enabled"),
      requestVolumeThreshold = cbConfig.getInt("request-volume-threshold"),
      minFailurePercent = cbConfig.getInt("min-failure-percent"),
      callTimeout = loadFiniteDuration(cbConfig, "call-timeout", TimeUnit.MILLISECONDS),
      resetTimeout = loadFiniteDuration(cbConfig, "reset-timeout", TimeUnit.SECONDS)
    )
  }

  private def asIsolationConfig(isolationConfig: Config) = {
    IsolationConfig(
      custom =
      if (isolationConfig.getBoolean("auto")) None
      else asCustomIsolationConfig(isolationConfig)
    )
  }

  private def asCustomIsolationConfig(isolationConfig: Config) = {
    loadConfigString("custom.dispatcher", isolationConfig).map { dispatcher =>
      CustomIsolationConfig(
        dispatcherName = dispatcher
      )
    }
  }

  private def asMetricsConfig(config: Config): MetricsConfig = {
    val mc = MetricsConfig(
      rollingStatsWindowDuration = loadFiniteDuration(config, "rolling-stats.window", TimeUnit.MILLISECONDS),
      rollingStatsBuckets = config.getInt("rolling-stats.buckets")
    )
    if ((mc.rollingStatsWindowDuration.toMillis % mc.rollingStatsBuckets) == 0) mc
    else throw new ConfigurationException("rolling-stats.window should be divisible by rolling-stats.buckets")
  }

  private def loadConfig(path: String): Option[Config] = {
    if (config.hasPath(path)) Some(config.getConfig(path)) else None
  }

  private def loadConfigString(key: String, cfg: Config): Option[String] = {
    if (cfg.hasPath(key)) Some(cfg.getString(key)) else None
  }

  private def loadFiniteDuration(cfg: Config, key: String, timeUnit: TimeUnit) = {
    FiniteDuration.apply(cfg.getDuration(key, timeUnit), timeUnit)
  }
}
