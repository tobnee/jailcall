package net.atinu.akka.defender

import java.util.concurrent.{ TimeUnit, ConcurrentHashMap }

import akka.actor._
import akka.defend.AkkaDefendDispatcherConfigurator
import akka.dispatch.Dispatchers
import akka.util.Timeout
import com.typesafe.config.Config
import net.atinu.akka.defender.internal.AkkaDefendActor
import net.atinu.akka.defender.internal.AkkaDefendActor.{ CmdExecutorCreated, CreateCmdExecutor }
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

  val defender = new AkkaDefender(defenderRef, system.dispatcher)

  def lookup(): ExtensionId[_ <: Extension] = AkkaDefender
}

class AkkaDefender private[defender] (defenderRef: ActorRef, ec: ExecutionContext) {

  import akka.pattern.ask

  private val createTimeout = Timeout(1, TimeUnit.SECONDS)
  private val execTimeout = Timeout(60, TimeUnit.SECONDS)
  private val refCache = new ConcurrentHashMap[String, ActorRef]

  def executeToRef(cmd: DefendExecution[_, _])(implicit sender: ActorRef = Actor.noSender): Unit = {
    val name: String = cmd.cmdKey.name
    if (refCache.contains(name)) {
      refCache.get(name) ! cmd
    } else {
      askCreateExecutor(cmd).onComplete {
        case Success(created: CmdExecutorCreated) =>
          val executor: ActorRef = created.executor
          executor ! cmd
          refCache.put(name, executor)
        case Failure(e) => // do nothing
      }(ec)
    }
  }

  def executeToFuture[R](cmd: DefendExecution[R, _])(implicit tag: ClassTag[R]): Future[R] = {
    val name: String = cmd.cmdKey.name
    def askInternal(ref: ActorRef) = ref.ask(cmd)(execTimeout).mapTo[R]
    if (refCache.contains(name)) {
      askInternal(refCache.get(name))
    } else {
      askCreateExecutor(cmd).flatMap { created =>
        val executor: ActorRef = created.executor
        refCache.put(name, executor)
        askInternal(executor)
      }(ec)
    }
  }

  private def askCreateExecutor(cmd: DefendExecution[_, _]): Future[CmdExecutorCreated] = {
    defenderRef.ask(CreateCmdExecutor(cmd.cmdKey, Some(cmd)))(createTimeout).mapTo[CmdExecutorCreated]
  }
}