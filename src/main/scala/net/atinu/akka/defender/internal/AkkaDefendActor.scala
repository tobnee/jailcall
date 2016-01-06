package net.atinu.akka.defender.internal

import akka.actor.{ Status, ActorRef, ActorLogging, Actor, Props }
import net.atinu.akka.defender._
import net.atinu.akka.defender.internal.AkkaDefendActor.{ NoCmdExecutorForThatKey, CmdExecutorCreationFailed, CmdExecutorCreated, CreateCmdExecutor }
import net.atinu.akka.defender.internal.DispatcherLookup.DispatcherHolder

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

    case da: DefendAction =>
      val cmdKey = da.cmd.cmdKey
      msgKeyToExecutor.get(cmdKey.name) match {
        case Some(ref) => ref forward da
        case _ => sender() ! NoCmdExecutorForThatKey(cmdKey)
      }
  }

  def createExecutor(cmdKey: DefendCommandKey, needsIsolation: Boolean): Unit = {
    sender() ! (executorFor(cmdKey, needsIsolation) match {
      case Success(ref) =>
        CmdExecutorCreated(cmdKey, ref)
      case Failure(e) =>
        val errorMsg = s"creation of DefendCommand executor failed for command key: $cmdKey"
        log.error(e, errorMsg)
        Status.Failure(CmdExecutorCreationFailed(e, errorMsg))
    })
  }

  def executorFor(cmdKey: DefendCommandKey, needsIsolation: Boolean) = {
    if (msgKeyToExecutor.isDefinedAt(cmdKey.name)) Success(msgKeyToExecutor(cmdKey.name))
    else buildCmdActor(cmdKey, needsIsolation)
  }

  def buildCmdActor(cmdKey: DefendCommandKey, needsIsolation: Boolean) = {
    for {
      cfg <- cbConfigBuilder.loadConfigForKey(cmdKey)
      dispatcherHolder <- dispatcherLookup.lookupDispatcher(cmdKey, cfg.isolation, needsIsolation)
    } yield {
      val ref = createExecutorActor(cmdKey, cfg, dispatcherHolder)
      log.debug(s"created defend executor for command key: $cmdKey")
      msgKeyToExecutor += cmdKey.name -> ref
      ref
    }
  }

  def createExecutorActor(msgKey: DefendCommandKey, cfg: MsgConfig, dispatcherHolder: DispatcherHolder) = {
    context.actorOf(AkkaDefendExecutor.props(msgKey, cfg, dispatcherHolder))
  }
}

object AkkaDefendActor {

  def props = Props(new AkkaDefendActor)

  case class CreateCmdExecutor(cmdKey: DefendCommandKey, firstCmd: Option[DefendExecution[_, _]])

  object CreateCmdExecutor {

    def withKey(cmdKey: DefendCommandKey) = CreateCmdExecutor(cmdKey, None)
  }

  case class CmdExecutorCreated(cmdKey: DefendCommandKey, executor: ActorRef)

  case class CmdExecutorCreationFailed(e: Throwable, msg: String) extends RuntimeException(msg, e)

  case class NoCmdExecutorForThatKey(cmdKey: DefendCommandKey)
}
