package net.atinu.jailcall

import akka.actor._
import net.atinu.jailcall.internal.InternalJailcallExecutor

import scala.concurrent.Future

/**
 * Public gateway for scheduling and managing jailcall executions
 */
class JailcallExecutor private[jailcall] (internalJailcallExecutor: InternalJailcallExecutor) {

  /**
   * Schedule a [[JailedExecution]] from within an actor or by providing a target [[akka.actor.ActorRef]]
   *
   * @param cmd a [[JailedExecution]]
   * @param receiver the receiver of the response. This will be either a [[JailcallExecutionResult]] or in case of a
   *                 failure a [[akka.actor.Status]] containing a [[JailcallExecutionException]]. Take a look at the
   *                [[JailcallExecutionResult]] companion object for result extractors.
   */
  def executeToRef(cmd: JailedExecution[_, _])(implicit receiver: ActorRef) = {
    internalJailcallExecutor.executeToRef(cmd, isJavaRequest = false)
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
  def executeToRefWithContext(cmd: JailedExecution[_, _])(implicit receiver: ActorRef, senderContext: ActorContext): Unit = {
    internalJailcallExecutor.executeToRefWithContext(cmd, isJavaRequest = false)
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
  def executeToFuture[R](cmd: JailedExecution[R, _]): Future[R] = {
    internalJailcallExecutor.executeToFuture(cmd, isJavaRequest = false)
  }

  /**
   * Get an eventual consistent snapshot of the current [[CommandKey]] statistics
   */
  def statsFor(key: CommandKey): CmdKeyStatsSnapshot = {
    internalJailcallExecutor.statsFor(key)
  }

  def metricsBus: MetricsEventBus =
    internalJailcallExecutor.metricsBus
}
