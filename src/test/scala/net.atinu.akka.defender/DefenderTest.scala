package net.atinu.akka.defender

import akka.actor.Status.Failure
import akka.pattern.CircuitBreakerOpenException
import com.typesafe.config.ConfigFactory
import net.atinu.akka.defender.util.ActorTest

import scala.concurrent.Future

class DefenderTest extends ActorTest("DefenderTest", DefenderTest.config) {

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

  test("the cb gets called if the failure limit is hit") {
    val err = new scala.IllegalArgumentException("foo1")

    val cmd = new DefendCommand[String] {
      val cmdKey = "load-data"
      // check why apply will result in open cb but no cb exception
      def execute = Future.successful{
        Thread.sleep(500)
        "foo1"
      }
    }

    val defender = AkkaDefender(system).defender
    defender.executeToRef(cmd)
    defender.executeToRef(cmd)
    defender.executeToRef(cmd)
    expectMsgPF(){
      case Failure(e) =>
        e shouldBe a [scala.concurrent.TimeoutException]
    }
    expectMsgPF(){
      case Failure(e) => e shouldBe a [scala.concurrent.TimeoutException]
    }
    expectMsgPF(){
      case Failure(e) =>
        e shouldBe a [CircuitBreakerOpenException]
    }
  }

  test("A static fallback is used in case of failure") {
    val err = new scala.IllegalArgumentException("foo1")

    val cmd = new DefendCommand[String] with StaticFallback[String] {
      def cmdKey = "load-data"
      def execute = Future.failed(err)
      def fallback: String = "yey1"
    }

    val defender = AkkaDefender(system).defender
    defender.executeToRef(cmd)
    expectMsg("yey1")
  }

  test("A dynamic (cmd based) fallback is used in case of failure") {
    val err = new scala.IllegalArgumentException("foo2")

    val cmd1 = new DefendCommand[String] {
      def cmdKey = "load-data2"
      def execute = Future.successful("yes1")
    }

    val cmd2 = new DefendCommand[String] with CmdFallback[String] {
      def cmdKey = "load-data2"
      def execute = Future.failed(err)
      def fallback = cmd1
    }

    val defender = AkkaDefender(system).defender
    defender.executeToRef(cmd2)
    expectMsg("yes1")
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
        |        call-timeout = 200 millis,
        |        reset-timeout = 2 minutes
        |      }
        |    }
        |  }
        |}""".stripMargin);
}
