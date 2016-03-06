package net.atinu.jailcall

import java.util.concurrent.{ TimeUnit, ConcurrentHashMap }

import akka.actor._
import akka.jailcall.JailcallDispatcherConfigurator
import akka.util.Timeout
import com.typesafe.config.Config
import net.atinu.jailcall.internal.JailedAction
import net.atinu.jailcall.internal.CmdKeyStatsActor.GetCurrentStats
import net.atinu.jailcall.internal.JailcallRootActor
import JailcallRootActor.{ CmdExecutorCreated, CreateCmdExecutor }
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
import scala.util.{ Try, Failure, Success }

import scala.concurrent.{ ExecutionContext, Future }

object Jailcall extends ExtensionId[Jailcall] {
  def createExtension(system: ExtendedActorSystem): Jailcall =
    new Jailcall(system)

  private[jailcall] val JAILCALL_DISPATCHER_ID = "jailcall-default"
}

class Jailcall(val system: ExtendedActorSystem) extends Extension with ExtensionIdProvider {

  private val config = system.settings.config
  private val dispatchers = system.dispatchers
  private val dispatcherConf: Config =
    config.getConfig("jailcall.isolation.default-dispatcher")
      .withFallback(config.getConfig("akka.actor.default-dispatcher"))

  system.dispatchers.registerConfigurator(
    Jailcall.JAILCALL_DISPATCHER_ID,
    new JailcallDispatcherConfigurator(dispatcherConf, dispatchers.prerequisites)
  )

  private val rootActorName = config.getString("jailcall.root-actor-name")
  private val jailcallRef = system.systemActorOf(JailcallRootActor.props, rootActorName)

  def loadMsDuration(key: String) = FiniteDuration(config.getDuration(key, TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)

  private val maxCallDuration = loadMsDuration("jailcall.max-call-duration")
  private val maxCreateTime = loadMsDuration("jailcall.create-timeout")
  val executor = new JailcallExecutor(jailcallRef, maxCreateTime, maxCallDuration, system.dispatcher)

  def lookup(): ExtensionId[_ <: Extension] = Jailcall
}

class JailcallExecutor private[jailcall] (jailcallRef: ActorRef, maxCreateTime: FiniteDuration, maxCallDuration: FiniteDuration, ec: ExecutionContext) {

  import akka.pattern.ask

  private val createTimeout = new Timeout(maxCreateTime)
  private val execTimeout = new Timeout(maxCallDuration)
  private val statsTimeout = new Timeout(60, TimeUnit.MICROSECONDS)
  private val refCache = new ConcurrentHashMap[String, ActorRef]

  def executeToRef(cmd: JailedExecution[_])(implicit receiver: ActorRef) = {
    val startTime = System.currentTimeMillis()
    val action = JailedAction(startTime, None, cmd)
    askRefInternal(cmd, action)
  }

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

  def executeToFuture[R](cmd: JailedExecution[R])(implicit tag: ClassTag[R]): Future[JailcallExecutionResult[R]] = {
    val startTime = System.currentTimeMillis()
    val cmdKey = cmd.cmdKey.name
    def askInternal(ref: ActorRef) = ref.ask(JailedAction(startTime, None, cmd))(execTimeout)
      .mapTo[JailcallExecutionResult[R]]
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

  def statsFor(key: CommandKey): Future[CmdKeyStatsSnapshot] = {
    val keyName = key.name
    if (refCache.containsKey(keyName)) {
      refCache.get(keyName).ask(GetCurrentStats)(statsTimeout).mapTo[CmdKeyStatsSnapshot]
    } else Future.successful(CmdKeyStatsSnapshot.initial)
  }
}