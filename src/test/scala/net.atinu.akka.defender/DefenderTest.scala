package net.atinu.akka.defender

import akka.actor.Status.Failure
import akka.pattern.CircuitBreakerOpenException
import com.typesafe.config.ConfigFactory
import net.atinu.akka.defender.util.ActorTest

import scala.concurrent.Future

class DefenderTest extends ActorTest("DefenderTest", DefenderTest.config) {

  test("the result of a future is executed and returned") {
    AkkaDefender(system).defender.executeToRef(new DefendExecution[String] {
      def cmdKey = DefendCommandKey("a")
      def execute: Future[String] = Future.successful("succFuture")
    })
    expectMsg("succFuture")
  }

  test("the result of a failed future is a failure message") {
    val err = new scala.IllegalArgumentException("foo")
    AkkaDefender(system).defender.executeToRef(new DefendExecution[String] {
      def cmdKey = DefendCommandKey("a")
      def execute = Future.failed(err)
    })
    expectMsg(Failure(err))
  }

  test("the cb gets called if the failure limit is hit") {
    val cmd = new DefendExecution[String] {
      val cmdKey = "load-data".asKey
      // check why apply will result in open cb but no cb exception
      import scala.concurrent.ExecutionContext.Implicits.global
      def execute = Future {
        Thread.sleep(2000)
        "foo1"
      }
    }

    val defender = AkkaDefender(system).defender
    defender.executeToRef(cmd)
    expectMsgPF(){
      case Failure(e) =>
        e shouldBe a [scala.concurrent.TimeoutException]
    }

    defender.executeToRef(cmd)
    expectMsgPF(){
      case Failure(e) => e shouldBe a [scala.concurrent.TimeoutException]
    }

    defender.executeToRef(cmd)
    expectMsgPF(){
      case Failure(e) =>
        e shouldBe a [CircuitBreakerOpenException]
    }

    defender.executeToRef(cmd)
    expectMsgPF(){
      case Failure(e) =>
        e shouldBe a [CircuitBreakerOpenException]
    }
  }

  test("A static fallback is used in case of failure") {
    val err = new scala.IllegalArgumentException("foo1")

    val cmd = new DefendExecution[String] with StaticFallback[String] {
      def cmdKey = "load-data-0".asKey
      def execute = Future.failed(err)
      def fallback: String = "yey1"
    }

    val defender = AkkaDefender(system).defender
    defender.executeToRef(cmd)
    expectMsg("yey1")
  }

  test("A dynamic (cmd based) fallback is used in case of failure") {
    val err = new scala.IllegalArgumentException("foo2")

    val cmd1 = DefendCommand.apply("load-data2", exec = Future.successful("yes1"))

    val cmd2 = DefendCommand.applyWithCmdFallback("load-data2", exec = Future.failed(err), fb = cmd1)

    val defender = AkkaDefender(system).defender
    defender.executeToRef(cmd2)
    expectMsg("yes1")
  }

  test("A sync command gets called") {
    val err = new scala.IllegalArgumentException("foo2")

    val cmd1 = new SyncDefendExecution[String] {
      def cmdKey = "load-data2".asKey
      def execute = "yes2"
    }

    val defender = AkkaDefender(system).defender
    defender.executeToRef(cmd1)
    expectMsg("yes2")
  }

  test("A dynamic (cmd based) fallback is used in case of sync cmd failure") {
    val err = new scala.IllegalArgumentException("foo2")

    val cmd1 = new SyncDefendExecution[String] {
      def cmdKey = "load-data2".asKey
      def execute = "yes3"
    }

    val cmd2 = new SyncDefendExecution[String] with CmdFallback[String] {
      def cmdKey = "load-data2".asKey
      def execute = throw err
      def fallback = cmd1
    }

    val defender = AkkaDefender(system).defender
    defender.executeToRef(cmd2)
    expectMsg("yes3")
  }

  test("the cb gets called if the failure limit is hit (sync)") {
    val cmd = new SyncDefendExecution[String] {
      val cmdKey = "load-data-sync".asKey
      // check why apply will result in open cb but no cb exception
      def execute = {
        Thread.sleep(1000)
        "foo1"
      }
    }

    val defender = AkkaDefender(system).defender
    defender.executeToRef(cmd)
    expectMsgPF(){
      case Failure(e) =>
        e shouldBe a [scala.concurrent.TimeoutException]
    }

    defender.executeToRef(cmd)
    expectMsgPF(){
      case Failure(e) => e shouldBe a [scala.concurrent.TimeoutException]
    }
    defender.executeToRef(cmd)
    expectMsgPF(){
      case Failure(e) =>
        e shouldBe a [CircuitBreakerOpenException]
    }
    expectNoMsg()
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
        |    load-data-sync {
        |      circuit-breaker {
        |        max-failures = 2,
        |        call-timeout = 200 millis,
        |        reset-timeout = 2 minutes
        |      }
        |      dispatcher = sync-call-dispatcher
        |    }
        |  }
        |}
        |sync-call-dispatcher {
        |  executor = "thread-pool-executor"
        |  type = PinnedDispatcher
        |}""".stripMargin);
}
