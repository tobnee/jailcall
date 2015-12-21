package net.atinu.akka.defender

import akka.pattern.AkkaDefendCircuitBreaker
import net.atinu.akka.defender.internal.DispatcherLookup.DispatcherHolder
import net.atinu.akka.defender.internal.util.CallStats

import scala.concurrent.Promise

package object internal {

  private[internal] case class CmdResources(circuitBreaker: AkkaDefendCircuitBreaker, cfg: MsgConfig,
    dispatcherHolder: DispatcherHolder)

  private[internal] case object GetKeyConfigs

  private[internal] case class FallbackAction(fallbackPromise: Promise[Any], cmd: DefendExecution[_, _])

  private[internal] case class CmdMetrics(name: DefendCommandKey)

  case class CmdKeyStatsSnapshot(median: Long, p95Time: Long, p99Time: Long, callStats: CallStats) {

    override def toString = {
      Vector(
        "callMedian" -> median,
        "callP95" -> p95Time,
        "callP99" -> p99Time,
        "countSucc" -> callStats.succCount,
        "countError" -> callStats.failureCount,
        "countCbOpen" -> callStats.ciruitBreakerOpenCount,
        "countTimeout" -> callStats.timeoutCount,
        "errorPercent" -> callStats.errorPercent
      ).mkString(", ")
    }
  }

  object CmdKeyStatsSnapshot {

    val initial = CmdKeyStatsSnapshot(0, 0, 0, CallStats(0, 0, 0, 0, 0))
  }

}
