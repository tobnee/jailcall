package net.atinu.akka.defender.internal

import akka.dispatch.{ MessageDispatcher, Dispatchers }
import akka.event.LoggingAdapter
import net.atinu.akka.defender.{ AkkaDefender, DefendCommandKey }
import net.atinu.akka.defender.internal.DispatcherLookup.DispatcherHolder

import scala.util.{ Success, Try }

object DispatcherLookup {

  case class DispatcherHolder(dispatcher: MessageDispatcher, isDefault: Boolean) {

    def isCustom = !isDefault
  }
}

private[internal] class DispatcherLookup(dispatchers: Dispatchers) {

  def lookupDispatcher(msgKey: DefendCommandKey, isoConfig: IsolationConfig, needsIsolation: Boolean): Try[DispatcherHolder] = Try {
    isoConfig.custom match {
      case Some(cfg) =>
        DispatcherHolder(dispatchers.lookup(cfg.dispatcherName), isDefault = false)

      case _ if needsIsolation =>
        DispatcherHolder(dispatchers.lookup(AkkaDefender.DEFENDER_DISPATCHER_ID), isDefault = false)

      case _ =>
        DispatcherHolder(dispatchers.defaultGlobalDispatcher, isDefault = true)
    }
  }
}