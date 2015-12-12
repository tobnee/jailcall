package net.atinu.akka.defender.internal.util

class RollingStats(val size: Int) {

  require(size > 0, "size must be greater than 0")

  private val elems: Array[StatsBucket] = Array.fill(size)(new StatsBucket)
  private var currentStep: Int = 0

  def roll() = {
    currentStep = (currentStep + 1) % size
    current.initialize
  }

  private def current = {
    elems(currentStep)
  }

  def sum: CallStats = {
    val sumBucket = new StatsBucket()
    var i = 0
    while (i < elems.size) {
      sumBucket += elems.apply(i)
      i += 1
    }
    sumBucket.toCallStats
  }

  def recordSuccess(): RollingStats = {
    current.succ_++
    this
  }

  def recordError(): RollingStats = {
    current.err_++
    this
  }

  def recordCbOpen(): RollingStats = {
    current.cb_++
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
