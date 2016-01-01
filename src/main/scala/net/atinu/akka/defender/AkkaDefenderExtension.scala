package net.atinu.akka.defender

import java.util.concurrent.{ TimeUnit, ConcurrentHashMap }

import akka.actor._
import akka.defend.AkkaDefendDispatcherConfigurator
import akka.dispatch.Dispatchers
import akka.util.Timeout
import com.typesafe.config.Config
import net.atinu.akka.defender.internal.AkkaDefendExecutor.GetCurrentStats
import net.atinu.akka.defender.internal.{ CmdKeyStatsSnapshot, DefendAction, AkkaDefendActor }
import net.atinu.akka.defender.internal.AkkaDefendActor.{ CmdExecutorCreated, CreateCmdExecutor }
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
import scala.util.{ Failure, Success }

import scala.concurrent.{ ExecutionContext, Future }

object AkkaDefender extends ExtensionId[AkkaDefenderExtension] {
  def createExtension(system: ExtendedActorSystem): AkkaDefenderExtension =
    new AkkaDefenderExtension(system)

  private[defender] val DEFENDER_DISPATCHER_ID = "akka-defend-default"
}

class AkkaDefenderExtension(val system: ExtendedActorSystem) extends Extension with ExtensionIdProvider {

  private val config = system.settings.config
  private val dispatchers = system.dispatchers
  private val dispatcherConf: Config =
    config.getConfig("defender.isolation.default-dispatcher")
      .withFallback(config.getConfig("akka.actor.default-dispatcher"))

  system.dispatchers.registerConfigurator(
    AkkaDefender.DEFENDER_DISPATCHER_ID,
    new AkkaDefendDispatcherConfigurator(dispatcherConf, dispatchers.prerequisites)
  )

  private val rootActorName = config.getString("defender.root-actor-name")
  private val defenderRef = system.systemActorOf(AkkaDefendActor.props, rootActorName)

  def loadMsDuration(key: String) = FiniteDuration(config.getDuration(key, TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)

  private val maxCallDuration = loadMsDuration("defender.max-call-duration")
  private val maxCreateTime = loadMsDuration("defender.create-timeout")
  val defender = new AkkaDefender(defenderRef, maxCreateTime, maxCallDuration, system.dispatcher)

  def lookup(): ExtensionId[_ <: Extension] = AkkaDefender
}

class AkkaDefender private[defender] (defenderRef: ActorRef, maxCreateTime: FiniteDuration, maxCallDuration: FiniteDuration, ec: ExecutionContext) {

  import akka.pattern.ask

  private val createTimeout = new Timeout(maxCreateTime)
  private val execTimeout = new Timeout(maxCallDuration)
  private val refCache = new ConcurrentHashMap[String, ActorRef]

  def executeToRef(cmd: DefendExecution[_, _])(implicit sender: ActorRef = Actor.noSender): Unit = {
    val startTime = System.currentTimeMillis()
    val cmdKey = cmd.cmdKey.name
    if (refCache.containsKey(cmdKey)) {
      refCache.get(cmdKey) ! DefendAction(startTime, cmd)
    } else {
      askCreateExecutor(cmd).onComplete {
        case Success(created: CmdExecutorCreated) =>
          val executor = created.executor
          executor ! DefendAction(startTime, cmd)
          refCache.put(cmdKey, executor)
        case Failure(e) => // do nothing
      }(ec)
    }
  }

  def executeToFuture[R](cmd: DefendExecution[R, _])(implicit tag: ClassTag[R]): Future[R] = {
    val startTime = System.currentTimeMillis()
    val cmdKey = cmd.cmdKey.name
    def askInternal(ref: ActorRef) = ref.ask(DefendAction(startTime, cmd))(execTimeout).mapTo[R]
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

  private def askCreateExecutor(cmd: DefendExecution[_, _]): Future[CmdExecutorCreated] = {
    defenderRef.ask(CreateCmdExecutor(cmd.cmdKey, Some(cmd)))(createTimeout).mapTo[CmdExecutorCreated]
  }
}