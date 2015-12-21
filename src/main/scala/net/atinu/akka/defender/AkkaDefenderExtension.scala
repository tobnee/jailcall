package net.atinu.akka.defender

import java.util.concurrent.{ TimeUnit, ConcurrentHashMap }

import akka.actor._
import akka.util.Timeout
import net.atinu.akka.defender.internal.AkkaDefendActor
import net.atinu.akka.defender.internal.AkkaDefendActor.{ CmdExecutorCreated, CreateCmdExecutor }
import scala.util.{ Failure, Success }

import scala.concurrent.{ ExecutionContext, Future }

object AkkaDefender extends ExtensionId[AkkaDefenderExtension] {
  def createExtension(system: ExtendedActorSystem): AkkaDefenderExtension =
    new AkkaDefenderExtension(system)
}

class AkkaDefenderExtension(val system: ExtendedActorSystem) extends Extension with ExtensionIdProvider {

  private val rootActorName = system.settings.config.getString("defender.root-actor-name")
  private val defenderRef = system.systemActorOf(AkkaDefendActor.props, rootActorName)

  val defender = new AkkaDefender(defenderRef, system.dispatcher)

  def lookup(): ExtensionId[_ <: Extension] = AkkaDefender
}

class AkkaDefender private[defender] (defenderRef: ActorRef, ec: ExecutionContext) {
  import akka.pattern.ask

  private val createTimeout = Timeout(1, TimeUnit.SECONDS)
  private val refCache = new ConcurrentHashMap[String, ActorRef]

  def executeToRef(cmd: DefendExecution[_, _])(implicit sender: ActorRef = Actor.noSender): Unit = {
    val name: String = cmd.cmdKey.name
    if (refCache.contains(name)) {
      refCache.get(name) ! cmd
    } else {
      defenderRef.ask(CreateCmdExecutor(cmd.cmdKey))(createTimeout).onComplete {
        case Success(created: CmdExecutorCreated) =>
          val executor: ActorRef = created.executor
          executor ! cmd
          refCache.put(name, executor)
        case Failure(e) => throw e
      }(ec)
    }
  }

  def executeToFuture[R, _](cmd: DefendExecution[R, _]): Future[R] = {
    ???
  }
}