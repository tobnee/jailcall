package net.atinu.akka.defender

import akka.pattern.AkkaDefendCircuitBreaker
import net.atinu.akka.defender.internal.DispatcherLookup.DispatcherHolder
import net.atinu.akka.defender.internal.util.CallStats

import scala.concurrent.Promise

package object internal {

  private[defender] case class CmdResources(circuitBreaker: AkkaDefendCircuitBreaker, cfg: MsgConfig,
    dispatcherHolder: DispatcherHolder)

  private[defender] case object GetKeyConfigs

  private[defender] case class DefendAction(startTime: Long, cmd: DefendExecution[_, _])

  object DefendAction {

    def now(cmd: DefendExecution[_, _]) = DefendAction(System.currentTimeMillis(), cmd)
  }

  private[defender] case class FallbackAction(fallbackPromise: Promise[Any], startTime: Long, cmd: DefendExecution[_, _])

  private[defender] case class CmdMetrics(name: DefendCommandKey)

  case class CmdKeyStatsSnapshot(mean: Double, median: Long, p95Time: Long, p99Time: Long, callStats: CallStats) {

    override def toString = {
      Vector(
        "mean" -> mean,
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

    val initial = CmdKeyStatsSnapshot(0, 0, 0, 0, CallStats(0, 0, 0, 0, 0))
  }

}
