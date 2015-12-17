package net.atinu.akka.defender.internal

package object util {

  case class CallStats(succCount: Long, errorCount: Long, ciruitBreakerOpenCount: Long, timeoutCount: Long) {

    val errorPercent: Int = {
      val ec = errorCount + ciruitBreakerOpenCount + timeoutCount
      val tc = succCount + ec
      if (tc > 0) (ec.toDouble / tc.toDouble * 100).toInt
      else 0
    }
  }

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

    def initialize = {
      succ = 0
      err = 0
      cb = 0
      to = 0
    }

    def toCallStats = CallStats(succ, err, cb, to)
  }

}
