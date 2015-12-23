package net.atinu.akka.defender.internal

import akka.actor.{ Actor, ActorLogging, ActorRef, Props }
import net.atinu.akka.defender._
import net.atinu.akka.defender.internal.AkkaDefendActor.{ CmdExecutorCreated, CreateCmdExecutor }

private[defender] class AkkaDefendActor extends Actor with ActorLogging {

  val msgKeyToExecutor = collection.mutable.Map.empty[String, ActorRef]

  val rootConfig = context.system.settings.config
  val cbConfigBuilder = new MsgConfigBuilder(rootConfig)
  val dispatcherLookup = new DispatcherLookup(context.system.dispatchers)

  def receive = {
    case CreateCmdExecutor(cmdKey, Some(exec)) =>
      val needsIsolation = exec match {
        case _: AsyncDefendExecution[_] => false
        case _: SyncDefendExecution[_] => true
      }
      createExecutor(cmdKey, needsIsolation = needsIsolation)

    case CreateCmdExecutor(cmdKey, None) =>
      createExecutor(cmdKey, needsIsolation = true)

    case msg: NamedCommand =>
      msgKeyToExecutor.get(msg.cmdKey.name).foreach { ref =>
        ref forward msg
      }
  }

  def createExecutor(cmdKey: DefendCommandKey, needsIsolation: Boolean) = {
    sender() ! CmdExecutorCreated(cmdKey, executorFor(cmdKey, needsIsolation))
  }

  def executorFor(cmdKey: DefendCommandKey, needsIsolation: Boolean): ActorRef = {
    msgKeyToExecutor.getOrElseUpdate(cmdKey.name, buildCmdActor(cmdKey, needsIsolation))
  }

  private def buildCmdActor(msgKey: DefendCommandKey, needsIsolation: Boolean): ActorRef = {
    val cfg = cbConfigBuilder.loadConfigForKey(msgKey)
    val dispatcherHolder = dispatcherLookup.lookupDispatcher(msgKey, cfg, log, needsIsolation)
    context.actorOf(AkkaDefendExecutor.props(msgKey, cfg, dispatcherHolder))
  }
}

object AkkaDefendActor {

  def props = Props(new AkkaDefendActor)

  case class CreateCmdExecutor(cmdKey: DefendCommandKey, firstCmd: Option[DefendExecution[_, _]])

  case class CmdExecutorCreated(cmdKey: DefendCommandKey, executor: ActorRef)
}
