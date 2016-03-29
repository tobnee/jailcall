package net.atinu.jailcall.internal

import akka.actor.Props
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import net.atinu.jailcall._
import net.atinu.jailcall.internal.DispatcherLookup.DispatcherHolder
import net.atinu.jailcall.internal.JailcallRootActor.{ CmdExecutorCreated, CreateCmdExecutor, NoCmdExecutorForThatKey }
import net.atinu.jailcall.util.ActorTest

class JailcallRootActorTest extends ActorTest("JailcallRootActorTest", JailcallRootActorTest.config) {

  val ext = Jailcall(system)
  val metricsBus = new MetricsEventBus

  test("create an defend executor") {
    val defendRoot = system.actorOf(JailcallRootActor.props(metricsBus))
    val msgKey = CommandKey("one")
    defendRoot ! CreateCmdExecutor.withKey(msgKey)
    val ref = expectMsgPF(hint = "creation messsage") {
      case CmdExecutorCreated(key, ref) =>
        key should equal(msgKey)
        ref
    }

    defendRoot ! CreateCmdExecutor.withKey(msgKey)
    expectMsgPF(hint = "idempotent creation") {
      case CmdExecutorCreated(key, ref2) =>
        key should equal(msgKey)
        ref2 should equal(ref)
    }
  }

  test("forward defend actions to executor") {
    val probe = TestProbe()
    val defendRoot = system.actorOf(Props(new JailcallRootActor(metricsBus) {
      override def createExecutorActor(msgKey: CommandKey, cfg: MsgConfig, dispatcherHolder: DispatcherHolder) =
        probe.ref
    }))
    val msgKey = CommandKey("two")
    defendRoot ! CreateCmdExecutor.withKey(msgKey)
    expectMsgType[CmdExecutorCreated]
    defendRoot ! JailedAction.now(new BlockingExecution[String] {
      def cmdKey = msgKey
      def execute = ""
    })
    probe.expectMsgPF(hint = "forwarded msg") {
      case JailedAction(_, _, exec) =>
        exec.cmdKey should equal(msgKey)
        probe.lastSender should equal(self)
    }
  }

  test("report missing executor on forward") {
    val defendRoot = system.actorOf(JailcallRootActor.props(metricsBus))
    val msgKey = CommandKey("three")
    defendRoot ! JailedAction.now(new BlockingExecution[String] {
      def cmdKey = msgKey
      def execute = ""
    })
    expectMsg(NoCmdExecutorForThatKey(msgKey))
  }

}

object JailcallRootActorTest {
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
        |   }
        |}""".stripMargin
    );
}