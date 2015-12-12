package net.atinu.akka.defender.internal

import akka.actor.{ Actor, ActorLogging, Props }
import com.google.common.collect.EvictingQueue
import net.atinu.akka.defender.DefendCommandKey
import net.atinu.akka.defender.internal.AkkaDefendCmdKeyStatsActor._
import org.HdrHistogram.Histogram

class AkkaDefendCmdKeyStatsActor(cmdKey: DefendCommandKey) extends Actor with ActorLogging {
  import context.dispatcher
  import scala.concurrent.duration._

  val execTime = new Histogram(600000L, 2)
  val rollingStats = EvictingQueue.create[StatsBucket](10)
  var currentBucket = new StatsBucket()

  rollingStats.offer(currentBucket)

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
      case SuccCall => currentBucket.succ_++
      case ErrCall => currentBucket.err_++
      case TimeoutCall => currentBucket.to_++
      case CircuitBreakerOpenCall => currentBucket.cb_++
    }
  }

  def rollAndNotify() = {
    publishSnapshotUpdate()
    rollStats()
  }

  def rollStats() = {
    currentBucket = new StatsBucket()
    rollingStats.offer(currentBucket)
  }

  def sumCounts = {
    val sum = new StatsBucket()
    val rollingStatsIt = rollingStats.iterator()
    while (rollingStatsIt.hasNext) {
      sum += rollingStatsIt.next()
    }
    sum.toCallStats
  }

  def publishSnapshotUpdate(): Unit = {
    val counts = sumCounts
    val stats = CmdKeyStatsSnapshot(
      cmdKey,
      execTime.getValueAtPercentile(50),
      execTime.getValueAtPercentile(95),
      execTime.getValueAtPercentile(99),
      counts
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

  case class CallStats(succCount: Long, errorCount: Long, ciruitBreakerOpenCount: Long, timeoutCount: Long) {

    val errorPercent = {
      val ec = errorCount + ciruitBreakerOpenCount + timeoutCount
      val tc = succCount + ec
      if (tc > 0) (ec.toDouble / tc.toDouble * 100).toInt
      else 0
    }
  }

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

  private[internal] class StatsBucket(var succ: Long = 0, var err: Long = 0, var cb: Long = 0, var to: Long = 0) {
    def succ_++ = succ += 1
    def err_++ = err += 1
    def cb_++ = cb += 1
    def to_++ = to += 1

    def succ_+(v: Long) = succ += v
    def err_+(v: Long) = err += v
    def cb_+(v: Long) = cb += v
    def to_+(v: Long) = to += v

    def +=(bucket: StatsBucket) = {
      succ += bucket.succ
      err += bucket.err
      cb += bucket.cb
      to += bucket.to
    }

    def toCallStats = CallStats(succ, err, cb, to)
  }
}
