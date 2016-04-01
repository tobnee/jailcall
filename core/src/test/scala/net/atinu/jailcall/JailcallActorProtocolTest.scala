package net.atinu.jailcall

import akka.actor.{ Props, Actor }
import net.atinu.jailcall.JailcallActorProtocolTest.JailcallProtocolActor
import net.atinu.jailcall.JailcallActorProtocolTest.JailcallProtocolActor.{ JPerr, JailcallProtocolActorCmd }
import net.atinu.jailcall.util.ActorTest

import scala.concurrent.Future

class JailcallActorProtocolTest extends ActorTest("JailcallActorProtocolTest") {

  test("support standard cmd call plus transformation flow") {
    val jc = system.actorOf(Props(new JailcallProtocolActor(withFailure = false)))
    jc ! "getStuff"
    expectMsg("abcdef")
  }

  test("support standard cmd call plus error handling flow") {
    val jc = system.actorOf(Props(new JailcallProtocolActor(withFailure = true)))
    jc ! "getStuff"
    expectMsg(JPerr)
  }

}

object JailcallActorProtocolTest {

  class JailcallProtocolActor(withFailure: Boolean) extends Actor {
    val executor = Jailcall(context.system).executor
    val cmd = new JailcallProtocolActorCmd(withFailure)

    def receive = {
      case "getStuff" => executor.executeToRefWithContext(cmd)

      case JailcallExecutionResult.SuccessWithContext(res, originalSender) =>
        originalSender ! (res + "def")

      case JailcallExecutionResult.FailureWithContext(err, originalSender) =>
        originalSender ! err
    }
  }

  object JailcallProtocolActor {

    class JailcallProtocolActorCmd(withFailure: Boolean) extends ScalaFutureCommand[String] {
      def execute = if (withFailure) Future.failed(JPerr) else Future.successful("abc")
    }

    object JPerr extends RuntimeException("err")
  }
}
