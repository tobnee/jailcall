package net.atinu.akka.defender.internal.util

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
    while (i < elems.size) {
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

}
