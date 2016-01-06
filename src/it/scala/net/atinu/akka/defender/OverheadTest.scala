package net.atinu.akka.defender

import java.util.concurrent.TimeUnit

import akka.actor.{Status, Scheduler}
import com.typesafe.config.ConfigFactory
import net.atinu.akka.defender.OverheadTest.TestExec
import net.atinu.akka.defender.util.ActorTest
import org.scalatest.concurrent.Futures

import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Random, Success, Try}

class OverheadTest extends ActorTest("DefenderTest", OverheadTest.config) with Futures {

  val ad = AkkaDefender(system)
  import system.dispatcher

  test("overhead succ") {
    val samplePre = TestExec.sample("check2", 50, Success(5), system.scheduler, system.dispatcher, 150)
    val nrOfCommands = 1500
    val sample = TestExec.sample("check", 50, Success(5), system.scheduler, system.dispatcher, nrOfCommands)
    //println(sample)
    runCheckedSample(samplePre)
    val start = System.currentTimeMillis()
    runCheckedSample(sample.headOption.toVector)
    Thread.sleep(2000)
    runSamplePar(sample.tail, 20)
    Thread.sleep(2000)

    whenReady(ad.defender.statsFor(sample.head.cmdKey)) { stats =>
      val end = System.currentTimeMillis() - start - 4000
      val msPerCmd = end / nrOfCommands
      println(s"succ: took $end ms, msCmdAvg $msPerCmd ms, stats: \n$stats")
    }
  }

  test("overhead timeout break") {
    callBreak("check-break", 20)
  }

  test("overhead open break") {
    callBreak("check-break-2", 100)
  }

  def callBreak(key: String, nrOfCommands: Int): Unit = {
    val samplePre = TestExec.sample(key, 50, Success(4), system.scheduler, system.dispatcher, nrOfCommands)
    val sample = TestExec.breakSample(key, 500, system.scheduler, system.dispatcher, nrOfCommands)
    runCheckedSample(samplePre)
    val start = System.currentTimeMillis()
    runCheckedSample(sample.headOption.toVector)
    Thread.sleep(2000)
    runSamplePar(sample.tail, 20)
    Thread.sleep(2000)

    whenReady(ad.defender.statsFor(sample.head.cmdKey)) { stats =>
      val end = System.currentTimeMillis() - start - 4000
      val msPerCmd = end / nrOfCommands
      println(s"$key: took $end ms, msCmdAvg $msPerCmd ms, stats: \n$stats\n")
    }
  }

  def runSamplePar(sample: IndexedSeq[TestExec], par: Int) = {
    for(cmdBatch <- sample.iterator.grouped(par)) {
      runCheckedSample(cmdBatch)
    }
  }

  def runCheckedSample(cmdBatch: Seq[TestExec]): Unit = {
    runBatch(cmdBatch)
    for (_ <- cmdBatch) {
      expectMsgPF(hint = "succ or failure") {
        case v: Int => "ok"
        case Status.Failure(e) => "ok"
      }
    }
  }

  def runBatch(cmdBatch: Seq[TestExec]): Unit = {
    for (cmd <- cmdBatch) {
      ad.defender.executeToRef(cmd)
    }
  }
}

object OverheadTest {
  val config =
    ConfigFactory.parseString(
      """defender {
        |  command {
        |    check-break {
        |      circuit-breaker {
        |        max-failures = 1000000,
        |        call-timeout = 500 millis,
        |        reset-timeout = 5 seconds
        |      }
        |    }
        |    check-break-2 {
        |      circuit-breaker {
        |        max-failures = 1,
        |        call-timeout = 500 millis,
        |        reset-timeout = 5 seconds
        |      }
        |    }
        |   }
        |  }
        |""".stripMargin
    )

  class TestExec(key: String, delay: FiniteDuration, result: Try[Int], scheduler: Scheduler, ec: ExecutionContext) extends AsyncDefendExecution[Int] {
    def cmdKey: DefendCommandKey = DefendCommandKey(key)

    def execute: Future[Int] = {
      val p = Promise.apply[Int]()
      scheduler.scheduleOnce(delay) {
        p.complete(result)
      }(ec)
      p.future
    }

    override def toString = s"$key -> $delay"
  }

  object TestExec {
    import scala.concurrent.duration._


    def sampleWithBreak(key: String, delayAvgMs: Int, result: Try[Int], scheduler: Scheduler, ec: ExecutionContext, nrOfCommands: Int) = {

    }

    def breakSample(key: String, breakMin: Int, scheduler: Scheduler, ec: ExecutionContext, nrOfCommands: Int) = {
      val time = (breakMin + 10).millis
      val succ = Success(0)
      val cmd = new TestExec(key, time, succ, scheduler, ec)
      Vector.fill(nrOfCommands)(cmd)
    }

    def sample(key: String, delayAvgMs: Int, result: Try[Int], scheduler: Scheduler, ec: ExecutionContext, nrOfCommands: Int) = {
      val base = Vector.range(1, delayAvgMs * 2)

      @tailrec
      def spin(start: Int, curr: Vector[Int]): Vector[Int] = start match {
        case 0 => curr
        case n => spin(n - 1, curr ++ base)
      }

      def min = {
        val chopElements = base.size - nrOfCommands
        base.take(base.size-chopElements).drop(chopElements)
      }

      val msDistribution = if(delayAvgMs > nrOfCommands) {
        min
      } else {
        spin(nrOfCommands / base.size, Vector.empty) ++ base.take(nrOfCommands % base.size)
      }


      for(ms <- msDistribution) yield new TestExec(key, ms.millis, result, scheduler, ec)
    }

  }
}
