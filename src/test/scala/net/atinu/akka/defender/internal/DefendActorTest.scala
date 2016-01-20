package net.atinu.akka.defender.internal

import akka.actor.{ ActorRef, Props }
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import net.atinu.akka.defender._
import net.atinu.akka.defender.internal.JailcallRootActor.{ NoCmdExecutorForThatKey, CmdExecutorCreated, CreateCmdExecutor }
import net.atinu.akka.defender.internal.DispatcherLookup.DispatcherHolder
import net.atinu.akka.defender.util.ActorTest

class DefendActorTest extends ActorTest("DefendActorTest", DefendActorTest.config) {

  val ext = Jailcall(system)

  test("create an defend executor") {
    val defendRoot = system.actorOf(JailcallRootActor.props)
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
    val defendRoot = system.actorOf(Props(new JailcallRootActor {
      override def createExecutorActor(msgKey: CommandKey, cfg: MsgConfig, dispatcherHolder: DispatcherHolder) =
        probe.ref
    }))
    val msgKey = CommandKey("two")
    defendRoot ! CreateCmdExecutor.withKey(msgKey)
    expectMsgType[CmdExecutorCreated]
    defendRoot ! JailedAction.now(new SyncJailedExecution[String] {
      def cmdKey = msgKey
      def execute = ""
    })
    probe.expectMsgPF(hint = "forwarded msg") {
      case JailedAction(_, exec) =>
        exec.cmdKey should equal(msgKey)
        probe.lastSender should equal(self)
    }
  }

  test("report missing executor on forward") {
    val defendRoot = system.actorOf(JailcallRootActor.props)
    val msgKey = CommandKey("three")
    defendRoot ! JailedAction.now(new SyncJailedExecution[String] {
      def cmdKey = msgKey
      def execute = ""
    })
    expectMsg(NoCmdExecutorForThatKey(msgKey))
  }

}

object DefendActorTest {
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