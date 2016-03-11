package net.atinu.jailcall

case class CmdKeyStatsSnapshot(cmdKey: CommandKey, mean: Double, median: Long, p95Time: Long, p99Time: Long, meanDefendOverhead: Double, callStats: CallStats) {

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

  private val stats: CallStats = CallStats(0, 0, 0, 0, 0)

  def initial(key: CommandKey) = {
    CmdKeyStatsSnapshot(key, 0, 0, 0, 0, 0, stats)
  }
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

