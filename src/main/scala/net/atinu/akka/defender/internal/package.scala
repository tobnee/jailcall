package net.atinu.akka.defender

import akka.pattern.AkkaDefendCircuitBreaker
import net.atinu.akka.defender.internal.DispatcherLookup.DispatcherHolder

import scala.concurrent.Promise

package object internal {

  private[internal] case class CmdResources(circuitBreaker: AkkaDefendCircuitBreaker, cfg: MsgConfig,
    dispatcherHolder: DispatcherHolder)

  private[internal] case object GetKeyConfigs

  private[internal] case class FallbackAction(fallbackPromise: Promise[Any], cmd: NamedCommand[_])

  private[internal] case class CmdMetrics(name: DefendCommandKey)

}
