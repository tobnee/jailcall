package net.atinu.akka.defender.internal

import akka.actor.{ Status, ActorRef, ActorLogging, Actor, Props }
import net.atinu.akka.defender._
import net.atinu.akka.defender.internal.AkkaDefendActor.{ CmdExecutorCreationFailed, CmdExecutorCreated, CreateCmdExecutor }

import scala.util.{ Success, Failure }

private[defender] class AkkaDefendActor extends Actor with ActorLogging {

  val msgKeyToExecutor = collection.mutable.Map.empty[String, ActorRef]

  val rootConfig = context.system.settings.config
  val cbConfigBuilder = new MsgConfigBuilder(rootConfig)
  val dispatcherLookup = new DispatcherLookup(context.system.dispatchers)

  def receive = {
    case CreateCmdExecutor(cmdKey, Some(exec)) =>
      val needsIsolation = exec match {
        case _: AsyncDefendExecution[_] => false
        case _ => true
      }
      createExecutor(cmdKey, needsIsolation = needsIsolation)

    case CreateCmdExecutor(cmdKey, None) =>
      createExecutor(cmdKey, needsIsolation = true)

    case msg: NamedCommand =>
      msgKeyToExecutor.get(msg.cmdKey.name).foreach { ref =>
        ref forward msg
      }
  }

  def createExecutor(cmdKey: DefendCommandKey, needsIsolation: Boolean): Unit = {
    sender() ! (executorFor(cmdKey, needsIsolation) match {
      case Success(ref) => CmdExecutorCreated(cmdKey, ref)
      case Failure(e) =>
        val errorMsg = s"Creation of DefendCommand executor failed for command key: $cmdKey"
        log.error(e, errorMsg)
        Status.Failure(CmdExecutorCreationFailed(e, errorMsg))
    })
  }

  def executorFor(cmdKey: DefendCommandKey, needsIsolation: Boolean) = {
    if (msgKeyToExecutor.isDefinedAt(cmdKey.name)) Success(msgKeyToExecutor(cmdKey.name))
    else buildCmdActor(cmdKey, needsIsolation)
  }

  private def buildCmdActor(msgKey: DefendCommandKey, needsIsolation: Boolean) = {
    for {
      cfg <- cbConfigBuilder.loadConfigForKey(msgKey)
      dispatcherHolder <- dispatcherLookup.lookupDispatcher(msgKey, cfg.isolation, log, needsIsolation)
    } yield context.actorOf(AkkaDefendExecutor.props(msgKey, cfg, dispatcherHolder))
  }
}

object AkkaDefendActor {

  def props = Props(new AkkaDefendActor)

  case class CreateCmdExecutor(cmdKey: DefendCommandKey, firstCmd: Option[DefendExecution[_, _]])

  case class CmdExecutorCreated(cmdKey: DefendCommandKey, executor: ActorRef)

  case class CmdExecutorCreationFailed(e: Throwable, msg: String) extends RuntimeException(msg, e)
}
