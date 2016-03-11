package net.atinu.jailcall

import java.util.concurrent.ConcurrentHashMap

import akka.actor.ActorRef
import akka.event.{ LookupClassification, EventBus }

/**
 * Subscribe to metrics updates by CommandKey
 */
class MetricsEventBus private[jailcall] () extends EventBus with LookupClassification {
  type Event = CmdKeyStatsSnapshot
  type Classifier = CommandKey
  type Subscriber = ActorRef

  override protected val mapSize: Int = 64

  private val metricsState = new ConcurrentHashMap[String, Event](mapSize)

  private[jailcall] def statsFor(cmdKey: CommandKey) =
    metricsState.getOrDefault(cmdKey.name, CmdKeyStatsSnapshot.initial(cmdKey))

  override protected def publish(event: Event, subscriber: Subscriber): Unit = {
    subscriber ! event
  }

  override protected def classify(event: Event): Classifier = {
    val cmdKey = event.cmdKey
    metricsState.put(cmdKey.name, event)
    cmdKey
  }

  override protected def compareSubscribers(a: Subscriber, b: Subscriber): Int = a.compareTo(b)
}
