package net.atinu.akka.defender.internal

import java.util.concurrent.TimeoutException

import akka.actor._
import akka.defend.DefendBatchingExecutor
import akka.event.Logging.MDC
import akka.pattern.CircuitBreakerOpenException
import net.atinu.akka.defender._
import net.atinu.akka.defender.internal.AkkaDefendCmdKeyStatsActor._
import net.atinu.akka.defender.internal.AkkaDefendExecutor.{ ClosingCircuitBreakerSucceed, ClosingCircuitBreakerFailed, TryCloseCircuitBreaker }
import net.atinu.akka.defender.internal.DispatcherLookup.DispatcherHolder
import net.atinu.akka.defender.internal.util.CallStats

import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.control.{ NoStackTrace, NonFatal }
import scala.util.{ Failure, Success }
import scala.concurrent.duration._

class AkkaDefendExecutor(val msgKey: DefendCommandKey, val cfg: MsgConfig, val dispatcherHolder: DispatcherHolder)
    extends Actor with DiagnosticActorLogging with Stash {

  import akka.pattern.pipe

  val statsActor: ActorRef = statsActorForKey(msgKey)
  var stats = CmdKeyStatsSnapshot.initial
  val resetTimeoutMillis = cfg.circuitBreaker.resetTimeout.toMillis

  def receive = receiveClosed(isHalfOpen = false)

  def receiveClosed(isHalfOpen: Boolean): Receive = {
    case msg: AsyncDefendExecution[_] =>
      import context.dispatcher
      callAsync(msg, isFallback = false, isHalfOpen) pipeTo sender()

    case msg: SyncDefendExecution[_] =>
      import context.dispatcher
      callSync(msg, isFallback = false, isHalfOpen) pipeTo sender()

    case FallbackAction(promise, msg: AsyncDefendExecution[_]) =>
      fallbackFuture(promise, callAsync(msg, isFallback = true, isHalfOpen))

    case FallbackAction(promise, msg: SyncDefendExecution[_]) =>
      fallbackFuture(promise, callSync(msg, isFallback = true, isHalfOpen))

    case snap: CmdKeyStatsSnapshot =>
      stats = snap
      openCircuitBreakerOnFailureLimit(snap.callStats)
  }

  def receiveOpen(end: Long): Receive = {
    case TryCloseCircuitBreaker =>
      context.become(receiveClosed(isHalfOpen = true))

    case msg: DefendExecution[_, _] =>
      import context.dispatcher
      callBreak(msg, calcCircuitBreakerOpenRemaining(end)) pipeTo sender()

    case FallbackAction(promise, cmd) =>
      promise.completeWith(callBreak(cmd, calcCircuitBreakerOpenRemaining(end)))

    case snap: CmdKeyStatsSnapshot => {
      stats = snap
    }
  }

  def receiveHalfOpen: Receive = {
    case ClosingCircuitBreakerFailed =>
      log.debug("{}: closing circuit breaker failed", msgKey.name)
      openCircuitBreaker()
      unstashAll()
    case ClosingCircuitBreakerSucceed =>
      log.debug("{}: closing circuit breaker succeeded", msgKey.name)
      context.become(receiveClosed(isHalfOpen = false))
      unstashAll()
    case _ =>
      stash()
  }

  def fallbackFuture(promise: Promise[Any], res: Future[_]) =
    promise.completeWith(res)

  def callSync(msg: SyncDefendExecution[_], isFallback: Boolean, breakOnSingleFailure: Boolean): Future[Any] = {
    cmdExecDebugMsg(isAsync = false, isFallback, breakOnSingleFailure)
    execFlow(msg, breakOnSingleFailure, Future.apply(msg.execute)(dispatcherHolder.dispatcher))
  }

  def callAsync(msg: AsyncDefendExecution[_], isFallback: Boolean, breakOnSingleFailure: Boolean): Future[Any] = {
    cmdExecDebugMsg(isAsync = true, isFallback, breakOnSingleFailure)
    execFlow(msg, breakOnSingleFailure, msg.execute)
  }

  def cmdExecDebugMsg(isAsync: Boolean, isFallback: Boolean, breakOnSingleFailure: Boolean) = {
    def halfOpenMsg =
      if (breakOnSingleFailure) " in half-open mode"
      else " in closed mode"

    def isFallbackMsg =
      if (isFallback) " fallback" else ""

    def isAsyncMsg =
      if (isAsync) "async" else "sync"

    if (log.isDebugEnabled) {
      log.debug("{}: execute {}{} command{}", msgKey, isAsyncMsg, isFallbackMsg, halfOpenMsg)
    }
  }

  def execFlow(msg: DefendExecution[_, _], breakOnSingleFailure: Boolean, execute: => Future[Any]): Future[Any] = {
    if (breakOnSingleFailure) waitForApproval()
    val (startTime, exec) = callThrough(execute)
    val recatExec = applyCategorization(msg, exec)
    updateCallStats(msg.cmdKey, startTime, recatExec)
    if (breakOnSingleFailure) checkForSingleFailure(recatExec)
    fallbackIfDefined(msg, recatExec)
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

    val callTimeout = cfg.circuitBreaker.callTimeout
    if (callTimeout == Duration.Zero) {
      materialize(body)
    } else {
      val p = Promise[T]()
      implicit val ec = AkkaDefendExecutor.sameThreadExecutionContext

      val timeout = context.system.scheduler.scheduleOnce(callTimeout) {
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

  def applyCategorization(msg: DefendExecution[_, _], exec: Future[Any]): Future[Any] = msg match {
    case categorizer: SuccessCategorization[Any @unchecked] =>
      import context.dispatcher
      exec.map { res =>
        categorizer.categorize.applyOrElse(res, (_: Any) => IsSuccess) match {
          case IsSuccess => res
          case IsBadRequest => throw DefendBadRequestException.apply("result $res categorized as bad request")
        }
      }
    case _ => exec
  }

  def callBreak[T](cmd: NamedCommand, remainingDuration: FiniteDuration): Future[T] = {
    log.debug("{}: fail call due to open circuit breaker (remaining duration: {})", cmd.cmdKey, remainingDuration)
    Promise.failed[T](new CircuitBreakerOpenException(remainingDuration)).future
  }

  def fallbackIfDefined(msg: DefendExecution[_, _], exec: Future[Any]): Future[Any] = msg match {
    case static: StaticFallback[_] =>
      fallbackIfValidRequest(exec)(err => Future.successful(static.fallback))
    case dynamic: CmdFallback[_] =>
      fallbackIfValidRequest(exec) { err =>
        val fallbackPromise = Promise.apply[Any]()
        self ! FallbackAction(fallbackPromise, dynamic.fallback)
        fallbackPromise.future
      }
    case _ => exec
  }

  def fallbackIfValidRequest(exec: Future[_])(recover: Throwable => Future[_]) = {
    import context.dispatcher
    exec.recoverWith {
      case _: DefendBadRequestException => exec
      case e => recover(e)
    }
  }

  def updateCallStats(cmdKey: DefendCommandKey, startTime: Long, exec: Future[Any]): Unit = {
    import context.dispatcher
    exec.onComplete {
      case Success(_) =>
        val t = System.currentTimeMillis() - startTime
        log.debug("{}: command execution succeeded", cmdKey)
        statsActor ! ReportSuccCall(t)
      case Failure(v) =>
        val t = System.currentTimeMillis() - startTime
        val msg = v match {
          case e: DefendBadRequestException =>
            log.debug("{}: command execution failed -> bad request", cmdKey)
            ReportBadRequestCall(t)
          case e: TimeoutException =>
            log.debug("{}: command execution failed -> timeout", cmdKey)
            ReportTimeoutCall(t)
          case e: CircuitBreakerOpenException =>
            log.debug("{}: command execution failed -> open circuit breaker", cmdKey)
            ReportCircuitBreakerOpenCall
          case e => ReportErrorCall(t)
        }
        statsActor ! msg
    }
  }

  def statsActorForKey(cmdKey: DefendCommandKey) = {
    val cmdKeyName = cmdKey.name
    context.actorOf(AkkaDefendCmdKeyStatsActor.props(cmdKey), s"cmd-key-stats")
  }

  def waitForApproval() = {
    log.debug("{}: become half open", msgKey.name)
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
    val cbConfig = cfg.circuitBreaker
    if (cbConfig.enabled) {
      if (callStats.validRequestCount >= cbConfig.requestVolumeThreshold) {
        if (callStats.errorPercent >= cbConfig.minFailurePercent) {
          openCircuitBreaker()
        }
      }
    }
  }

  def openCircuitBreaker(): Unit = {
    import context.dispatcher
    log.debug("{}: open circuit breaker for {}, calls will fail fast", msgKey.name, cfg.circuitBreaker.resetTimeout)
    context.system.scheduler.scheduleOnce(cfg.circuitBreaker.resetTimeout, self, TryCloseCircuitBreaker)
    context.become(receiveOpen(calcCircuitBreakerEndTime))
  }

  def calcCircuitBreakerEndTime: Long = {
    System.currentTimeMillis() + resetTimeoutMillis
  }

  def calcCircuitBreakerOpenRemaining(end: Long) = {
    val r = end - System.currentTimeMillis()
    if (end > 0) r.millis
    else 0.millis
  }

  val staticMdcInfo = Map("cmdKey" -> msgKey.name)

  override def mdc(currentMessage: Any): MDC = staticMdcInfo

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
