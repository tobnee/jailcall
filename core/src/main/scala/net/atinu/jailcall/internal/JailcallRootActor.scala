package net.atinu.jailcall.internal

import akka.actor.{ Actor, ActorLogging, ActorRef, Props, Status }
import com.typesafe.config.{ Config, ConfigRenderOptions }
import net.atinu.jailcall.internal.DispatcherLookup.DispatcherHolder
import net.atinu.jailcall.internal.JailcallRootActor.{ CmdExecutorCreationFailed, CmdExecutorCreated, NoCmdExecutorForThatKey, CreateCmdExecutor }
import net.atinu.jailcall._

import scala.util.{ Failure, Success }

private[jailcall] class JailcallRootActor(metricsBus: MetricsEventBus) extends Actor with ActorLogging {

  val msgKeyToExecutor = collection.mutable.Map.empty[String, ActorRef]

  val rootConfig = context.system.settings.config
  val cbConfigBuilder = new MsgConfigBuilder(rootConfig)
  val dispatcherLookup = new DispatcherLookup(context.system.dispatchers)

  def receive = {
    case CreateCmdExecutor(cmdKey, Some(exec)) =>
      val needsIsolation = exec match {
        case _: ScalaFutureExecution[_] => false
        case _ => true
      }
      createExecutor(cmdKey, needsIsolation = needsIsolation)

    case CreateCmdExecutor(cmdKey, None) =>
      createExecutor(cmdKey, needsIsolation = true)

    case da: JailedAction =>
      val cmdKey = da.cmd.cmdKey
      msgKeyToExecutor.get(cmdKey.name) match {
        case Some(ref) => ref forward da
        case _ => sender() ! NoCmdExecutorForThatKey(cmdKey)
      }
  }

  def createExecutor(cmdKey: CommandKey, needsIsolation: Boolean): Unit = {
    sender() ! (executorFor(cmdKey, needsIsolation) match {
      case Success(ref) =>
        CmdExecutorCreated(cmdKey, ref)
      case Failure(e) =>
        val errorMsg = s"creation of JailedCommand executor failed for command key: $cmdKey"
        log.error(e, errorMsg)
        Status.Failure(CmdExecutorCreationFailed(e, errorMsg))
    })
  }

  def executorFor(cmdKey: CommandKey, needsIsolation: Boolean) = {
    if (msgKeyToExecutor.isDefinedAt(cmdKey.name)) Success(msgKeyToExecutor(cmdKey.name))
    else buildCmdActor(cmdKey, needsIsolation)
  }

  def buildCmdActor(cmdKey: CommandKey, needsIsolation: Boolean) = {
    for {
      cfg <- cbConfigBuilder.loadConfigForKey(cmdKey)
      dispatcherHolder <- dispatcherLookup.lookupDispatcher(cmdKey, cfg.isolation, needsIsolation)
    } yield {
      val ref = createExecutorActor(cmdKey, cfg, dispatcherHolder)
      log.debug("{}: created jaillcall executor with config: {}", cmdKey.name, configString(cfg))
      msgKeyToExecutor += cmdKey.name -> ref
      ref
    }
  }

  def configString(cfg: MsgConfig) =
    if (log.isDebugEnabled) cfg.rawConfigValue.root().render(ConfigRenderOptions.concise())
    else ""

  def createExecutorActor(msgKey: CommandKey, cfg: MsgConfig, dispatcherHolder: DispatcherHolder) = {
    context.actorOf(JailedCommandExecutor.props(msgKey, cfg, dispatcherHolder, metricsBus))
  }
}

object JailcallRootActor {

  def props(metricsBus: MetricsEventBus) = Props(new JailcallRootActor(metricsBus))

  case class CreateCmdExecutor(cmdKey: CommandKey, firstCmd: Option[JailedExecution[_, _]])

  object CreateCmdExecutor {

    def withKey(cmdKey: CommandKey) = CreateCmdExecutor(cmdKey, None)
  }

  case class CmdExecutorCreated(cmdKey: CommandKey, executor: ActorRef)

  case class CmdExecutorCreationFailed(e: Throwable, msg: String) extends RuntimeException(msg, e)

  case class NoCmdExecutorForThatKey(cmdKey: CommandKey)
}
