package net.atinu.jailcall.internal.util

import net.atinu.jailcall.CallStats
import net.atinu.jailcall.internal.util.RollingStats.StatsBucket

class RollingStats(val size: Int) {

  require(size > 0, "size must be greater than 0")

  private val elems: Array[StatsBucket] = Array.fill(size)(new StatsBucket)
  private var currentStep: Int = 0
  private val sumBucket = new StatsBucket()

  def roll() = {
    currentStep = (currentStep + 1) % size
    current.initialize
  }

  private def current = {
    elems(currentStep)
  }

  def sum: CallStats = {
    val sb = sumBucket
    sb.initialize
    var i = 0
    while (i < size) {
      sb += elems.apply(i)
      i += 1
    }
    sb.toCallStats
  }

  def recordSuccess(): RollingStats = {
    current.succ_++
    this
  }

  def recordError(): RollingStats = {
    current.fail_++
    this
  }

  def recordCbOpen(): RollingStats = {
    current.cb_++
    this
  }

  def recordBadRequest(): RollingStats = {
    current.br_++
    this
  }

  def recordTimeout(): RollingStats = {
    current.to_++
    this
  }
}

object RollingStats {

  def withSize(size: Int) = new RollingStats(size)

  private class StatsBucket(var succ: Long = 0, var failure: Long = 0, var cb: Long = 0, var to: Long = 0, var br: Long = 0) {

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
