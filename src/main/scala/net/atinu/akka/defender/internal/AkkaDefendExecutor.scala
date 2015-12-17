package net.atinu.akka.defender.internal

import java.util.concurrent.TimeoutException

import akka.actor.{ Actor, ActorLogging, ActorRef, Props }
import akka.defend.DefendBatchingExecutor
import akka.pattern.CircuitBreakerOpenException
import net.atinu.akka.defender._
import net.atinu.akka.defender.internal.AkkaDefendCmdKeyStatsActor._
import net.atinu.akka.defender.internal.AkkaDefendExecutor.TryCloseCircuitBreaker
import net.atinu.akka.defender.internal.DispatcherLookup.DispatcherHolder

import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.control.{ NoStackTrace, NonFatal }
import scala.util.{ Failure, Success, Try }

class AkkaDefendExecutor(val msgKey: DefendCommandKey, val cfg: MsgConfig, val dispatcherHolder: DispatcherHolder) extends Actor with ActorLogging {

  import akka.pattern.pipe

  import scala.concurrent.duration._

  val statsActor: ActorRef = statsActorForKey(msgKey)
  var stats: Option[CmdKeyStatsSnapshot] = None

  def receive = receiveClosed

  val receiveClosed: Receive = {
    case msg: DefendExecution[_] =>
      import context.dispatcher
      callAsync(msg) pipeTo sender()

    case msg: SyncDefendExecution[_] =>
      import context.dispatcher
      callSync(msg) pipeTo sender()

    case FallbackAction(promise, msg: DefendExecution[_]) =>
      fallbackFuture(promise, callAsync(msg))

    case FallbackAction(promise, msg: SyncDefendExecution[_]) =>
      fallbackFuture(promise, callSync(msg))

    case snap: CmdKeyStatsSnapshot =>
      log.info("got new stats data {}", snap.callStats.errorCount)
      val errorCount = snap.callStats.timeoutCount
      stats = Some(snap)
      if (errorCount >= cfg.cbConfig.maxFailures - 1) {
        import context.dispatcher
        log.info("open circuit breaker for {}", cfg.cbConfig.resetTimeout)
        context.system.scheduler.scheduleOnce(cfg.cbConfig.resetTimeout, self, TryCloseCircuitBreaker)
        context.become(receiveOpen(System.currentTimeMillis() + cfg.cbConfig.resetTimeout.toMillis))
      }
  }

  def receiveOpen(end: Long): Receive = {
    case TryCloseCircuitBreaker =>
      context.become(receiveClosed)

    case msg: DefendExecution[_] =>
      import context.dispatcher
      callBreak(calcRemaining(end)) pipeTo sender()

    case msg: SyncDefendExecution[_] =>
      import context.dispatcher
      callBreak(calcRemaining(end)) pipeTo sender()

    case FallbackAction(promise, _) =>
      promise.completeWith(callBreak(calcRemaining(end)))

    case snap: CmdKeyStatsSnapshot => {
      stats = Some(snap)
    }
  }

  def calcRemaining(end: Long) = {
    val r = end - System.currentTimeMillis()
    if (end > 0) r.millis
    else 0.millis
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
    val exec = callThrough(execute)
    updateCallStats(exec)
    fallbackIfDefined(msg, exec)
  }

  // adapted based on the akka circuit breaker implementation
  def callThrough[T](body: ⇒ Future[T]): Future[T] = {

    def materialize[U](value: ⇒ Future[U]): Future[U] = try value catch { case NonFatal(t) ⇒ Future.failed(t) }

    if (cfg.cbConfig.callTimeout == Duration.Zero) {
      materialize(body)
    } else {
      val p = Promise[T]()

      implicit val ec = AkkaDefendExecutor.sameThreadExecutionContext

      val timeout = context.system.scheduler.scheduleOnce(cfg.cbConfig.callTimeout) {
        p tryCompleteWith AkkaDefendExecutor.timeoutFuture
      }

      materialize(body).onComplete { result ⇒
        p tryComplete result
        timeout.cancel
      }
      p.future
    }
  }

  def callBreak[T](remainingDuration: FiniteDuration): Future[T] =
    Promise.failed[T](new CircuitBreakerOpenException(remainingDuration)).future

  def updateCallStats(exec: Future[Any]): Unit = {
    import context.dispatcher
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

  def statsActorForKey(cmdKey: DefendCommandKey) = {
    val cmdKeyName = cmdKey.name
    context.actorOf(AkkaDefendCmdKeyStatsActor.props(cmdKey), s"stats-$cmdKeyName")
  }

}

object AkkaDefendExecutor {

  def props(msgKey: DefendCommandKey, cfg: MsgConfig, dispatcherHolder: DispatcherHolder) =
    Props(new AkkaDefendExecutor(msgKey, cfg, dispatcherHolder))

  private[internal] case object TryCloseCircuitBreaker

  private[internal] object sameThreadExecutionContext extends ExecutionContext with DefendBatchingExecutor {
    override protected def unbatchedExecute(runnable: Runnable): Unit = runnable.run()
    override protected def resubmitOnBlock: Boolean = false // No point since we execute on same thread
    override def reportFailure(t: Throwable): Unit =
      throw new IllegalStateException("exception in sameThreadExecutionContext", t)
  }

  private val timeoutFuture = Future.failed(new TimeoutException("Circuit Breaker Timed out.") with NoStackTrace)
}
