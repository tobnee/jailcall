package net.atinu.jailcall.internal

import java.util.concurrent.ConcurrentHashMap

import akka.actor.{ Status, ActorContext, ActorRef }
import akka.util.Timeout
import net.atinu.jailcall.internal.JailcallRootActor.{ CreateCmdExecutor, CmdExecutorCreated }
import net.atinu.jailcall._

import scala.concurrent.{ Future, ExecutionContext }
import scala.concurrent.duration.FiniteDuration
import scala.util.{ Failure, Success }

class InternalJailcallExecutor private[jailcall] (jailcallRef: ActorRef, maxCreateTime: FiniteDuration, maxCallDuration: FiniteDuration, metricsBusImpl: MetricsEventBus, ec: ExecutionContext) {
  import akka.pattern.ask

  private val createTimeout = new Timeout(maxCreateTime)
  private val execTimeout = new Timeout(maxCallDuration)
  private val refCache = new ConcurrentHashMap[String, ActorRef]

  private[jailcall] def executeToRef(cmd: JailedExecution[_, _], isJavaRequest: Boolean)(implicit receiver: ActorRef) = {
    val startTime = System.currentTimeMillis()
    val action = JailedAction(startTime, None, isJavaRequest, cmd)
    askRefInternal(cmd, action)
  }

  private[jailcall] def executeToRefWithContext(cmd: JailedExecution[_, _], isJavaRequest: Boolean)(implicit receiver: ActorRef, senderContext: ActorContext): Unit = {
    val startTime = System.currentTimeMillis()
    val action = JailedAction(startTime, Some(senderContext.sender()), isJavaRequest, cmd)
    askRefInternal(cmd, action)
  }

  private def askRefInternal(cmd: JailedExecution[_, _], action: JailedAction)(implicit receiver: ActorRef): Unit = {
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

  private[jailcall] def executeToFuture[R](cmd: JailedExecution[R, _], isJavaRequest: Boolean): Future[R] = {
    val startTime = System.currentTimeMillis()
    val cmdKey = cmd.cmdKey.name
    def askInternal(ref: ActorRef) = ref.ask(JailedAction(startTime, None, isJavaRequest, cmd))(execTimeout)
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

  private def askCreateExecutor[R](cmd: JailedExecution[R, _]): Future[CmdExecutorCreated] = {
    jailcallRef.ask(CreateCmdExecutor(cmd.cmdKey, Some(cmd)))(createTimeout).mapTo[CmdExecutorCreated]
  }

  private[jailcall] def statsFor(key: CommandKey): CmdKeyStatsSnapshot = {
    metricsBusImpl.statsFor(key)
  }

  private[jailcall] def metricsBus: MetricsEventBus = metricsBusImpl
}