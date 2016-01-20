package net.atinu.jailcall.util

import akka.actor.ActorSystem
import akka.testkit.{ DefaultTimeout, ImplicitSender, TestKit }
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ BeforeAndAfterAll, FunSuiteLike, Matchers }

class ActorTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
    with Matchers with FunSuiteLike with BeforeAndAfterAll with ScalaFutures with DefaultTimeout {

  def this(name: String, cfg: Config) = this(ActorSystem(
    name,
    cfg.withFallback(ConfigFactory.load())
  ))

  def this(name: String) = this(name, ConfigFactory.load())

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

}
