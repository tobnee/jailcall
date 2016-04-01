package net.atinu.jailcall

import java.util.concurrent.ConcurrentHashMap

import akka.actor._
import akka.util.Timeout
import net.atinu.jailcall.internal.JailcallRootActor.{ CmdExecutorCreated, CreateCmdExecutor }
import net.atinu.jailcall.internal.{ JailedCommandExecutor, JailedAction }

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

/**
 * Public gateway for scheduling and managing jailcall executions
 */
class JailcallExecutor private[jailcall] (jailcallRef: ActorRef, maxCreateTime: FiniteDuration, maxCallDuration: FiniteDuration, metricsBusImpl: MetricsEventBus, ec: ExecutionContext) {
  import akka.pattern.ask

  private val createTimeout = new Timeout(maxCreateTime)
  private val execTimeout = new Timeout(maxCallDuration)
  private val refCache = new ConcurrentHashMap[String, ActorRef]

  /**
   * Schedule a [[JailedExecution]] from within an actor or by providing a target [[akka.actor.ActorRef]]
   *
   * @param cmd a [[JailedExecution]]
   * @param receiver the receiver of the response. This will be either a [[JailcallExecutionResult]] or in case of a
   *                 failure a [[akka.actor.Status]] containing a [[JailcallExecutionException]]. Take a look at the
   *                [[JailcallExecutionResult]] companion object for result extractors.
   */
  def executeToRef(cmd: JailedExecution[_])(implicit receiver: ActorRef) = {
    val startTime = System.currentTimeMillis()
    val action = JailedAction(startTime, None, cmd)
    askRefInternal(cmd, action)
  }

  /**
   * Schedule a [[JailedExecution]] from within an actor, adding the sender of the current message to the request
   * context
   *
   * @param cmd a [[JailedExecution]]
   * @param receiver the receiver of the response. This will be either a [[JailcallExecutionResult]] or in case of a
   *                 failure a [[akka.actor.Status]] containing a [[JailcallExecutionException]]. Take a look at the
   *                [[JailcallExecutionResult]] companion object for result extractors.
   */
  def executeToRefWithContext(cmd: JailedExecution[_])(implicit receiver: ActorRef, senderContext: ActorContext): Unit = {
    val startTime = System.currentTimeMillis()
    val action = JailedAction(startTime, Some(senderContext.sender()), cmd)
    askRefInternal(cmd, action)
  }

  private def askRefInternal(cmd: JailedExecution[_], action: JailedAction)(implicit receiver: ActorRef): Unit = {
    val cmdKey = cmd.cmdKey.name
    if (refCache.containsKey(cmdKey)) {
      refCache.get(cmdKey) ! action
    } else {
      askCreateExecutor(cmd).onComplete {
        case Success(created: CmdExecutorCreated) =>
          val executor = created.executor
          executor ! action
          refCache.put(cmdKey, executor)
        case Failure(e) =>
          // unlikely to happen
          receiver ! Status.Failure(e)
      }(ec)
    }
  }

  /**
   * Schedule a [[JailedExecution]] which gets executed to a [[scala.concurrent.Future]]. This has the advantage that
   * command results can be processed outside an actor context.
   *
   * @param cmd a [[JailedExecution]]
   * @tparam R result type of the execution
   * @return a future containing the result or a failure. jailcall specific failure types are a
   *         [[java.util.concurrent.TimeoutException]] in case the call took too long or a
   *         [[akka.pattern.CircuitBreakerOpenException]] if the call was prevented due to an open circuit breaker
   */
  def executeToFuture[R](cmd: JailedExecution[R]): Future[R] = {
    val startTime = System.currentTimeMillis()
    val cmdKey = cmd.cmdKey.name
    def askInternal(ref: ActorRef) = ref.ask(JailedAction(startTime, None, cmd))(execTimeout)
      .transform(res => res.asInstanceOf[JailcallExecutionResult[R]].result, {
        case e: JailcallExecutionException => e.failure
        case err => err
      })(JailedCommandExecutor.sameThreadExecutionContext)
    if (refCache.containsKey(cmdKey)) {
      askInternal(refCache.get(cmdKey))
    } else {
      askCreateExecutor(cmd).flatMap { created =>
        val executor = created.executor
        refCache.put(cmdKey, executor)
        askInternal(executor)
      }(ec)
    }
  }

  private def askCreateExecutor[R](cmd: JailedExecution[R]): Future[CmdExecutorCreated] = {
    jailcallRef.ask(CreateCmdExecutor(cmd.cmdKey, Some(cmd)))(createTimeout).mapTo[CmdExecutorCreated]
  }

  /**
   * Get an eventual consistent snapshot of the current [[CommandKey]] statistics
   */
  def statsFor(key: CommandKey): CmdKeyStatsSnapshot = {
    metricsBusImpl.statsFor(key)
  }

  def metricsBus: MetricsEventBus = metricsBusImpl
}
