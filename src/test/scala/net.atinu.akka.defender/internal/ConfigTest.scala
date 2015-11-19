package net.atinu.akka.defender.internal

import com.typesafe.config.ConfigFactory
import org.scalatest.{FunSuite, Matchers}

class ConfigTest extends FunSuite with Matchers {

  import scala.concurrent.duration._

  val refCfg = ConfigFactory.load("reference.conf")
  val customCfg = ConfigFactory
    .parseString(
      """defender {
        |  command {
        |    load-data {
        |      circuit-breaker {
        |        max-failures = 11,
        |        call-timeout = 3 seconds,
        |        reset-timeout = 2 minutes
        |      }
        |    }
        |  }
        |}""".stripMargin)
  val cfg = refCfg.withFallback(customCfg)

  test("the default config gets loaded as expected") {
    val cfg = refCfg.withFallback(customCfg)
    val builder = new CircuitBreakerConfigBuilder(refCfg)
    val defaultCbConfig = builder.loadCbConfig("foo")
    defaultCbConfig should equal(CircuitBreakerConfig(10, 2.seconds, 1.minute))
  }

  test("load a custom config if specified") {
    val cfg = refCfg.withFallback(customCfg)
    val builder = new CircuitBreakerConfigBuilder(cfg)
    builder.loadCbConfig("load-data") should equal(CircuitBreakerConfig(11, 3.seconds, 2.minutes))
  }

}
