package net.atinu.akka.defender.internal

import akka.actor.Status.Failure
import akka.dispatch.MessageDispatcher
import akka.pattern.CircuitBreakerOpenException
import net.atinu.akka.defender.internal.DispatcherLookup.DispatcherHolder
import net.atinu.akka.defender.internal.util.CallStats
import net.atinu.akka.defender.util.ActorTest
import net.atinu.akka.defender.{ DefendCommand, DefendCommandKey, DefenderTest }

import scala.concurrent.Future

class CircuitBreakerTest extends ActorTest("DefenderTest", DefenderTest.config) {
  import system.dispatcher

  import scala.concurrent.duration._

  test("cb break") {
    val thisCfg = cfg(2, 500, 1000)
    val commandKey = DefendCommandKey("foo")
    val ref = system.actorOf(
      AkkaDefendExecutor.props(commandKey, thisCfg, dispatcherHolder)
    )

    ref ! CmdKeyStatsSnapshot(0, 0, 0, CallStats(0, 0, 0, 3))
    ref ! DefendCommand.apply(key = "foo", Future.successful("a"))
    expectMsgPF() {
      case Failure(e) =>
        e shouldBe a[CircuitBreakerOpenException]
    }
  }

  test("cb closed / halfopen / open circle") {
    val thisCfg = cfg(2, 500, 500)
    val commandKey = DefendCommandKey("foo2")
    val cmd = DefendCommand.apply(key = commandKey.name, Future.successful("b"))
    val ref = system.actorOf(
      AkkaDefendExecutor.props(commandKey, thisCfg, dispatcherHolder)
    )
    ref ! CmdKeyStatsSnapshot(0, 0, 0, CallStats(0, 0, 0, 3))
    ref ! cmd
    expectMsgPF() {
      case Failure(e) =>
        e shouldBe a[CircuitBreakerOpenException]
    }
    system.scheduler.scheduleOnce(700.millis, ref, cmd)
    expectMsg("b")
    ref ! cmd
    expectMsg("b")
  }

  test("cb closed / halfopen / closed circle") {
    val thisCfg = cfg(2, 500, 500)
    val commandKey = DefendCommandKey("foo2")
    val failure = new IllegalStateException("naa")
    val cmd = DefendCommand.apply(key = commandKey.name, Future.failed(failure))
    val ref = system.actorOf(
      AkkaDefendExecutor.props(commandKey, thisCfg, dispatcherHolder)
    )
    ref ! CmdKeyStatsSnapshot(0, 0, 0, CallStats(0, 0, 0, 3))
    ref ! cmd
    expectMsgPF() {
      case Failure(e) =>
        e shouldBe a[CircuitBreakerOpenException]
    }
    system.scheduler.scheduleOnce(700.millis, ref, cmd)
    expectMsgPF() {
      case Failure(e) =>
        e should equal(failure)
    }
    ref ! cmd
    expectMsgPF() {
      case Failure(e) =>
        e shouldBe a[CircuitBreakerOpenException]
    }
  }

  def cfg(maxFailures: Int, callTimeoutMs: Int, resetTimeoutMs: Int) =
    MsgConfig(CircuitBreakerConfig(maxFailures, callTimeoutMs.millis, resetTimeoutMs.millis), None)

  def dispatcherHolder = DispatcherHolder(system.dispatcher.asInstanceOf[MessageDispatcher], true)
}
