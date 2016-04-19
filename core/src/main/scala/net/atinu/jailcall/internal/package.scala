package net.atinu.jailcall

import akka.actor.ActorRef

import scala.concurrent.Promise
import scala.util.control.NoStackTrace
import scala.util.{ Failure, Success, Try }

package object internal {

  private[jailcall] case class JailedAction(startTime: Long, senderRef: Option[ActorRef], isJavaRequest: Boolean, cmd: JailedExecution[_, _])

  object JailedAction {

    def now[T](cmd: JailedExecution[T, _], isJavaRequest: Boolean = false) = JailedAction(System.currentTimeMillis(), None, isJavaRequest, cmd)
  }

  private[jailcall] case class FallbackAction[T](fallbackPromise: Promise[T], startTime: Long, cmd: JailedExecution[T, _])

  private[jailcall] case class CmdMetrics(name: CommandKey)

  private[jailcall] class StatsResultException(val timeMs: Long, root: Throwable) extends RuntimeException(root) with NoStackTrace

  private[jailcall] case class StatsResult[T](timeMs: Long, res: T) {

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
