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
import scala.util.{ Failure, Success }

import scala.concurrent.{ ExecutionContext, Future }

object Jailcall extends ExtensionId[JailcallExtension] {
  def createExtension(system: ExtendedActorSystem): JailcallExtension =
    new JailcallExtension(system)

  private[jailcall] val JAILCALL_DISPATCHER_ID = "akka-defend-default"
}

class JailcallExtension(val system: ExtendedActorSystem) extends Extension with ExtensionIdProvider {

  private val config = system.settings.config
  private val dispatchers = system.dispatchers
  private val dispatcherConf: Config =
    config.getConfig("defender.isolation.default-dispatcher")
      .withFallback(config.getConfig("akka.actor.default-dispatcher"))

  system.dispatchers.registerConfigurator(
    Jailcall.JAILCALL_DISPATCHER_ID,
    new JailcallDispatcherConfigurator(dispatcherConf, dispatchers.prerequisites)
  )

  private val rootActorName = config.getString("defender.root-actor-name")
  private val jailcallRef = system.systemActorOf(JailcallRootActor.props, rootActorName)

  def loadMsDuration(key: String) = FiniteDuration(config.getDuration(key, TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)

  private val maxCallDuration = loadMsDuration("defender.max-call-duration")
  private val maxCreateTime = loadMsDuration("defender.create-timeout")
  val jailcall = new Jailcall(jailcallRef, maxCreateTime, maxCallDuration, system.dispatcher)

  def lookup(): ExtensionId[_ <: Extension] = Jailcall
}

class Jailcall private[jailcall] (jailcallRef: ActorRef, maxCreateTime: FiniteDuration, maxCallDuration: FiniteDuration, ec: ExecutionContext) {

  import akka.pattern.ask

  private val createTimeout = new Timeout(maxCreateTime)
  private val execTimeout = new Timeout(maxCallDuration)
  private val statsTimeout = new Timeout(60, TimeUnit.MICROSECONDS)
  private val refCache = new ConcurrentHashMap[String, ActorRef]

  def executeToRef(cmd: JailedExecution[_, _])(implicit sender: ActorRef = Actor.noSender): Unit = {
    val startTime = System.currentTimeMillis()
    val cmdKey = cmd.cmdKey.name
    if (refCache.containsKey(cmdKey)) {
      refCache.get(cmdKey) ! JailedAction(startTime, cmd)
    } else {
      askCreateExecutor(cmd).onComplete {
        case Success(created: CmdExecutorCreated) =>
          val executor = created.executor
          executor ! JailedAction(startTime, cmd)
          refCache.put(cmdKey, executor)
        case Failure(e) =>
          // unlikely to happen
          sender ! Status.Failure(e)
      }(ec)
    }
  }

  def executeToFuture[R](cmd: JailedExecution[R, _])(implicit tag: ClassTag[R]): Future[R] = {
    val startTime = System.currentTimeMillis()
    val cmdKey = cmd.cmdKey.name
    def askInternal(ref: ActorRef) = ref.ask(JailedAction(startTime, cmd))(execTimeout).mapTo[R]
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

  private def askCreateExecutor(cmd: JailedExecution[_, _]): Future[CmdExecutorCreated] = {
    jailcallRef.ask(CreateCmdExecutor(cmd.cmdKey, Some(cmd)))(createTimeout).mapTo[CmdExecutorCreated]
  }

  def statsFor(key: CommandKey): Future[CmdKeyStatsSnapshot] = {
    val keyName = key.name
    if (refCache.containsKey(keyName)) {
      refCache.get(keyName).ask(GetCurrentStats)(statsTimeout).mapTo[CmdKeyStatsSnapshot]
    } else Future.failed(new IllegalArgumentException(s"no executor for key '$key'"))
  }
}