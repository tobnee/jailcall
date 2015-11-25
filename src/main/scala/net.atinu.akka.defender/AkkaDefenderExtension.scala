package net.atinu.akka.defender

import akka.actor._
import net.atinu.akka.defender.internal.AkkaDefendActor

import scala.concurrent.Future

object AkkaDefender extends ExtensionId[AkkaDefenderExtension] {
  def createExtension(system: ExtendedActorSystem): AkkaDefenderExtension =
    new AkkaDefenderExtension(system)
}

class AkkaDefenderExtension(val system: ExtendedActorSystem) extends Extension with ExtensionIdProvider {

  private val rootActorName = system.settings.config.getString("defender.root-actor-name")
  private val defenderRef = system.systemActorOf(AkkaDefendActor.props, rootActorName)

  val defender = new AkkaDefender(defenderRef)

  def lookup(): ExtensionId[_ <: Extension] = AkkaDefender
}

class AkkaDefender private[defender](defenderRef: ActorRef) {

  def executeToRef[T](cmd: NamedCommand[T])(implicit sender: ActorRef = Actor.noSender): Unit = {
    defenderRef ! cmd
  }

  def executeToFuture[T](cmd: NamedCommand[T]): Future[T] = {
    ???
  }
}