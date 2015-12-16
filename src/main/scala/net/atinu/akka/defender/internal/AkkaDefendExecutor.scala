package net.atinu.akka.defender.internal

import java.util.concurrent.TimeoutException

import akka.actor.{ Props, ActorLogging, ActorRef, Actor }
import akka.pattern.{ AkkaDefendCircuitBreaker, CircuitBreakerOpenException }
import net.atinu.akka.defender.internal.AkkaDefendCmdKeyStatsActor._
import net.atinu.akka.defender._
import net.atinu.akka.defender.internal.DispatcherLookup.DispatcherHolder

import scala.concurrent.{ Future, Promise }
import scala.util.{ Try, Failure, Success }

class AkkaDefendExecutor(val msgKey: DefendCommandKey, val circuitBreaker: AkkaDefendCircuitBreaker, val cfg: MsgConfig, val dispatcherHolder: DispatcherHolder) extends Actor with ActorLogging {

  import akka.pattern.pipe
  import context.dispatcher

  val statsActor: ActorRef = statsActorForKey(msgKey)
  var stats: Option[CmdKeyStatsSnapshot] = None

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

  def callSync(msg: SyncDefendExecution[_]): Future[Any] = {
    if (dispatcherHolder.isDefault) {
      log.warning("Use of default dispatcher for command {}, consider using a custom one", msg.cmdKey)
    }
    execFlow(msg, Future.apply(msg.execute)(dispatcherHolder.dispatcher))
  }

  def callAsync(msg: DefendExecution[_]): Future[Any] = {
    execFlow(msg, msg.execute)
  }

  def execFlow(msg: NamedCommand[_], execute: => Future[Any]): Future[Any] = {
    val exec = circuitBreaker.withCircuitBreaker(execute)
    updateCallStats(exec)
    val execOrFallback = fallback(msg, exec)
    execOrFallback
  }

  def updateCallStats(exec: Future[Any]): Unit = {
    val startTime = System.currentTimeMillis()
    exec.onComplete {
      case Success(_) =>
        val t = System.currentTimeMillis() - startTime
        statsActor ! ReportSuccCall(t)
      case Failure(v) =>
        val t = System.currentTimeMillis() - startTime
        val msg = v match {
          case e: TimeoutException => ReportTimeoutCall(t)
          case e: CircuitBreakerOpenException => ReportCircuitBreakerOpenCall
          case e => ReportErrorCall(t)
        }
        statsActor ! msg
    }
  }

  def fallback(msg: NamedCommand[_], exec: Future[Any]): Future[Any] = msg match {
    case static: StaticFallback[_] => exec.fallbackTo(Future.fromTry(Try(static.fallback)))
    case dynamic: CmdFallback[_] =>
      exec.fallbackTo {
        val fallbackPromise = Promise.apply[Any]()
        self ! FallbackAction(fallbackPromise, dynamic.fallback)
        fallbackPromise.future
      }
    case _ => exec
  }

  def statsActorForKey(cmdKey: DefendCommandKey) = {
    val cmdKeyName = cmdKey.name
    context.actorOf(AkkaDefendCmdKeyStatsActor.props(cmdKey), s"stats-$cmdKeyName")
  }

}

object AkkaDefendExecutor {

  def props(msgKey: DefendCommandKey, circuitBreaker: AkkaDefendCircuitBreaker, cfg: MsgConfig, dispatcherHolder: DispatcherHolder) =
    Props(new AkkaDefendExecutor(msgKey, circuitBreaker, cfg, dispatcherHolder))
}
