package net.atinu.akka.defender.internal

import akka.actor.{ ActorRef, Actor, ActorLogging, Props }
import akka.pattern.{ CircuitBreakerOpenException, AkkaDefendCircuitBreaker }
import net.atinu.akka.defender._
import net.atinu.akka.defender.internal.AkkaDefendActor.{ CmdResources, FallbackAction }
import net.atinu.akka.defender.internal.AkkaDefendCmdKeyStatsActor.UpdateStats
import net.atinu.akka.defender.internal.DispatcherLookup.DispatcherHolder

import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success }

private[defender] class AkkaDefendActor extends Actor with ActorLogging {

  import akka.pattern.pipe
  import context.dispatcher

  val msgKeyToConf = collection.mutable.Map.empty[String, CmdResources]

  val rootConfig = context.system.settings.config
  val cbConfigBuilder = new MsgConfigBuilder(rootConfig)
  val cbBuilder = new CircuitBreakerBuilder(context.system.scheduler)
  val dispatcherLookup = new DispatcherLookup(context.system.dispatchers)

  def receive = {
    case msg: DefendExecution[_] =>
      callAsync(msg) pipeTo sender()

    case msg: SyncDefendExecution[_] =>
      callSync(msg) pipeTo sender()

    case FallbackAction(promise, msg: DefendExecution[_]) =>
      fallbackFuture(promise, callAsync(msg))

    case FallbackAction(promise, msg: SyncDefendExecution[_]) =>
      fallbackFuture(promise, callSync(msg))
  }

  def fallbackFuture(promise: Promise[Any], res: Future[_]) =
    promise.completeWith(res)

  def resourcesFor(msg: NamedCommand[_]): CmdResources = {
    msgKeyToConf.getOrElseUpdate(msg.cmdKey.name, buildCommandResources(msg.cmdKey))
  }

  def callSync(msg: SyncDefendExecution[_]): Future[Any] = {
    val resources = resourcesFor(msg)
    val dispatcherHolder = resources.dispatcherHolder
    if (dispatcherHolder.isDefault) {
      log.warning("Use of default dispatcher for command {}, consider using a custom one", msg.cmdKey)
    }
    execFlow(msg, resources, Future.apply(msg.execute)(dispatcherHolder.dispatcher))
  }

  def callAsync(msg: DefendExecution[_]): Future[Any] = {
    val resources = resourcesFor(msg)
    execFlow(msg, resources, msg.execute)
  }

  def execFlow(msg: NamedCommand[_], resources: CmdResources, execute: => Future[Any]): Future[Any] = {
    val startTime = System.currentTimeMillis();
    val exec = resources.circuitBreaker.withCircuitBreaker(execute)
    exec.onComplete {
      case Success(_) | Failure(_: CircuitBreakerOpenException) =>
        val t = System.currentTimeMillis() - startTime
        log.debug("time for cmd {} was {} ms", msg.cmdKey.name, t)
        resources.statsActor ! UpdateStats(t)
      case _ =>
    }
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

  private def buildCommandResources(msgKey: DefendCommandKey): CmdResources = {
    val statsActor = statsActorForKey(msgKey)
    val cfg = cbConfigBuilder.loadConfigForKey(msgKey)
    val cb = cbBuilder.createCb(msgKey, cfg.cbConfig, log)
    val dispatcherHolder = dispatcherLookup.lookupDispatcher(msgKey, cfg, log)
    val resources = CmdResources(cb, cfg, dispatcherHolder, statsActor)
    log.debug("initialize {} command resources with config {}", msgKey, cfg)
    resources
  }

  def statsActorForKey(cmdKey: DefendCommandKey) = {
    val cmdKeyName = cmdKey.name
    context.actorOf(AkkaDefendCmdKeyStatsActor.props, s"stats-$cmdKeyName")
  }
}

object AkkaDefendActor {

  private[internal] case class CmdResources(circuitBreaker: AkkaDefendCircuitBreaker, cfg: MsgConfig,
    dispatcherHolder: DispatcherHolder, statsActor: ActorRef)

  private[internal] case object GetKeyConfigs

  private[internal] case class FallbackAction(fallbackPromise: Promise[Any], cmd: NamedCommand[_])

  private[internal] case class CmdMetrics(name: DefendCommandKey)

  def props = Props(new AkkaDefendActor)

}
