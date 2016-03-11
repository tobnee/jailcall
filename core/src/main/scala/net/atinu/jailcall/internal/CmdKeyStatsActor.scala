package net.atinu.jailcall.internal

import akka.actor.{ Actor, ActorLogging, Props }
import net.atinu.jailcall.internal.CmdKeyStatsActor._
import net.atinu.jailcall.internal.util.RollingStats
import net.atinu.jailcall.{ MetricsEventBus, CmdKeyStatsSnapshot, CommandKey }
import org.HdrHistogram.Histogram

class CmdKeyStatsActor(cmdKey: CommandKey, metrics: MetricsConfig, metricsBus: MetricsEventBus) extends Actor with ActorLogging {
  import context.dispatcher

  val execTime = new Histogram(600000L, 1)
  val totalTime = new Histogram(600000L, 1)
  val rollingStats = RollingStats.withSize(metrics.rollingStatsBuckets)
  var updateSinceLastSnapshot = false
  var currentStats = CmdKeyStatsSnapshot.initial(cmdKey)

  val interval = metrics.rollingStatsWindowDuration / metrics.rollingStatsBuckets
  context.system.scheduler.schedule(interval, interval, self, RollStats)

  def receive = {
    case r: ReportSuccCall =>
      updateStats(r.execTimeMs, r.totalTimeMs, r.metricType)
    case r: ReportErrorCall =>
      updateStats(r.execTimeMs, r.totalTimeMs, r.metricType)
    case ReportCircuitBreakerOpenCall =>
      updateStats(ReportCircuitBreakerOpenCall.metricType)
    case r: ReportTimeoutCall =>
      updateStats(r.execTimeMs, r.totalTimeMs, r.metricType)
    case r: ReportBadRequestCall =>
      updateStats(r.execTimeMs, r.totalTimeMs, r.metricType)
    case RollStats =>
      rollAndNotifyIfUpdated()
    case GetCurrentStats =>
      sender() ! currentStats
  }

  def updateStats(execTimeMs: Long, totalTimeMs: Long, metricType: MetricType): Unit = {
    execTime.recordValue(execTimeMs)
    totalTime.recordValue(totalTimeMs)
    updateStats(metricType)
    updateSinceLastSnapshot = true
  }

  def updateStats(metricType: MetricType): Unit = {
    metricType match {
      case Succ => rollingStats.recordSuccess()
      case Err => rollingStats.recordError()
      case Timeout => rollingStats.recordTimeout()
      case CircuitBreakerOpen => rollingStats.recordCbOpen()
      case BadRequest => rollingStats.recordBadRequest()
    }
  }

  def rollAndNotifyIfUpdated() = {
    if (updateSinceLastSnapshot) {
      publishSnapshotUpdate()
      updateSinceLastSnapshot = false
    }
    rollingStats.roll()
  }

  def publishSnapshotUpdate(): Unit = {
    val meanExec = execTime.getMean
    val meanTotal = totalTime.getMean
    val diffMeanTotal = meanTotal - meanExec

    val stats = CmdKeyStatsSnapshot(
      cmdKey,
      execTime.getMean,
      execTime.getValueAtPercentile(50),
      execTime.getValueAtPercentile(95),
      execTime.getValueAtPercentile(99),
      diffMeanTotal,
      rollingStats.sum
    )
    def overhead = if (log.isDebugEnabled) {
      val diffMeanPercent = if (meanTotal == 0d) 0 else (1 - meanExec / meanTotal) * 100
      s"($diffMeanTotal ms, $diffMeanPercent %)"
    }
    log.debug("{}: current cmd key stats {}, overhead defend exec {}", cmdKey, stats, overhead)
    context.parent ! stats
    metricsBus.publish(stats)
    currentStats = stats
  }
}

object CmdKeyStatsActor {

  def props(cmdKey: CommandKey, metrics: MetricsConfig, metricsBus: MetricsEventBus) = Props(new CmdKeyStatsActor(cmdKey, metrics, metricsBus))

  sealed abstract class MetricReportCommand(val metricType: MetricType)
  case class ReportSuccCall(execTimeMs: Long, totalTimeMs: Long) extends MetricReportCommand(Succ)
  case class ReportErrorCall(execTimeMs: Long, totalTimeMs: Long) extends MetricReportCommand(Err)
  case object ReportCircuitBreakerOpenCall extends MetricReportCommand(CircuitBreakerOpen)
  case class ReportTimeoutCall(execTimeMs: Long, totalTimeMs: Long) extends MetricReportCommand(Timeout)
  case class ReportBadRequestCall(execTimeMs: Long, totalTimeMs: Long) extends MetricReportCommand(BadRequest)

  case object GetCurrentStats

  sealed trait MetricType
  case object Succ extends MetricType
  case object Err extends MetricType
  case object Timeout extends MetricType
  case object BadRequest extends MetricType
  case object CircuitBreakerOpen extends MetricType

  case object RollStats

}
