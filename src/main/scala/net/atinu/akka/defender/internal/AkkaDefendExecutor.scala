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

import scala.concurrent.{ Future, ExecutionContext, Promise }
import scala.util.control.{ NoStackTrace, NonFatal }
import scala.util.{ Try, Failure, Success }
import scala.concurrent.duration._

class AkkaDefendExecutor(val msgKey: DefendCommandKey, val cfg: MsgConfig, val dispatcherHolder: DispatcherHolder)
    extends Actor with DiagnosticActorLogging with Stash {

  import akka.pattern.pipe

  val statsActor = startStatsActor()
  var stats = CmdKeyStatsSnapshot.initial
  val resetTimeoutMillis = cfg.circuitBreaker.resetTimeout.toMillis

  def receive = receiveClosed(isHalfOpen = false)

  def receiveClosed(isHalfOpen: Boolean): Receive = {
    case DefendAction(startTime, msg: AsyncDefendExecution[_]) =>
      import context.dispatcher
      callAsync(msg, startTime, isFallback = false, isHalfOpen) pipeTo sender()

    case DefendAction(startTime, msg: SyncDefendExecution[_]) =>
      import context.dispatcher
      callSync(msg, startTime, isFallback = false, isHalfOpen) pipeTo sender()

    case FallbackAction(promise, startTime, msg: AsyncDefendExecution[_]) =>
      fallbackFuture(promise, callAsync(msg, startTime, isFallback = true, isHalfOpen))

    case FallbackAction(promise, startTime, msg: SyncDefendExecution[_]) =>
      fallbackFuture(promise, callSync(msg, startTime, isFallback = true, isHalfOpen))

    case snap: CmdKeyStatsSnapshot =>
      stats = snap
      openCircuitBreakerOnFailureLimit(snap.callStats)

    case gcs @ GetCurrentStats =>
      statsActor forward gcs
  }

  def receiveOpen(end: Long): Receive = {
    case TryCloseCircuitBreaker =>
      context.become(receiveClosed(isHalfOpen = true))

    case DefendAction(_, msg: DefendExecution[_, _]) =>
      import context.dispatcher
      callBreak(msg, calcCircuitBreakerOpenRemaining(end)) pipeTo sender()

    case FallbackAction(promise, startTime, cmd) =>
      promise.completeWith(callBreak(cmd, calcCircuitBreakerOpenRemaining(end)))

    case snap: CmdKeyStatsSnapshot =>
      stats = snap

    case gcs @ GetCurrentStats =>
      statsActor forward gcs
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

  def fallbackFuture[T](promise: Promise[T], res: Future[T]) =
    promise.completeWith(res)

  def callSync[T](msg: SyncDefendExecution[T], totalStartTime: Long, isFallback: Boolean, breakOnSingleFailure: Boolean): Future[T] = {
    cmdExecDebugMsg(isAsync = false, isFallback, breakOnSingleFailure)
    execFlow(msg, breakOnSingleFailure, totalStartTime, Future.apply(msg.execute)(dispatcherHolder.dispatcher))
  }

  def callAsync[T](msg: AsyncDefendExecution[T], totalStartTime: Long, isFallback: Boolean, breakOnSingleFailure: Boolean): Future[T] = {
    cmdExecDebugMsg(isAsync = true, isFallback, breakOnSingleFailure)
    execFlow(msg, breakOnSingleFailure, totalStartTime, msg.execute)
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

  def execFlow[T](msg: DefendExecution[T, _], breakOnSingleFailure: Boolean, totalStartTime: Long, execute: => Future[T]): Future[T] = {
    if (breakOnSingleFailure) waitForApproval()
    val res = process(msg, totalStartTime, execute)
    if (breakOnSingleFailure) checkForSingleFailure(res)
    fallbackIfDefined(msg, res)
  }

  // adapted based on the akka circuit breaker implementation
  def process[T](msg: DefendExecution[T, _], totalStartTime: Long, body: => Future[T]): Future[T] = {
    implicit val ec = AkkaDefendExecutor.sameThreadExecutionContext

    def materialize[U](value: ⇒ Future[U]): (Long, Future[U]) = {
      var time = 0L
      try {
        time = System.currentTimeMillis()
        (time, value)
      } catch { case NonFatal(t) ⇒ (time, Future.failed(t)) }
    }

    def processInternal(res: Try[T], startTimeMs: Long) = {
      processPostCall(res, startTimeMs, totalStartTime, msg)
    }

    val callTimeout = cfg.circuitBreaker.callTimeout
    val p = Promise[T]()
    if (callTimeout == Duration.Zero) {
      val (time, f) = materialize(body)
      f.onComplete { result ⇒
        p tryComplete processInternal(result, time)
      }
    } else {
      val (time, f) = materialize(body)
      val timeout = context.system.scheduler.scheduleOnce(callTimeout) {
        p tryComplete processInternal(AkkaDefendExecutor.timeoutFailure, time)
      }
      f.onComplete { result ⇒
        timeout.cancel
        p tryComplete processInternal(result, time)
      }
    }
    p.future
  }

  def processPostCall[T](result: Try[T], startTimeMs: Long, totalStartTime: Long, cmd: DefendExecution[T, _]): Try[T] = {
    setMdcContext()
    val statsRes = StatsResult.captureStart[T](result, startTimeMs)
    val recartExec = applyCategorization(cmd, statsRes)
    updateCallStats(cmd.cmdKey, totalStartTime, recartExec)
    recartExec match {
      case Success(v) => Success(v.res)
      case Failure(t) => Failure(t.getCause)
    }
  }

  def applyCategorization[T](msg: DefendExecution[T, _], exec: Try[StatsResult[T]]): Try[StatsResult[T]] = msg match {
    case categorizer: SuccessCategorization[T @unchecked] =>
      exec.map { res =>
        categorizer.categorize.applyOrElse(res.res, (_: T) => IsSuccess) match {
          case IsSuccess => res
          case IsBadRequest => res.error(DefendBadRequestException.apply("result $res categorized as bad request"))
        }
      }
    case _ => exec
  }

  def callBreak[T](cmd: NamedCommand, remainingDuration: FiniteDuration): Future[T] = {
    log.debug("{}: fail call due to open circuit breaker (remaining duration: {})", cmd.cmdKey, remainingDuration)
    statsActor ! ReportCircuitBreakerOpenCall
    Promise.failed[T](new CircuitBreakerOpenException(remainingDuration)).future
  }

  def fallbackIfDefined[T](msg: DefendExecution[T, _], exec: Future[T]): Future[T] = msg match {
    case static: StaticFallback[T @unchecked] =>
      fallbackIfValidRequest(exec)(err => Future.successful(static.fallback))
    case dynamic: CmdFallback[T @unchecked] =>
      fallbackIfValidRequest(exec) { err =>
        val fallbackPromise = Promise.apply[T]()
        self ! FallbackAction(fallbackPromise, System.currentTimeMillis(), dynamic.fallback)
        fallbackPromise.future
      }
    case _ => exec
  }

  def fallbackIfValidRequest[T](exec: Future[T])(recover: Throwable => Future[T]) = {
    import context.dispatcher
    exec.recoverWith {
      case _: DefendBadRequestException => exec
      case e => recover(e)
    }
  }

  def updateCallStats(cmdKey: DefendCommandKey, totalStartTime: Long, exec: Try[StatsResult[_]]): Unit = {
    val durationTotal = System.currentTimeMillis() - totalStartTime
    exec match {
      case Success(v) =>
        log.debug("{}: command execution succeeded in {} ms", cmdKey, durationTotal)
        statsActor ! ReportSuccCall(v.timeMs, durationTotal)
      case Failure(v: StatsResultException) =>
        val msg = v.getCause match {
          case e: DefendBadRequestException =>
            log.debug("{}: command execution failed -> bad request", cmdKey)
            ReportBadRequestCall(v.timeMs, durationTotal)
          case e: TimeoutException =>
            log.debug("{}: command execution failed -> timeout", cmdKey)
            ReportTimeoutCall(v.timeMs, durationTotal)
          case e: CircuitBreakerOpenException =>
            log.debug("{}: command execution failed -> open circuit breaker", cmdKey)
            ReportCircuitBreakerOpenCall
          case e => ReportErrorCall(v.timeMs, durationTotal)
        }
        statsActor ! msg
      case Failure(e) =>
        log.error(e, "unexpected exception")
    }
  }

  def startStatsActor() = {
    context.actorOf(AkkaDefendCmdKeyStatsActor.props(msgKey, cfg.metrics), s"cmd-key-stats")
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

  def setMdcContext() = log.mdc(staticMdcInfo)

  override def mdc(currentMessage: Any): MDC = staticMdcInfo

}

object AkkaDefendExecutor {

  def props(msgKey: DefendCommandKey, cfg: MsgConfig, dispatcherHolder: DispatcherHolder) =
    Props(new AkkaDefendExecutor(msgKey, cfg, dispatcherHolder))

  private[defender] case object TryCloseCircuitBreaker
  private[defender] case object ClosingCircuitBreakerFailed
  private[defender] case object ClosingCircuitBreakerSucceed

  private object sameThreadExecutionContext extends ExecutionContext with DefendBatchingExecutor {
    override protected def unbatchedExecute(runnable: Runnable): Unit = runnable.run()
    override protected def resubmitOnBlock: Boolean = false // No point since we execute on same thread
    override def reportFailure(t: Throwable): Unit =
      throw new IllegalStateException("exception in sameThreadExecutionContext", t)
  }

  private val timeoutFailure = scala.util.Failure(new TimeoutException("Circuit Breaker Timed out.") with NoStackTrace)
}
