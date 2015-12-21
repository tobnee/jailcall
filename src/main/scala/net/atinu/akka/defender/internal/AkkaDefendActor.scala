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
    case CreateCmdExecutor(cmdKey) =>
      sender() ! CmdExecutorCreated(cmdKey, executorFor(cmdKey))

    case msg: NamedCommand =>
      executorFor(msg.cmdKey) forward msg
  }

  def executorFor(cmdKey: DefendCommandKey): ActorRef = {
    msgKeyToExecutor.getOrElseUpdate(cmdKey.name, buildCmdActor(cmdKey))
  }

  private def buildCmdActor(msgKey: DefendCommandKey): ActorRef = {
    val cfg = cbConfigBuilder.loadConfigForKey(msgKey)
    val dispatcherHolder = dispatcherLookup.lookupDispatcher(msgKey, cfg, log)
    context.actorOf(AkkaDefendExecutor.props(msgKey, cfg, dispatcherHolder))
  }
}

object AkkaDefendActor {

  def props = Props(new AkkaDefendActor)

  case class CreateCmdExecutor(cmdKey: DefendCommandKey)

  case class CmdExecutorCreated(cmdKey: DefendCommandKey, executor: ActorRef)
}
