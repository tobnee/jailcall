package net.atinu.jailcall

import java.util.concurrent.TimeUnit

import akka.actor._
import akka.jailcall.JailcallDispatcherConfigurator
import net.atinu.jailcall.internal.{ InternalJailcallExecutor, JailcallRootActor }
import com.typesafe.config.Config

import scala.concurrent.duration.FiniteDuration

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

  private val metricsBus = new MetricsEventBus
  private val rootActorName = config.getString("jailcall.root-actor-name")
  private val jailcallRef = system.systemActorOf(JailcallRootActor.props(metricsBus), rootActorName)

  def loadMsDuration(key: String) = FiniteDuration(config.getDuration(key, TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)

  private val maxCallDuration = loadMsDuration("jailcall.max-call-duration")
  private val maxCreateTime = loadMsDuration("jailcall.create-timeout")
  private[jailcall] val ice = new InternalJailcallExecutor(jailcallRef, maxCreateTime, maxCallDuration, metricsBus, system.dispatcher)

  val executor = new JailcallExecutor(ice)

  def lookup(): ExtensionId[_ <: Extension] = Jailcall
}

