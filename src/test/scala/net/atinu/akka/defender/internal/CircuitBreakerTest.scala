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

  test("no break if call limit is to low") {
    val thisCfg = cfg(20, 50, 500, 1000)
    val commandKey = DefendCommandKey("nfoo")
    val ref = system.actorOf(
      AkkaDefendExecutor.props(commandKey, thisCfg, dispatcherHolder)
    )

    ref ! CmdKeyStatsSnapshot(median = 0, p95Time = 0, p99Time = 0, callStats =
      CallStats(succCount = 2, failureCount = 6, ciruitBreakerOpenCount = 0, timeoutCount = 1, badRequest = 0))
    ref ! DefendCommand.apply(key = "nfoo", Future.successful("na"))
    expectMsg("na")
  }

  test("no break if error count is to low") {
    val thisCfg = cfg(20, 50, 500, 1000)
    val commandKey = DefendCommandKey("nfoo2")
    val ref = system.actorOf(
      AkkaDefendExecutor.props(commandKey, thisCfg, dispatcherHolder)
    )

    ref ! CmdKeyStatsSnapshot(median = 0, p95Time = 0, p99Time = 0, callStats =
      CallStats(succCount = 15, failureCount = 6, ciruitBreakerOpenCount = 0, timeoutCount = 1, badRequest = 0))
    ref ! DefendCommand.apply(key = "nfoo2", Future.successful("na"))
    expectMsg("na")
  }

  test("cb break") {
    val thisCfg = cfg(20, 50, 500, 1000)
    val commandKey = DefendCommandKey("foo")
    val ref = system.actorOf(
      AkkaDefendExecutor.props(commandKey, thisCfg, dispatcherHolder)
    )

    ref ! CmdKeyStatsSnapshot(median = 0, p95Time = 0, p99Time = 0, callStats =
      CallStats(succCount = 2, failureCount = 17, ciruitBreakerOpenCount = 0, timeoutCount = 3, badRequest = 0))
    ref ! DefendCommand.apply(key = "foo", Future.successful("a"))
    expectMsgPF() {
      case Failure(e) =>
        e shouldBe a[CircuitBreakerOpenException]
    }
  }

  test("cb closed / halfopen / open circle") {
    val thisCfg = cfg(20, 50, 500, 500)
    val commandKey = DefendCommandKey("foo2")
    val cmd = DefendCommand.apply(key = commandKey.name, Future.successful("b"))
    val ref = system.actorOf(
      AkkaDefendExecutor.props(commandKey, thisCfg, dispatcherHolder)
    )
    ref ! CmdKeyStatsSnapshot(median = 0, p95Time = 0, p99Time = 0, callStats =
      CallStats(succCount = 2, failureCount = 17, ciruitBreakerOpenCount = 0, timeoutCount = 3, badRequest = 0))
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
    val thisCfg = cfg(20, 50, 500, 500)
    val commandKey = DefendCommandKey("foo2")
    val failure = new IllegalStateException("naa")
    val cmd = DefendCommand.apply(key = commandKey.name, Future.failed(failure))
    val ref = system.actorOf(
      AkkaDefendExecutor.props(commandKey, thisCfg, dispatcherHolder)
    )
    ref ! CmdKeyStatsSnapshot(median = 0, p95Time = 0, p99Time = 0, callStats =
      CallStats(succCount = 2, failureCount = 17, ciruitBreakerOpenCount = 0, timeoutCount = 3, badRequest = 0))
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

  def cfg(rvt: Int, minFailurePercent: Int, callTimeoutMs: Int, resetTimeoutMs: Int) =
    MsgConfig(CircuitBreakerConfig(requestVolumeThreshold = rvt, minFailurePercent = minFailurePercent, callTimeout = callTimeoutMs.millis, resetTimeout = resetTimeoutMs.millis), None)

  def dispatcherHolder = DispatcherHolder(system.dispatcher.asInstanceOf[MessageDispatcher], true)
}
