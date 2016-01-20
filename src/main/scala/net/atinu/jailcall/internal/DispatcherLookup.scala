package net.atinu.jailcall.internal

import akka.dispatch.{ Dispatchers, MessageDispatcher }
import net.atinu.jailcall.internal.DispatcherLookup.DispatcherHolder
import net.atinu.jailcall.{ CommandKey, Jailcall }

import scala.util.Try

object DispatcherLookup {

  case class DispatcherHolder(dispatcher: MessageDispatcher, isDefault: Boolean) {

    def isCustom = !isDefault
  }
}

private[internal] class DispatcherLookup(dispatchers: Dispatchers) {

  def lookupDispatcher(msgKey: CommandKey, isoConfig: IsolationConfig, needsIsolation: Boolean): Try[DispatcherHolder] = Try {
    isoConfig.custom match {
      case Some(cfg) =>
        DispatcherHolder(dispatchers.lookup(cfg.dispatcherName), isDefault = false)

      case _ if needsIsolation =>
        DispatcherHolder(dispatchers.lookup(Jailcall.JAILCALL_DISPATCHER_ID), isDefault = false)

      case _ =>
        DispatcherHolder(dispatchers.defaultGlobalDispatcher, isDefault = true)
    }
  }
}