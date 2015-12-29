package net.atinu.akka.defender.internal

import com.typesafe.config.ConfigFactory
import org.scalatest.{ FunSuite, Matchers }
import net.atinu.akka.defender._

class ConfigTest extends FunSuite with Matchers {

  import scala.concurrent.duration._

  val refCfg = ConfigFactory.load("reference.conf")
  val customCfg = ConfigFactory
    .parseString(
      """defender {
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
        |    }
        |    load-y {
        |
        |    }
        |  }
        |}""".stripMargin
    )
  val customCfg2 = ConfigFactory
    .parseString(
      """defender {
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
}
