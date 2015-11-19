package net.atinu.akka.defender.util

import akka.actor.ActorSystem
import akka.testkit.{DefaultTimeout, ImplicitSender, TestKit}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike, Matchers}

class ActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with Matchers with FunSuiteLike with BeforeAndAfterAll with ScalaFutures with DefaultTimeout {

  def this(name: String) = this(ActorSystem(name))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }
}
