package net.atinu.akka.defender

import akka.actor.ActorSystem
import akka.actor.Status.Failure
import akka.testkit.{DefaultTimeout, ImplicitSender, TestKit}
import net.atinu.akka.defender.util.ActorTest
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike, Matchers}

import scala.concurrent.Future

class DefenderTest extends ActorTest("DefenderTest") {

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

}
