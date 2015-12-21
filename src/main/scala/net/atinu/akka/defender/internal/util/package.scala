package net.atinu.akka.defender.internal

package object util {

  case class CallStats(succCount: Long, failureCount: Long, ciruitBreakerOpenCount: Long, timeoutCount: Long, badRequest: Long) {

    val errorCount: Long = failureCount + ciruitBreakerOpenCount + timeoutCount

    val validRequestCount: Long = errorCount + succCount

    val totalCount: Long = validRequestCount + badRequest

    val errorPercent: Int = {
      if (validRequestCount > 0) (errorCount.toDouble / validRequestCount.toDouble * 100).toInt
      else 0
    }
  }

  private[internal] class StatsBucket(var succ: Long = 0, var failure: Long = 0, var cb: Long = 0, var to: Long = 0, var br: Long = 0) {

    def succ_++ = succ += 1
    def fail_++ = failure += 1
    def cb_++ = cb += 1
    def to_++ = to += 1
    def br_++ = br += 1

    def succ_+(v: Long) = succ += v
    def fail_+(v: Long) = failure += v
    def cb_+(v: Long) = cb += v
    def to_+(v: Long) = to += v
    def br_+(v: Long) = br += v

    def +=(bucket: StatsBucket) = {
      succ += bucket.succ
      failure += bucket.failure
      cb += bucket.cb
      to += bucket.to
      br += bucket.br
    }

    def initialize = {
      succ = 0
      failure = 0
      cb = 0
      to = 0
      br = 0
    }

    def toCallStats = CallStats(succ, failure, cb, to, br)
  }

}
