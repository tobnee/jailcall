package net.atinu.akka.defender.internal

import akka.actor.{Actor, ActorLogging, Props}
import akka.pattern.CircuitBreaker
import net.atinu.akka.defender.internal.AkkaDefendActor.{FallbackAction, CmdResources}
import net.atinu.akka.defender._
import net.atinu.akka.defender.internal.DispatcherLookup.DispatcherHolder

import scala.concurrent.{Future, Promise}

private[defender] class AkkaDefendActor extends Actor with ActorLogging {

  import akka.pattern.pipe
  import context.dispatcher

  val msgKeyToConf = collection.mutable.Map.empty[String, CmdResources]

  val rootConfig = context.system.settings.config
  val cbConfigBuilder = new MsgConfigBuilder(rootConfig)
  val cbBuilder = new CircuitBreakerBuilder(context.system.scheduler)
  val dispatcherLookup = new DispatcherLookup(context.system.dispatchers)

  def receive = {
    case msg: DefendCommand[_] =>
      val resources = resourcesFor(msg)
      callAsync(msg, resources) pipeTo sender()

    case msg: SyncDefendCommand[_] =>
      val resources = resourcesFor(msg)
      callSync(msg, resources) pipeTo sender()

    case FallbackAction(promise, msg) =>
      val resources = resourcesFor(msg)
      promise.completeWith(callAsync(msg, resources))
  }

  def resourcesFor(msg: NamedCommand[_]): CmdResources = {
    msgKeyToConf.getOrElseUpdate(msg.cmdKey, buildCommandResources(msg.cmdKey))
  }

  def callSync(msg: SyncDefendCommand[_], resources: CmdResources): Future[Any] = {
    val dispatcherHolder = resources.dispatcherHolder
    if(dispatcherHolder.isDefault) {
      log.warning("Use of default dispatcher for command {}, consider using a custom one", msg.cmdKey)
    }
    execFlow(msg, resources, Future.apply(msg.execute)(dispatcherHolder.dispatcher))
  }

  def callAsync(msg: DefendCommand[_], resources: CmdResources): Future[Any] = {
    execFlow(msg, resources, msg.execute)
  }

  def execFlow(msg: NamedCommand[_], resources: CmdResources, execute: => Future[Any]): Future[Any] = {
    val exec = resources.circuitBreaker.withCircuitBreaker(execute)
    val execOrFallback = fallback(msg, exec)
    execOrFallback
  }

  def fallback(msg: NamedCommand[_], exec: Future[Any]): Future[Any] = msg match {
    case static: StaticFallback[_] => exec.fallbackTo(Future.successful(static.fallback))
    case dynamic: CmdFallback[_] =>
      exec.fallbackTo {
        val fallbackPromise = Promise.apply[Any]()
        self ! FallbackAction(fallbackPromise, dynamic.fallback)
        fallbackPromise.future
      }
    case _ => exec
  }

  private def buildCommandResources(msgKey: String) = {
    val cfg = cbConfigBuilder.loadConfigForKey(msgKey)
    val cb = cbBuilder.createCb(msgKey, cfg.cbConfig, log)
    val dispatcherHolder = dispatcherLookup.lookupDispatcher(msgKey)
    val resources = CmdResources(cb, cfg, dispatcherHolder)
    log.debug("initialize {} command resources with config {}", msgKey, cfg)
    resources
  }
}

object AkkaDefendActor {

  private[internal] case class CmdResources(circuitBreaker: CircuitBreaker, cfg: MsgConfig, dispatcherHolder: DispatcherHolder)

  private[internal] case object GetKeyConfigs

  private[internal] case class FallbackAction(fallbackPromise: Promise[Any], cmd: DefendCommand[_])

  def props = Props(new AkkaDefendActor)
}
