package net.atinu.akka.defender

import net.atinu.akka.defender.internal.util.CallStats

import scala.concurrent._
import scala.util.control.NoStackTrace
import scala.util.{ Failure, Success, Try }

package object internal {

  private[defender] case class DefendAction(startTime: Long, cmd: DefendExecution[_, _])

  object DefendAction {

    def now(cmd: DefendExecution[_, _]) = DefendAction(System.currentTimeMillis(), cmd)
  }

  private[defender] case class FallbackAction[T](fallbackPromise: Promise[T], startTime: Long, cmd: DefendExecution[T, _])

  private[defender] case class CmdMetrics(name: DefendCommandKey)

  case class CmdKeyStatsSnapshot(mean: Double, median: Long, p95Time: Long, p99Time: Long, meanDefendOverhead: Double, callStats: CallStats) {

    override def toString = {
      Vector(
        "mean" -> mean,
        "callMedian" -> median,
        "callP95" -> p95Time,
        "callP99" -> p99Time,
        "meanDefendOverhead" -> meanDefendOverhead,
        "countSucc" -> callStats.succCount,
        "countError" -> callStats.failureCount,
        "countCbOpen" -> callStats.ciruitBreakerOpenCount,
        "countTimeout" -> callStats.timeoutCount,
        "errorPercent" -> callStats.errorPercent
      ).mkString(", ")
    }
  }

  object CmdKeyStatsSnapshot {

    val initial = CmdKeyStatsSnapshot(0, 0, 0, 0, 0, CallStats(0, 0, 0, 0, 0))
  }

  class StatsResultException(val timeMs: Long, root: Throwable) extends RuntimeException(root) with NoStackTrace

  case class StatsResult[T](timeMs: Long, res: T) {

    def withResult(res: T) = this.copy(res = res)

    def error(t: Throwable) = throw new StatsResultException(timeMs, t)
  }

  object StatsResult {

    def captureStart[T](res: Try[T], startMs: Long): Try[StatsResult[T]] = {
      val time = System.currentTimeMillis() - startMs
      res match {
        case Success(v) => Success(StatsResult(time, v))
        case Failure(e) => Failure(new StatsResultException(time, e))
      }
    }
  }
}
