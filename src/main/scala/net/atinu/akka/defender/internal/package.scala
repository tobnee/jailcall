package net.atinu.akka.defender

import scala.concurrent._
import scala.util.control.NoStackTrace
import scala.util.{ Failure, Success, Try }

package object internal {

  private[defender] case class JailedAction(startTime: Long, cmd: JailedExecution[_, _])

  object JailedAction {

    def now(cmd: JailedExecution[_, _]) = JailedAction(System.currentTimeMillis(), cmd)
  }

  private[defender] case class FallbackAction[T](fallbackPromise: Promise[T], startTime: Long, cmd: JailedExecution[T, _])

  private[defender] case class CmdMetrics(name: CommandKey)

  private[defender] class StatsResultException(val timeMs: Long, root: Throwable) extends RuntimeException(root) with NoStackTrace

  private[defender] case class StatsResult[T](timeMs: Long, res: T) {

    def withResult(res: T) = this.copy(res = res)

    def error(t: Throwable) = throw new StatsResultException(timeMs, t)
  }

  object StatsResult {

    def captureStart[T](res: Try[T], startMs: Long): Try[StatsResult[T]] = {
      val time = System.currentTimeMillis() - startMs
      res match {
        case Success(v) => Success(StatsResult(time, v))
        case Failure(e) => Failure(new StatsResultException(time, e))
      }
    }
  }
}
