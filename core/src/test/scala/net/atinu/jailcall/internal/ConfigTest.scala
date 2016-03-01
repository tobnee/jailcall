package net.atinu.jailcall.internal

import akka.ConfigurationException
import com.typesafe.config.ConfigFactory
import net.atinu.jailcall._
import org.scalatest.{ FunSuite, Matchers }

class ConfigTest extends FunSuite with Matchers {

  import scala.concurrent.duration._

  val refCfg = ConfigFactory.load("reference.conf")
  val customCfg = ConfigFactory
    .parseString(
      """jailcall {
        |  command {
        |    load-data {
        |      circuit-breaker {
                enabled = false,
        |       request-volume-threshold = 21,
        |       min-failure-percent = 60,
        |       call-timeout = 3 seconds,
        |       reset-timeout = 2 minutes,
        |      },
        |      isolation {
        |        auto = false
        |        custom = {
            |      dispatcher = "foo"
        |        }
        |      }
        |      metrics {
        |        rolling-stats = {
        |          window = 11 seconds
        |          buckets = 11
        |        }
        |      }
        |    }
        |    load-y {
        |
        |    }
        |    load-s {
        |     metrics {
        |        rolling-stats = {
        |          buckets = 9
        |        }
        |      }
        |    }
        |  }
        |}""".stripMargin
    )
  val customCfg2 = ConfigFactory
    .parseString(
      """jailcall {
        |  command {
        |    load-data {
        |    }
        |  }
        |}""".stripMargin
    )

  val cfg = refCfg.withFallback(customCfg)
  val defaultCbConfig: CircuitBreakerConfig = CircuitBreakerConfig(true, 20, 50, 1.second, 5.seconds)

  test("the default config gets loaded as expected") {
    val cfg = refCfg.withFallback(customCfg)
    val builder = new MsgConfigBuilder(refCfg)
    val defCbConfig = builder.loadConfigForKey("foo".asKey)
    defCbConfig.get.circuitBreaker should equal(defaultCbConfig)
  }

  test("non specified key results in default config") {
    val cfg = refCfg.withFallback(customCfg)
    val builder = new MsgConfigBuilder(cfg)
    builder.loadConfigForKey("load-x".asKey).get.circuitBreaker should equal(defaultCbConfig)
  }

  test("specified key partial options results in default config fallback") {
    val cfg = refCfg.withFallback(customCfg)
    val builder = new MsgConfigBuilder(cfg)
    builder.loadConfigForKey("load-y".asKey).get.circuitBreaker should equal(defaultCbConfig)
  }

  test("load a custom config if specified") {
    val cfg = refCfg.withFallback(customCfg)
    val builder = new MsgConfigBuilder(cfg)
    builder.loadConfigForKey("load-data".asKey).get.circuitBreaker should equal(CircuitBreakerConfig(false, 21, 60, 3.seconds, 2.minutes))
  }

  test("load a dispatcher if specified") {
    val cfg = refCfg.withFallback(customCfg)
    val builder = new MsgConfigBuilder(cfg)
    builder.loadConfigForKey("load-data".asKey).get.isolation.custom should equal(Some(CustomIsolationConfig("foo")))
  }

  test("ignore dispatcher if not specified") {
    val cfg = refCfg.withFallback(customCfg2)
    val builder = new MsgConfigBuilder(cfg)
    builder.loadConfigForKey("load-data".asKey).get.isolation.custom should equal(None)
  }

  test("load default metrics info") {
    val cfg = refCfg.withFallback(customCfg)
    val builder = new MsgConfigBuilder(cfg)
    val metrics: MetricsConfig = builder.loadConfigForKey("foo".asKey).get.metrics
    metrics.rollingStatsBuckets should equal(10)
    metrics.rollingStatsWindowDuration should equal(10 seconds)
  }

  test("load custom metrics info") {
    val cfg = refCfg.withFallback(customCfg)
    val builder = new MsgConfigBuilder(cfg)
    val metrics: MetricsConfig = builder.loadConfigForKey("load-data".asKey).get.metrics
    metrics.rollingStatsBuckets should equal(11)
    metrics.rollingStatsWindowDuration should equal(11 seconds)
  }

  test("fail for invalid rolling-stats settings") {
    val cfg = refCfg.withFallback(customCfg)
    val builder = new MsgConfigBuilder(cfg)
    val metrics = builder.loadConfigForKey("load-s".asKey)
    metrics should be a 'failure
    metrics.failed.get shouldBe a[ConfigurationException]
  }
}
