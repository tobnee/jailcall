package net.atinu.jailcall

import akka.actor.{Scheduler, Status}
import com.typesafe.config.ConfigFactory
import net.atinu.jailcall.OverheadTest.{TestExecAsync, TestExec}
import net.atinu.jailcall.util.ActorTestIt
import org.scalatest.concurrent.Futures

import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Success, Try}

class OverheadTest extends ActorTestIt("OverheadTest", OverheadTest.config) with Futures {

  val ad = Jailcall(system)

  test("overhead succ") {
    callAutomatedOverheadTest("check", 350, 1500,
      (key, nr) => TestExec.sample(key, 50, Success(5), system.scheduler, system.dispatcher, nr))
  }

  test("overhead succ sync") {
    callAutomatedOverheadTest("check-sync", 350, 1500,
      (key, nr) => TestExec.sample(key, 50, Success(4), system.scheduler, system.dispatcher, nr, isSync = true))
  }

  test("overhead timeout break") {
    callBreak("check-break", 350, 700)
  }

  test("overhead open break") {
    callBreak("check-break-2", 350, 700)
  }

  test("overhead mixed") {
    import scala.concurrent.duration._
    val key = "mixed"
    val v1 = TestExec.sample(key, 50, Success(5), system.scheduler, system.dispatcher, 1000) ++
      TestExec.breakSample(key, 300, system.scheduler, system.dispatcher, 250) ++
      TestExec.breakSample(key, 1000, system.scheduler, system.dispatcher, 250)

    val sample = Vector(new TestExecAsync(key, 50.millis, Success(5), system.scheduler, system.dispatcher)) ++
      scala.util.Random.shuffle(v1)

    callOverheadTest("mixed", sample)
  }

  def callBreak(key: String, nrOfSamples: Int, nrOfCommands: Int): Unit = {
    callAutomatedOverheadTest(key, 10, nrOfCommands,
      (key, nr) => TestExec.breakSample(key, 500, system.scheduler, system.dispatcher, nr))
  }

  def callAutomatedOverheadTest(key: String, nrOfSamples: Int, nrOfCommands: Int, generator: (String, Int) => Vector[JailedExecution[_]]) = {
    val samplePre = generator.apply(key+"-sample", nrOfSamples)
    val sample = generator.apply(key, nrOfCommands)

    runCheckedSample(samplePre)
    callOverheadTest(key, sample)
  }

  def callOverheadTest(key: String, sample: Vector[JailedExecution[_]]): Unit = {
    val start = System.currentTimeMillis()
    runCheckedSample(sample.headOption.toVector)
    Thread.sleep(2000)
    runSamplePar(sample.tail, 20)
    Thread.sleep(2000)

    val stats = ad.executor.statsFor(sample.head.cmdKey)
    val end = System.currentTimeMillis() - start - 4000
    val msPerCmd = end / sample.length
    println(s"\nrunning $key: took $end ms, msCmdAvg $msPerCmd ms, stats: \n$stats")

  }

  def runSamplePar(sample: IndexedSeq[JailedExecution[_]], par: Int) = {
    for(cmdBatch <- sample.iterator.grouped(par)) {
      runCheckedSample(cmdBatch)
    }
  }

  def runCheckedSample(cmdBatch: Seq[JailedExecution[_]]): Unit = {
    runBatch(cmdBatch)
    for (_ <- cmdBatch) {
      expectMsgPF(hint = "succ or failure") {
        case JailcallExecutionResult(v: Int, _) => "ok"
        case Status.Failure(e) => "ok"
      }
    }
  }

  def runBatch(cmdBatch: Seq[JailedExecution[_]]): Unit = {
    for (cmd <- cmdBatch) {
      ad.executor.executeToRef(cmd)
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
        |        enabled = false
        |        call-timeout = 200 millis,
        |        reset-timeout = 5 seconds
        |      }
        |    }
        |    check-break-2 {
        |      circuit-breaker {
        |        call-timeout = 500 millis,
        |        reset-timeout = 5 seconds
        |      }
        |    }
        |    mixed {
        |      circuit-breaker {
        |        call-timeout = 500 millis,
        |        reset-timeout = 5 seconds
        |      }
        |    }
        |   }
        |  }
        |""".stripMargin
    )

  abstract class TestExec(key: String, delay: FiniteDuration, result: Try[Int], scheduler: Scheduler, ec: ExecutionContext) extends NamedCommand {
    def cmdKey: CommandKey = CommandKey(key)

    def call(): Future[Int] = {
      val p = Promise.apply[Int]()
      scheduler.scheduleOnce(delay) {
        p.complete(result)
      }(ec)
      p.future
    }

    override def toString = s"$key -> $delay"
  }

  class TestExecAsync(key: String, delay: FiniteDuration, result: Try[Int], scheduler: Scheduler, ec: ExecutionContext)
    extends TestExec(key, delay, result, scheduler, ec) with ScalaFutureExecution[Int] {

    def execute: Future[Int] = call()

  }

  class TestExecSync(key: String, delay: FiniteDuration, result: Try[Int], scheduler: Scheduler, ec: ExecutionContext)
    extends TestExec(key, delay, result, scheduler, ec) with BlockingExecution[Int] {
    import scala.concurrent.duration._


    def execute = Await.result(call(), 20.seconds)
  }

  object TestExec {
    import scala.concurrent.duration._

    def breakSample(key: String, breakMin: Int, scheduler: Scheduler, ec: ExecutionContext, nrOfCommands: Int) = {
      val time = (breakMin + 10).millis
      val succ = Success(0)
      val cmd = new TestExecAsync(key, time, succ, scheduler, ec)
      Vector.fill(nrOfCommands)(cmd)
    }

    def sample(key: String, delayAvgMs: Int, result: Try[Int], scheduler: Scheduler, ec: ExecutionContext, nrOfCommands: Int, isSync: Boolean = false) = {
      val base = Vector.range(1, delayAvgMs * 2)

      @tailrec
      def spin(start: Int, curr: Vector[Int]): Vector[Int] = start match {
        case 0 => curr
        case n => spin(n - 1, curr ++ base)
      }

      def min = {
        val chopElements = base.size - nrOfCommands
        base.slice(chopElements, base.size - chopElements)
      }

      val msDistribution =
        if(delayAvgMs > nrOfCommands) min
        else spin(nrOfCommands / base.size, Vector.empty) ++ base.take(nrOfCommands % base.size)

      for(ms <- msDistribution) yield {
        if(isSync) new TestExecSync(key, ms.millis, result, scheduler, ec)
        else new TestExecAsync(key, ms.millis, result, scheduler, ec)
      }
    }

  }
}
