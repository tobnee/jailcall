package net.atinu.akka.defender.internal

import akka.actor.{ Actor, ActorLogging, Props }
import net.atinu.akka.defender.DefendCommandKey
import net.atinu.akka.defender.internal.AkkaDefendCmdKeyStatsActor._
import net.atinu.akka.defender.internal.util.RollingStats
import org.HdrHistogram.Histogram
import scala.concurrent.duration._

class AkkaDefendCmdKeyStatsActor(cmdKey: DefendCommandKey) extends Actor with ActorLogging {
  import context.dispatcher

  val execTime = new Histogram(600000L, 1)
  val rollingStats = RollingStats.withSize(10)
  var updateSinceLastSnapshot = false

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
    case r: ReportBadRequestCall =>
      updateStats(r.execTimeMs, r.metricType)
    case RollStats =>
      rollAndNotifyIfUpdated()
  }

  def updateStats(timeMs: Long, metricType: MetricType): Unit = {
    execTime.recordValue(timeMs)
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
    val stats = CmdKeyStatsSnapshot(
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
  case class ReportSuccCall(execTimeMs: Long) extends MetricReportCommand(Succ)
  case class ReportErrorCall(execTimeMs: Long) extends MetricReportCommand(Err)
  case object ReportCircuitBreakerOpenCall extends MetricReportCommand(CircuitBreakerOpen)
  case class ReportTimeoutCall(execTimeMs: Long) extends MetricReportCommand(Timeout)
  case class ReportBadRequestCall(execTimeMs: Long) extends MetricReportCommand(BadRequest)

  sealed trait MetricType
  case object Succ extends MetricType
  case object Err extends MetricType
  case object Timeout extends MetricType
  case object BadRequest extends MetricType
  case object CircuitBreakerOpen extends MetricType

  case object RollStats

}
