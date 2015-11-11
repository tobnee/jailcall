package net.atinu.akka.defender

import akka.actor.ActorSystem
import akka.testkit.{DefaultTimeout, ImplicitSender, TestKit}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike, Matchers}

import scala.concurrent.Future

class DefenderTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with Matchers with FunSuiteLike with BeforeAndAfterAll with ScalaFutures with DefaultTimeout {

  import scala.concurrent.duration._

  def this() = this(ActorSystem("MySpec"))

  test("the result of a future is executed and returned") {
    AkkaDefender(system).defender.executeToRef(new DefendCommand[String] {
      def cmdKey = "a"
      def execute: Future[String] = Future.successful("succFuture")
    })
    expectMsg("succFuture")
  }

  test("the result of a future is executed and returned") {
    AkkaDefender(system).defender.executeToRef(new DefendCommand[String] {
      def cmdKey = "a"
      def execute = Future.successful("succFuture")
    })
    expectMsg("succFuture")
  }




  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }
}
