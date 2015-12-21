package net.atinu.akka.defender

import akka.actor.Status.Failure
import com.typesafe.config.ConfigFactory
import net.atinu.akka.defender.util.ActorTest
import org.scalatest.concurrent.Futures

import scala.concurrent.Future

class DefenderTest extends ActorTest("DefenderTest", DefenderTest.config) with Futures {

  test("a command is executed and returned to an actor") {
    AkkaDefender(system).defender.executeToRef(new AsyncDefendExecution[String] {
      def cmdKey = DefendCommandKey("a")
      def execute: Future[String] = Future.successful("succFuture")
    })
    expectMsg("succFuture")
  }

  test("a command is executed and returned to a future") {
    val res = AkkaDefender(system).defender.executeToFuture(new AsyncDefendExecution[String] {
      def cmdKey = DefendCommandKey("af")
      def execute: Future[String] = Future.successful("succFuture")
    })
    whenReady(res) { v =>
      v should equal("succFuture")
    }
  }

  test("a command fails and is returned to an actor") {
    val err = new scala.IllegalArgumentException("foo")
    AkkaDefender(system).defender.executeToRef(new AsyncDefendExecution[String] {
      def cmdKey = DefendCommandKey("a")
      def execute = Future.failed(err)
    })
    expectMsg(Failure(err))
  }

  test("a command fails and is returned to a future") {
    val err = new scala.IllegalArgumentException("foo")
    val res = AkkaDefender(system).defender.executeToFuture(new AsyncDefendExecution[String] {
      def cmdKey = DefendCommandKey("af")
      def execute = Future.failed(err)
    })
    whenReady(res.failed) { v =>
      v should equal(err)
    }
  }

  test("A static fallback is used in case of failure") {
    val err = new scala.IllegalArgumentException("foo1")

    val cmd = new AsyncDefendExecution[String] with StaticFallback[String] {
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
        |        reset-timeout = 1 seconds
        |      }
        |    }
        |    load-data-sync {
        |      circuit-breaker {
        |        max-failures = 2,
        |        call-timeout = 200 millis,
        |        reset-timeout = 1 seconds
        |      }
        |      dispatcher = sync-call-dispatcher
        |    }
        |  }
        |}
        |sync-call-dispatcher {
        |  executor = "thread-pool-executor"
        |  type = PinnedDispatcher
        |}""".stripMargin
    );
}
