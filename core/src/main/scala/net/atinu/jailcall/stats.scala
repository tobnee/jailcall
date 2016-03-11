package net.atinu.jailcall

case class CmdKeyStatsSnapshot(cmdKey: CommandKey, latencyStats: LatencyStats, callStats: CallStats) {

  override def toString = {
    Vector(
      "mean" -> latencyStats.mean,
      "callMedian" -> latencyStats.median,
      "callP95" -> latencyStats.p95Time,
      "callP99" -> latencyStats.p99Time,
      "meanDefendOverhead" -> latencyStats.meanDefendOverhead,
      "countSucc" -> callStats.succCount,
      "countError" -> callStats.failureCount,
      "countCbOpen" -> callStats.ciruitBreakerOpenCount,
      "countTimeout" -> callStats.timeoutCount,
      "errorPercent" -> callStats.errorPercent
    ).mkString(", ")
  }
}

object CmdKeyStatsSnapshot {

  def initial(key: CommandKey) = {
    CmdKeyStatsSnapshot(key, LatencyStats.initial, CallStats.initial)
  }
}

object CallStats {

  val initial = CallStats(0, 0, 0, 0, 0)
}

case class CallStats(succCount: Long, failureCount: Long, ciruitBreakerOpenCount: Long, timeoutCount: Long, badRequest: Long) {

  val errorCount: Long = failureCount + ciruitBreakerOpenCount + timeoutCount

  val validRequestCount: Long = errorCount + succCount

  val totalCount: Long = validRequestCount + badRequest

  val errorPercent: Int = {
    if (validRequestCount > 0) (errorCount.toDouble / validRequestCount.toDouble * 100).toInt
    else 0
  }
}

object LatencyStats {

  val initial = LatencyStats(0, 0, 0, 0, 0)
}

case class LatencyStats(mean: Double, median: Long, p95Time: Long, p99Time: Long, meanDefendOverhead: Double)

