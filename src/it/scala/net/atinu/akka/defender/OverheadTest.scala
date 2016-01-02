package net.atinu.akka.defender

import java.util.concurrent.TimeUnit

import akka.actor.Scheduler
import com.typesafe.config.ConfigFactory
import net.atinu.akka.defender.OverheadTest.TestExec
import net.atinu.akka.defender.util.ActorTest
import org.scalatest.concurrent.Futures

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Random, Success, Try}

class OverheadTest extends ActorTest("DefenderTest", OverheadTest.config) with Futures {

  val ad = AkkaDefender(system)
  import system.dispatcher

  test("overhead") {
    val samplePre = TestExec.sample("check2", 50, Success(5), system.scheduler, system.dispatcher, 100)
    val sample = TestExec.sample("check", 50, Success(5), system.scheduler, system.dispatcher, 1500)
    //println(sample)
    runCheckedSample(samplePre)
    runCheckedSample(sample.headOption.toVector)
    Thread.sleep(2000)
    runSamplePar(sample.tail, 20)
    Thread.sleep(2000)

    whenReady(ad.defender.statsFor(sample.head.cmdKey)) { stats =>
      println(stats)
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
      expectMsgType[Int]
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
        |    check {
        |      circuit-breaker {
        |        max-failures = 2,
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


    def sample(key: String, delayAvgMs: Int, result: Try[Int], scheduler: Scheduler, ec: ExecutionContext, nrOfCommands: Int) = {
      val r = new Random()
      def delay = FiniteDuration.apply(r.nextInt(delayAvgMs * 2), TimeUnit.MILLISECONDS)

      for(_ <- 0 to nrOfCommands) yield new TestExec(key, delay, result, scheduler, ec)
    }

  }
}
