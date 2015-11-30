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
      callAsync(msg) pipeTo sender()

    case msg: SyncDefendCommand[_] =>
      callSync(msg) pipeTo sender()

    case FallbackAction(promise, msg: DefendCommand[_]) =>
      fallbackFuture(promise, callAsync(msg))

    case FallbackAction(promise, msg: SyncDefendCommand[_]) =>
      fallbackFuture(promise, callSync(msg))
  }

  def fallbackFuture(promise: Promise[Any], res: Future[_]) =
    promise.completeWith(res)

  def resourcesFor(msg: NamedCommand[_]): CmdResources = {
    msgKeyToConf.getOrElseUpdate(msg.cmdKey, buildCommandResources(msg.cmdKey))
  }

  def callSync(msg: SyncDefendCommand[_]): Future[Any] = {
    val resources = resourcesFor(msg)
    val dispatcherHolder = resources.dispatcherHolder
    if(dispatcherHolder.isDefault) {
      log.warning("Use of default dispatcher for command {}, consider using a custom one", msg.cmdKey)
    }
    execFlow(msg, resources, Future.apply(msg.execute)(dispatcherHolder.dispatcher))
  }

  def callAsync(msg: DefendCommand[_]): Future[Any] = {
    val resources = resourcesFor(msg)
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

  private def buildCommandResources(msgKey: String): CmdResources = {
    val cfg = cbConfigBuilder.loadConfigForKey(msgKey)
    val cb = cbBuilder.createCb(msgKey, cfg.cbConfig, log)
    val dispatcherHolder = dispatcherLookup.lookupDispatcher(msgKey, cfg, log)
    val resources = CmdResources(cb, cfg, dispatcherHolder)
    log.debug("initialize {} command resources with config {}", msgKey, cfg)
    resources
  }
}

object AkkaDefendActor {

  private[internal] case class CmdResources(circuitBreaker: CircuitBreaker, cfg: MsgConfig, dispatcherHolder: DispatcherHolder)

  private[internal] case object GetKeyConfigs

  private[internal] case class FallbackAction(fallbackPromise: Promise[Any], cmd: NamedCommand[_])

  def props = Props(new AkkaDefendActor)
}
