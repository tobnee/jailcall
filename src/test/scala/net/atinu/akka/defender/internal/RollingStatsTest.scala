package net.atinu.akka.defender.internal

import net.atinu.akka.defender.internal.util.{ RollingStats, CallStats }
import org.scalatest.{ Matchers, FunSuite }

class RollingStatsTest extends FunSuite with Matchers {

  test("sum") {
    val rs = RollingStats.withSize(3)
    rs.recordSuccess()
    rs.recordSuccess()
    rs.recordError()

    val sum: CallStats = rs.sum
    sum.succCount should equal(2)
    sum.errorCount should equal(1)
  }

  test("sum roll") {
    val rs = RollingStats.withSize(3)
    rs.recordSuccess()
    rs.roll()
    rs.recordError()

    val sum: CallStats = rs.sum
    sum.succCount should equal(1)
    sum.errorCount should equal(1)
  }

  test("sum roll over") {
    val rs = RollingStats.withSize(3)
    rs.recordSuccess()
    rs.roll() // pos 1

    rs.sum.succCount should equal(1)

    rs.recordError()
    rs.recordSuccess()
    rs.roll() // pos 2

    rs.sum.succCount should equal(2)
    rs.sum.errorCount should equal(1)

    rs.recordCbOpen()
    rs.roll() // pos 0

    rs.recordCbOpen()
    rs.sum.succCount should equal(1)
    rs.sum.errorCount should equal(1)
    rs.sum.ciruitBreakerOpenCount should equal(2)
  }
}
