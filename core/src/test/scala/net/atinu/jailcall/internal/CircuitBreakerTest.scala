package net.atinu.jailcall.internal

import akka.actor.Status.Failure
import akka.dispatch.MessageDispatcher
import akka.pattern.CircuitBreakerOpenException
import net.atinu.jailcall.internal.DispatcherLookup.DispatcherHolder
import net.atinu.jailcall._
import net.atinu.jailcall.util.ActorTest
import net.atinu.jailcall.{ JailedCommand, CommandKey }
import net.atinu.jailcall.internal._

import scala.concurrent.Future

class CircuitBreakerTest extends ActorTest("CircuitBreakerTest") {
  import system.dispatcher

  import scala.concurrent.duration._

  test("no break if call limit is to low") {
    val thisCfg = cfg(rvt = 20, minFailurePercent = 50, callTimeoutMs = 500, resetTimeoutMs = 1000)
    val commandKey = CommandKey("nfoo")
    val ref = system.actorOf(
      JailedCommandExecutor.props(commandKey, thisCfg, dispatcherHolder)
    )

    ref ! CmdKeyStatsSnapshot(mean = 0, median = 0, p95Time = 0, p99Time = 0, meanDefendOverhead = 0, callStats =
      CallStats(succCount = 2, failureCount = 6, ciruitBreakerOpenCount = 0, timeoutCount = 1, badRequest = 0))
    ref ! JailedAction.now(JailedCommand.apply(key = "nfoo", Future.successful("na")))
    expectMsg("na")
  }

  test("no break if error count is to low") {
    val thisCfg = cfg(rvt = 20, minFailurePercent = 50, callTimeoutMs = 500, resetTimeoutMs = 1000)
    val commandKey = CommandKey("nfoo2")
    val ref = system.actorOf(
      JailedCommandExecutor.props(commandKey, thisCfg, dispatcherHolder)
    )

    ref ! CmdKeyStatsSnapshot(mean = 0, median = 0, p95Time = 0, p99Time = 0, meanDefendOverhead = 0, callStats =
      CallStats(succCount = 15, failureCount = 6, ciruitBreakerOpenCount = 0, timeoutCount = 1, badRequest = 0))
    ref ! JailedAction.now(JailedCommand.apply(key = "nfoo2", Future.successful("na")))
    expectMsg("na")
  }

  test("cb break") {
    val thisCfg = cfg(rvt = 20, minFailurePercent = 50, callTimeoutMs = 500, resetTimeoutMs = 1000)
    cbCalls(thisCfg)
    expectMsgPF() {
      case Failure(e) =>
        e shouldBe a[CircuitBreakerOpenException]
    }
  }

  test("no cb break if cb disabled") {
    val thisCfg = cfg(enabled = false, rvt = 20, minFailurePercent = 50, callTimeoutMs = 500, resetTimeoutMs = 1000)
    cbCalls(thisCfg)
    expectMsg("a")
  }

  def cbCalls(thisCfg: MsgConfig): Unit = {
    val commandKey = CommandKey("foo")
    val ref = system.actorOf(
      JailedCommandExecutor.props(commandKey, thisCfg, dispatcherHolder)
    )

    ref ! CmdKeyStatsSnapshot(mean = 0, median = 0, p95Time = 0, p99Time = 0, meanDefendOverhead = 0, callStats =
      CallStats(succCount = 2, failureCount = 17, ciruitBreakerOpenCount = 0, timeoutCount = 3, badRequest = 0))
    ref ! JailedAction.now(JailedCommand.apply(key = "foo", Future.successful("a")))
  }

  test("cb closed / halfopen / open circle") {
    val thisCfg = cfg(rvt = 20, minFailurePercent = 50, callTimeoutMs = 500, resetTimeoutMs = 500)
    val commandKey = CommandKey("foo2")
    val cmd = JailedAction.now(JailedCommand.apply(key = commandKey.name, Future.successful("b")))
    val ref = system.actorOf(
      JailedCommandExecutor.props(commandKey, thisCfg, dispatcherHolder)
    )
    ref ! CmdKeyStatsSnapshot(mean = 0, median = 0, p95Time = 0, p99Time = 0, meanDefendOverhead = 0, callStats =
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
    val thisCfg = cfg(rvt = 20, minFailurePercent = 50, callTimeoutMs = 500, resetTimeoutMs = 500)
    val commandKey = CommandKey("foo2")
    val failure = new IllegalStateException("naa")
    val cmd = JailedAction.now(JailedCommand.apply(key = commandKey.name, Future.failed(failure)))
    val ref = system.actorOf(
      JailedCommandExecutor.props(commandKey, thisCfg, dispatcherHolder)
    )
    ref ! CmdKeyStatsSnapshot(mean = 0, median = 0, p95Time = 0, p99Time = 0, meanDefendOverhead = 0, callStats =
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

  def cfg(enabled: Boolean = true, rvt: Int, minFailurePercent: Int, callTimeoutMs: Int, resetTimeoutMs: Int) =
    MsgConfig(CircuitBreakerConfig(enabled, requestVolumeThreshold = rvt, minFailurePercent = minFailurePercent, callTimeout = callTimeoutMs.millis, resetTimeout = resetTimeoutMs.millis), IsolationConfig.default, MetricsConfig(10 seconds, 10))

  def dispatcherHolder = DispatcherHolder(system.dispatcher.asInstanceOf[MessageDispatcher], true)
}