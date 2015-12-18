package net.atinu.akka.defender.internal

import java.util.concurrent.TimeoutException

import akka.actor._
import akka.defend.DefendBatchingExecutor
import akka.pattern.CircuitBreakerOpenException
import net.atinu.akka.defender._
import net.atinu.akka.defender.internal.AkkaDefendCmdKeyStatsActor._
import net.atinu.akka.defender.internal.AkkaDefendExecutor.{ ClosingCircuitBreakerSucceed, ClosingCircuitBreakerFailed, TryCloseCircuitBreaker }
import net.atinu.akka.defender.internal.DispatcherLookup.DispatcherHolder
import net.atinu.akka.defender.internal.util.CallStats

import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.control.{ NoStackTrace, NonFatal }
import scala.util.{ Failure, Success, Try }
import scala.concurrent.duration._

class AkkaDefendExecutor(val msgKey: DefendCommandKey, val cfg: MsgConfig, val dispatcherHolder: DispatcherHolder)
    extends Actor with ActorLogging with Stash {

  import akka.pattern.pipe

  val statsActor: ActorRef = statsActorForKey(msgKey)
  var stats = CmdKeyStatsSnapshot.initial

  def receive = receiveClosed(isHalfOpen = false)

  def receiveClosed(isHalfOpen: Boolean): Receive = {
    case msg: DefendExecution[_] =>
      import context.dispatcher
      callAsync(msg, isHalfOpen) pipeTo sender()

    case msg: SyncDefendExecution[_] =>
      import context.dispatcher
      callSync(msg, isHalfOpen) pipeTo sender()

    case FallbackAction(promise, msg: DefendExecution[_]) =>
      fallbackFuture(promise, callAsync(msg, isHalfOpen))

    case FallbackAction(promise, msg: SyncDefendExecution[_]) =>
      fallbackFuture(promise, callSync(msg, isHalfOpen))

    case snap: CmdKeyStatsSnapshot =>
      stats = snap
      openCircuitBreakerOnFailureLimit(snap.callStats)
  }

  def receiveOpen(end: Long): Receive = {
    case TryCloseCircuitBreaker =>
      context.become(receiveClosed(isHalfOpen = true))

    case msg: DefendExecution[_] =>
      import context.dispatcher
      callBreak(calcCircuitBreakerOpenRemaining(end)) pipeTo sender()

    case msg: SyncDefendExecution[_] =>
      import context.dispatcher
      callBreak(calcCircuitBreakerOpenRemaining(end)) pipeTo sender()

    case FallbackAction(promise, _) =>
      promise.completeWith(callBreak(calcCircuitBreakerOpenRemaining(end)))

    case snap: CmdKeyStatsSnapshot => {
      stats = snap
    }
  }

  def receiveHalfOpen: Receive = {
    case ClosingCircuitBreakerFailed =>
      log.debug("circuit closed test call failed for {}", msgKey.name)
      openCircuitBreaker()
      unstashAll()
    case ClosingCircuitBreakerSucceed =>
      context.become(receiveClosed(isHalfOpen = false))
      unstashAll()
    case _ =>
      stash()
  }

  def fallbackFuture(promise: Promise[Any], res: Future[_]) =
    promise.completeWith(res)

  def callSync(msg: SyncDefendExecution[_], breakOnSingleFailure: Boolean): Future[Any] = {
    if (dispatcherHolder.isDefault) {
      log.warning("Use of default dispatcher for command {}, consider using a custom one", msg.cmdKey)
    }
    execFlow(msg, breakOnSingleFailure, Future.apply(msg.execute)(dispatcherHolder.dispatcher))
  }

  def callAsync(msg: DefendExecution[_], breakOnSingleFailure: Boolean): Future[Any] = {
    execFlow(msg, breakOnSingleFailure, msg.execute)
  }

  def execFlow(msg: NamedCommand[_], breakOnSingleFailure: Boolean, execute: => Future[Any]): Future[Any] = {
    if (breakOnSingleFailure) waitForApproval()
    val (startTime, exec) = callThrough(execute)
    updateCallStats(startTime, exec)
    if (breakOnSingleFailure) checkForSingleFailure(exec)
    fallbackIfDefined(msg, exec)
  }

  // adapted based on the akka circuit breaker implementation
  def callThrough[T](body: ⇒ Future[T]): (Long, Future[T]) = {

    def materialize[U](value: ⇒ Future[U]): (Long, Future[U]) = {
      var time = 0L
      try {
        time = System.currentTimeMillis()
        (time, value)
      } catch { case NonFatal(t) ⇒ (time, Future.failed(t)) }
    }

    if (cfg.cbConfig.callTimeout == Duration.Zero) {
      materialize(body)
    } else {
      val p = Promise[T]()

      implicit val ec = AkkaDefendExecutor.sameThreadExecutionContext

      val timeout = context.system.scheduler.scheduleOnce(cfg.cbConfig.callTimeout) {
        p tryCompleteWith AkkaDefendExecutor.timeoutFuture
      }

      val (t, f) = materialize(body)
      f.onComplete { result ⇒
        p tryComplete result
        timeout.cancel
      }
      (t, p.future)
    }
  }

  def callBreak[T](remainingDuration: FiniteDuration): Future[T] =
    Promise.failed[T](new CircuitBreakerOpenException(remainingDuration)).future

  def fallbackIfDefined(msg: NamedCommand[_], exec: Future[Any]): Future[Any] = msg match {
    case static: StaticFallback[_] => exec.fallbackTo(Future.fromTry(Try(static.fallback)))
    case dynamic: CmdFallback[_] =>
      exec.fallbackTo {
        val fallbackPromise = Promise.apply[Any]()
        self ! FallbackAction(fallbackPromise, dynamic.fallback)
        fallbackPromise.future
      }
    case _ => exec
  }

  def updateCallStats(startTime: Long, exec: Future[Any]): Unit = {
    import context.dispatcher
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

  def statsActorForKey(cmdKey: DefendCommandKey) = {
    val cmdKeyName = cmdKey.name
    context.actorOf(AkkaDefendCmdKeyStatsActor.props(cmdKey), s"stats-$cmdKeyName")
  }

  def waitForApproval() = {
    log.debug("{} become half open", msgKey.name)
    context.become(receiveHalfOpen)
  }

  def checkForSingleFailure(exec: Future[Any]): Unit = {
    import context.dispatcher
    exec.onComplete {
      case Success(v) =>
        self ! ClosingCircuitBreakerSucceed
      case Failure(e) =>
        self ! ClosingCircuitBreakerFailed
    }
  }

  def openCircuitBreakerOnFailureLimit(callStats: CallStats): Unit = {
    // rolling call count has to be significant enough
    // to consider opening the CB
    if (callStats.totalCount >= 20) {
      if (callStats.errorPercent >= 50) {
        openCircuitBreaker()
      }
    }
  }

  def openCircuitBreaker(): Unit = {
    import context.dispatcher
    log.debug("{} open circuit breaker for {}", msgKey.name, cfg.cbConfig.resetTimeout)
    context.system.scheduler.scheduleOnce(cfg.cbConfig.resetTimeout, self, TryCloseCircuitBreaker)
    context.become(receiveOpen(System.currentTimeMillis() + cfg.cbConfig.resetTimeout.toMillis))
  }

  def calcCircuitBreakerOpenRemaining(end: Long) = {
    val r = end - System.currentTimeMillis()
    if (end > 0) r.millis
    else 0.millis
  }

}

object AkkaDefendExecutor {

  def props(msgKey: DefendCommandKey, cfg: MsgConfig, dispatcherHolder: DispatcherHolder) =
    Props(new AkkaDefendExecutor(msgKey, cfg, dispatcherHolder))

  private[internal] case object TryCloseCircuitBreaker
  private[internal] case object ClosingCircuitBreakerFailed
  private[internal] case object ClosingCircuitBreakerSucceed

  private[internal] object sameThreadExecutionContext extends ExecutionContext with DefendBatchingExecutor {
    override protected def unbatchedExecute(runnable: Runnable): Unit = runnable.run()
    override protected def resubmitOnBlock: Boolean = false // No point since we execute on same thread
    override def reportFailure(t: Throwable): Unit =
      throw new IllegalStateException("exception in sameThreadExecutionContext", t)
  }

  private val timeoutFuture = Future.failed(new TimeoutException("Circuit Breaker Timed out.") with NoStackTrace)
}
