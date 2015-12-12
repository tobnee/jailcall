package net.atinu.akka.defender.internal

import akka.actor.{ Actor, ActorLogging, Props }
import net.atinu.akka.defender.DefendCommandKey
import net.atinu.akka.defender.internal.AkkaDefendCmdKeyStatsActor._
import net.atinu.akka.defender.internal.util.{RollingStats, CallStats}
import org.HdrHistogram.Histogram

class AkkaDefendCmdKeyStatsActor(cmdKey: DefendCommandKey) extends Actor with ActorLogging {
  import context.dispatcher

  import scala.concurrent.duration._

  val execTime = new Histogram(600000L, 2)
  val rollingStats = RollingStats.withSize(10)

  val interval = 1.second
  context.system.scheduler.schedule(interval, interval, self, RollStats)

  def receive = {
    case r: ReportSuccCall =>
      updateStats(r.execTimeMs, r.metricType)
    case r: ReportErrorCall =>
      updateStats(r.execTimeMs, r.metricType)
    case ReportCircuitBreakerOpenCall =>
      updateStats(ReportCircuitBreakerOpenCall.metricType)
    case r: ReportTimeoutCall =>
      updateStats(r.execTimeMs, r.metricType)
    case RollStats =>
      rollAndNotify()
  }

  def updateStats(timeMs: Long, metricType: MetricType): Unit = {
    execTime.recordValue(timeMs)
    updateStats(metricType)
  }

  def updateStats(metricType: MetricType): Unit = {
    metricType match {
      case SuccCall => rollingStats.recordSuccess()
      case ErrCall => rollingStats.recordError()
      case TimeoutCall => rollingStats.recordTimeout()
      case CircuitBreakerOpenCall => rollingStats.recordCbOpen()
    }
  }

  def rollAndNotify() = {
    publishSnapshotUpdate()
    rollingStats.roll()
  }

  def publishSnapshotUpdate(): Unit = {
    val stats = CmdKeyStatsSnapshot(
      cmdKey,
      execTime.getValueAtPercentile(50),
      execTime.getValueAtPercentile(95),
      execTime.getValueAtPercentile(99),
      rollingStats.sum
    )
    log.debug("current cmd key stats {}", stats)
    context.parent ! stats
  }
}

object AkkaDefendCmdKeyStatsActor {

  def props(cmdKey: DefendCommandKey) = Props(new AkkaDefendCmdKeyStatsActor(cmdKey))

  sealed abstract class MetricReportCommand(val metricType: MetricType)
  case class ReportSuccCall(execTimeMs: Long) extends MetricReportCommand(SuccCall)
  case class ReportErrorCall(execTimeMs: Long) extends MetricReportCommand(ErrCall)
  case object ReportCircuitBreakerOpenCall extends MetricReportCommand(CircuitBreakerOpenCall)
  case class ReportTimeoutCall(execTimeMs: Long) extends MetricReportCommand(TimeoutCall)

  case class CmdKeyStatsSnapshot(cmdKey: DefendCommandKey, median: Long, p95Time: Long, p99Time: Long, callStats: CallStats) {

    override def toString = {
      Vector(
        "cmdKey" -> cmdKey.name,
        "callMedian" -> median,
        "callP95" -> p95Time,
        "callP99" -> p99Time,
        "countSucc" -> callStats.succCount,
        "countError" -> callStats.errorCount,
        "countCbOpen" -> callStats.ciruitBreakerOpenCount,
        "countTimeout" -> callStats.timeoutCount,
        "errorPercent" -> callStats.errorPercent
      ).mkString(", ")
    }
  }

  sealed trait MetricType
  case object SuccCall extends MetricType
  case object ErrCall extends MetricType
  case object TimeoutCall extends MetricType
  case object CircuitBreakerOpenCall extends MetricType

  case object RollStats

}
