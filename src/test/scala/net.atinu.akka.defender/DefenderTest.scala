package net.atinu.akka.defender

import akka.actor.Status.Failure
import com.typesafe.config.ConfigFactory
import net.atinu.akka.defender.util.ActorTest

import scala.concurrent.Future

class DefenderTest extends ActorTest("DefenderTest", DefenderTest.config) {
  import scala.concurrent.ExecutionContext.Implicits.global

  test("the result of a future is executed and returned") {
    AkkaDefender(system).defender.executeToRef(new DefendCommand[String] {
      def cmdKey = "a"
      def execute: Future[String] = Future.successful("succFuture")
    })
    expectMsg("succFuture")
  }

  test("the result of a failed future is a failure message") {
    val err = new scala.IllegalArgumentException("foo")
    AkkaDefender(system).defender.executeToRef(new DefendCommand[String] {
      def cmdKey = "a"
      def execute = Future.failed(err)
    })
    expectMsg(Failure(err))
  }

  test("cb gets called") {
    val err = new scala.IllegalArgumentException("foo")

    val cmd = new DefendCommand[String] with StaticFallback[String] {
      def cmdKey = "load-data"
      def execute = Future.failed(err)
      def fallback: String = "yey"
    }

    val cmd2 = new DefendCommand[String] with StaticFallback[String] {
      def cmdKey = "load-data"
      def execute = Future.apply{
        Thread.sleep(200)
        "foo"
      }
      def fallback: String = "yey"
    }

    val defender = AkkaDefender(system).defender
    defender.executeToRef(cmd)
    defender.executeToRef(cmd2)
    defender.executeToRef(cmd)
    expectMsg("yey")
  }
}

object DefenderTest {
  val config =
    ConfigFactory.parseString(
      """defender {
        |  command {
        |    load-data {
        |      circuit-breaker {
        |        max-failures = 2,
        |        call-timeout = 100 millis,
        |        reset-timeout = 2 minutes
        |      }
        |    }
        |  }
        |}""".stripMargin).withFallback(ConfigFactory.defaultReference());
}
