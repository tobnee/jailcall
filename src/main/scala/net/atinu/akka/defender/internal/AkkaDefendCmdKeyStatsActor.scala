package net.atinu.akka.defender.internal

import akka.actor.{ ActorLogging, Props, Actor }
import net.atinu.akka.defender.internal.AkkaDefendCmdKeyStatsActor.{ CmdKeyStatsSnapshot, UpdateStats }
import org.HdrHistogram.Histogram

class AkkaDefendCmdKeyStatsActor extends Actor with ActorLogging {

  val execTime = new Histogram(600000L, 2)

  def receive = {
    case UpdateStats(time) =>
      execTime.recordValue(time)
      val stats = CmdKeyStatsSnapshot(
        execTime.getValueAtPercentile(50),
        execTime.getValueAtPercentile(95),
        execTime.getValueAtPercentile(99)
      )
      log.debug("current cmd key stats {}", stats)
  }
}

object AkkaDefendCmdKeyStatsActor {

  def props = Props(new AkkaDefendCmdKeyStatsActor)

  case class UpdateStats(execTimeMs: Long)

  case class CmdKeyStatsSnapshot(medianTime: Long, p95Time: Long, p99Time: Long)
}
