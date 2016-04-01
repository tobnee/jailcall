package net.atinu.jailcall.internal

import java.util.concurrent.TimeoutException

import akka.actor._
import akka.jailcall.JailcallBatchingExecutor
import akka.event.Logging.MDC
import akka.pattern.CircuitBreakerOpenException
import com.typesafe.config.ConfigRenderOptions
import net.atinu.jailcall._
import net.atinu.jailcall.internal.CmdKeyStatsActor._
import net.atinu.jailcall.internal.DispatcherLookup.DispatcherHolder
import net.atinu.jailcall.internal.JailedCommandExecutor.{ ClosingCircuitBreakerSucceed, ClosingCircuitBreakerFailed, TryCloseCircuitBreaker }

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.control.{ NoStackTrace, NonFatal }
import scala.util.{ Failure, Success, Try }

class JailedCommandExecutor(val cmdKey: CommandKey, val cfg: MsgConfig, val dispatcherHolder: DispatcherHolder, metricsBus: MetricsEventBus)
    extends Actor with DiagnosticActorLogging with Stash {

  import akka.pattern.pipe

  val statsActor = startStatsActor()
  var stats = CmdKeyStatsSnapshot.initial(cmdKey)
  val resetTimeoutMillis = cfg.circuitBreaker.resetTimeout.toMillis

  def receive = receiveClosed(isHalfOpen = false)

  def receiveClosed(isHalfOpen: Boolean): Receive = {
    case JailedAction(startTime, senderRef, msg: ScalaFutureExecution[_]) =>
      implicit val ec = JailedCommandExecutor.sameThreadExecutionContext
      toResCommand(callAsync(msg, startTime, isFallback = false, isHalfOpen), senderRef) pipeTo sender()

    case JailedAction(startTime, senderRef, msg: BlockingExecution[_]) =>
      implicit val ec = JailedCommandExecutor.sameThreadExecutionContext
      toResCommand(callSync(msg, startTime, isFallback = false, isHalfOpen), senderRef) pipeTo sender()

    case FallbackAction(promise, startTime, msg: ScalaFutureExecution[_]) =>
      fallbackFuture(promise, callAsync(msg, startTime, isFallback = true, isHalfOpen))

    case FallbackAction(promise, startTime, msg: BlockingExecution[_]) =>
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

    case JailedAction(_, senderRef, msg: JailedExecution[_]) =>
      implicit val ec = JailedCommandExecutor.sameThreadExecutionContext
      toResCommand(callBreak(msg, calcCircuitBreakerOpenRemaining(end)), senderRef) pipeTo sender()

    case FallbackAction(promise, startTime, cmd) =>
      promise.completeWith(callBreak(cmd, calcCircuitBreakerOpenRemaining(end)))

    case snap: CmdKeyStatsSnapshot =>
      stats = snap

    case gcs @ GetCurrentStats =>
      statsActor forward gcs
  }

  def receiveHalfOpen: Receive = {
    case ClosingCircuitBreakerFailed =>
      log.debug("{}: closing circuit breaker failed", cmdKey.name)
      openCircuitBreaker()
      unstashAll()
    case ClosingCircuitBreakerSucceed =>
      log.debug("{}: closing circuit breaker succeeded", cmdKey.name)
      context.become(receiveClosed(isHalfOpen = false))
      unstashAll()
    case _ =>
      stash()
  }

  def fallbackFuture[T](promise: Promise[T], res: Future[T]) =
    promise.completeWith(res)

  def callSync[T](msg: BlockingExecution[T], totalStartTime: Long, isFallback: Boolean, breakOnSingleFailure: Boolean): Future[T] = {
    cmdExecDebugMsg(isAsync = false, isFallback, breakOnSingleFailure)
    execFlow(msg, breakOnSingleFailure, totalStartTime, Future.apply(msg.execute)(dispatcherHolder.dispatcher))
  }

  def callAsync[T](msg: ScalaFutureExecution[T], totalStartTime: Long, isFallback: Boolean, breakOnSingleFailure: Boolean): Future[T] = {
    cmdExecDebugMsg(isAsync = true, isFallback, breakOnSingleFailure)
    execFlow(msg, breakOnSingleFailure, totalStartTime, msg.execute)
  }

  def toResCommand[T](res: Future[T], senderRef: Option[ActorRef])(implicit ec: ExecutionContext): Future[JailcallExecutionResult[T]] =
    res.transform(res => JailcallExecutionResult(res, senderRef), t => JailcallExecutionException(t, senderRef))

  def cmdExecDebugMsg(isAsync: Boolean, isFallback: Boolean, breakOnSingleFailure: Boolean) = {
    def halfOpenMsg =
      if (breakOnSingleFailure) " in half-open mode"
      else " in closed mode"

    def isFallbackMsg =
      if (isFallback) " fallback" else ""

    def isAsyncMsg =
      if (isAsync) "async" else "sync"

    if (log.isDebugEnabled) {
      log.debug("{}: execute {}{} command{}", cmdKey, isAsyncMsg, isFallbackMsg, halfOpenMsg)
    }
  }

  def execFlow[T](msg: JailedExecution[T], breakOnSingleFailure: Boolean, totalStartTime: Long, execute: => Future[T]): Future[T] = {
    if (breakOnSingleFailure) waitForApproval()
    val res = process(msg, totalStartTime, execute)
    if (breakOnSingleFailure) checkForSingleFailure(res)
    fallbackIfDefined(msg, res)
  }

  // adapted based on the akka circuit breaker implementation
  def process[T](msg: JailedExecution[T], totalStartTime: Long, body: => Future[T]): Future[T] = {
    implicit val ec = JailedCommandExecutor.sameThreadExecutionContext

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
        p tryComplete processInternal(JailedCommandExecutor.timeoutFailure, time)
      }
      f.onComplete { result ⇒
        timeout.cancel
        p tryComplete processInternal(result, time)
      }
    }
    p.future
  }

  def processPostCall[T](result: Try[T], startTimeMs: Long, totalStartTime: Long, cmd: JailedExecution[T]): Try[T] = {
    setMdcContext()
    val statsRes = StatsResult.captureStart[T](result, startTimeMs)
    updateCallStats(cmd.cmdKey, totalStartTime, statsRes)
    statsRes match {
      case Success(v) => Success(v.res)
      case Failure(t) => Failure(t.getCause)
    }
  }

  def callBreak[T](cmd: NamedCommand, remainingDuration: FiniteDuration): Future[T] = {
    log.debug("{}: fail call due to open circuit breaker (remaining duration: {})", cmd.cmdKey, remainingDuration)
    statsActor ! ReportCircuitBreakerOpenCall
    Promise.failed[T](new CircuitBreakerOpenException(remainingDuration)).future
  }

  def fallbackIfDefined[T](msg: JailedExecution[T], exec: Future[T]): Future[T] = msg match {
    case dynamic: CmdFallback =>
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
      case _: BadRequestException => exec
      case e => recover(e)
    }
  }

  def updateCallStats(cmdKey: CommandKey, totalStartTime: Long, exec: Try[StatsResult[_]]): Unit = {
    val durationTotal = System.currentTimeMillis() - totalStartTime
    exec match {
      case Success(v) =>
        log.debug("{}: command execution succeeded in {} ms", cmdKey, durationTotal)
        statsActor ! ReportSuccCall(v.timeMs, durationTotal)
      case Failure(v: StatsResultException) =>
        val msg = v.getCause match {
          case e: BadRequestException =>
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
    context.actorOf(CmdKeyStatsActor.props(cmdKey, cfg.metrics, metricsBus), s"cmd-key-stats")
  }

  def waitForApproval() = {
    log.debug("{}: become half open", cmdKey.name)
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
    log.debug("{}: open circuit breaker for {}, calls will fail fast", cmdKey.name, cfg.circuitBreaker.resetTimeout)
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

  val staticMdcInfo = Map("cmdKey" -> cmdKey.name)

  def setMdcContext() = log.mdc(staticMdcInfo)

  override def mdc(currentMessage: Any): MDC = staticMdcInfo

}

object JailedCommandExecutor {

  def props(msgKey: CommandKey, cfg: MsgConfig, dispatcherHolder: DispatcherHolder, metricsBus: MetricsEventBus) =
    Props(new JailedCommandExecutor(msgKey, cfg, dispatcherHolder, metricsBus))

  private[jailcall] case object TryCloseCircuitBreaker
  private[jailcall] case object ClosingCircuitBreakerFailed
  private[jailcall] case object ClosingCircuitBreakerSucceed

  private[jailcall] object sameThreadExecutionContext extends ExecutionContext with JailcallBatchingExecutor {
    override protected def unbatchedExecute(runnable: Runnable): Unit = runnable.run()
    override protected def resubmitOnBlock: Boolean = false // No point since we execute on same thread
    override def reportFailure(t: Throwable): Unit =
      throw new IllegalStateException("exception in sameThreadExecutionContext", t)
  }

  private val timeoutFailure = scala.util.Failure(new TimeoutException("Circuit Breaker Timed out.") with NoStackTrace)
}
