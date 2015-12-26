package net.atinu.akka.defender.internal

import akka.dispatch.{ MessageDispatcher, Dispatchers }
import akka.event.LoggingAdapter
import net.atinu.akka.defender.{ AkkaDefender, DefendCommandKey }
import net.atinu.akka.defender.internal.DispatcherLookup.DispatcherHolder

object DispatcherLookup {

  case class DispatcherHolder(dispatcher: MessageDispatcher, isDefault: Boolean)
}

private[internal] class DispatcherLookup(dispatchers: Dispatchers) {

  def lookupDispatcher(msgKey: DefendCommandKey, msgConfig: MsgConfig, log: LoggingAdapter, needsIsolation: Boolean): DispatcherHolder = {
    msgConfig.isolation.custom match {
      case Some(cfg) if dispatchers.hasDispatcher(cfg.dispatcherName) =>
        DispatcherHolder(dispatchers.lookup(cfg.dispatcherName), isDefault = false)

      case Some(cfg) =>
        log.warning(
          "dispatcher {} was configured for cmd {} but not available, fallback to default dispatcher",
          cfg.dispatcherName, msgKey.name
        )
        DispatcherHolder(dispatchers.defaultGlobalDispatcher, isDefault = true)

      case _ if needsIsolation =>
        DispatcherHolder(dispatchers.lookup(AkkaDefender.DEFENDER_DISPATCHER_ID), isDefault = false)

      case _ =>
        DispatcherHolder(dispatchers.defaultGlobalDispatcher, isDefault = true)
    }
  }
}