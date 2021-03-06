package net.atinu.jailcall

import akka.actor.Status
import akka.actor.Status.Failure
import net.atinu.jailcall.util.ActorTest
import org.scalatest.concurrent.Futures

import scala.concurrent.Future

class JailcallTest extends ActorTest("JailcallTest") with Futures {

  test("a command is executed and returned to an actor") {
    Jailcall(system).executor.executeToRef(new ScalaFutureExecution[String] {
      def cmdKey = CommandKey("a")
      def execute: Future[String] = Future.successful("succFuture")
    })
    expectResult("succFuture")
  }

  test("a command is executed and returned to a future") {
    val res = Jailcall(system).executor.executeToFuture(new ScalaFutureExecution[String] {
      def cmdKey = CommandKey("af")
      def execute: Future[String] = Future.successful("succFuture")
    })
    whenReady(res) { v =>
      v should equal("succFuture")
    }
  }

  test("a command fails and is returned to an actor") {
    val err = new scala.IllegalArgumentException("foo")
    Jailcall(system).executor.executeToRef(new ScalaFutureExecution[String] {
      def cmdKey = CommandKey("a")
      def execute = Future.failed(err)
    })
    expectMsg(Failure(JailcallExecutionException(err, None)))
  }

  test("a command fails and is returned to a future") {
    val err = new scala.IllegalArgumentException("foo")
    val res = Jailcall(system).executor.executeToFuture(new ScalaFutureExecution[String] {
      def cmdKey = CommandKey("af")
      def execute = Future.failed(err)
    })
    whenReady(res.failed) { v =>
      v should equal(err)
    }
  }

  test("No fallback is selected in case of a bad request error") {
    val err = BadRequestException("bad request")

    val cmd = new ScalaFutureExecution[String] with CmdFallback {
      def cmdKey = "load-data-3".asKey
      def execute = Future.failed(err)
      def fallback = ScalaFutureCommand.apply("load-fallback", Future.successful("works"))
    }

    val defender = Jailcall(system).executor
    defender.executeToRef(cmd)
    expectMsg(Status.Failure(JailcallExecutionException(err)))
  }

  test("No fallback is selected in case of a bad request") {
    Jailcall(system).executor.executeToRef(new ScalaFutureExecution[String] with CmdFallback {
      def cmdKey = CommandKey("load-data-3")
      def execute: Future[String] = {
        import system.dispatcher
        ScalaFutureExecution.filterBadRequest(Future.successful("succFuture"))(_ == "succFuture")
      }
      def fallback = ScalaFutureCommand.apply("load-fallback", Future.successful("works"))
    })
    expectMsgPF(hint = "a DefendBadRequestException") {
      case Status.Failure(JailcallExecutionException(e, _)) =>
        e shouldBe a[BadRequestException]
    }
  }

  test("No fallback is selected in case of a bad request result categorization") {
    Jailcall(system).executor.executeToRef(new ScalaFutureExecution[String] with CmdFallback {
      def cmdKey = CommandKey("load-data-3")
      def execute: Future[String] = {
        import system.dispatcher
        ScalaFutureExecution.categorizeResult(Future.successful("succFuture")) {
          case "succFuture" => IsBadRequest
        }
      }
      def fallback = ScalaFutureCommand.apply("load-fallback", Future.successful("works"))
    })
    expectMsgPF(hint = "a DefendBadRequestException") {
      case Status.Failure(JailcallExecutionException(e, _)) =>
        e shouldBe a[BadRequestException]
    }
  }

  test("A dynamic (cmd based) fallback is used in case of failure") {
    val err = new scala.IllegalArgumentException("foo2")

    val cmd1 = ScalaFutureCommand.apply("load-data2", exec = Future.successful("yes1"))

    val cmd2 = ScalaFutureCommand.withCmdFallback("load-data2", exec = Future.failed(err), fb = cmd1)

    val defender = Jailcall(system).executor
    defender.executeToRef(cmd2)
    expectResult("yes1")
  }

  test("A sync command gets called") {
    val err = new scala.IllegalArgumentException("foo2")

    val cmd1 = new BlockingExecution[String] {
      def cmdKey = "load-data-sync-2".asKey
      def execute = "yes2"
    }

    val defender = Jailcall(system).executor
    defender.executeToRef(cmd1)
    expectResult("yes2")
  }

  test("A dynamic (cmd based) fallback is used in case of sync cmd failure") {
    val err = new scala.IllegalArgumentException("foo2")

    val cmd1 = new BlockingExecution[String] {
      def cmdKey = "load-data2".asKey
      def execute = "yes3"
    }

    val cmd2 = new BlockingExecution[String] with CmdFallback {
      def cmdKey = "load-data2".asKey
      def execute = throw err
      def fallback: BlockingExecution[String] = cmd1
    }

    val defender = Jailcall(system).executor
    defender.executeToRef(cmd2)
    expectResult("yes3")
  }

  def expectResult[T](result: T) = expectMsg(JailcallExecutionResult(result))
}