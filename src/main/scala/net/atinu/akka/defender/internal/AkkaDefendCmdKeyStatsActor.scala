package net.atinu.akka.defender.internal

import akka.actor.{ ActorLogging, Props, Actor }
import net.atinu.akka.defender.DefendCommandKey
import net.atinu.akka.defender.internal.AkkaDefendCmdKeyStatsActor.{ CmdKeyStatsSnapshot, UpdateStats }
import org.HdrHistogram.Histogram

class AkkaDefendCmdKeyStatsActor(cmdKey: DefendCommandKey) extends Actor with ActorLogging {

  val execTime = new Histogram(600000L, 2)

  def receive = {
    case UpdateStats(time) =>
      execTime.recordValue(time)
      val stats = CmdKeyStatsSnapshot(
        cmdKey,
        execTime.getValueAtPercentile(50),
        execTime.getValueAtPercentile(95),
        execTime.getValueAtPercentile(99)
      )
      log.debug("current cmd key stats {}", stats)
      context.parent ! stats
  }
}

object AkkaDefendCmdKeyStatsActor {

  def props(cmdKey: DefendCommandKey) = Props(new AkkaDefendCmdKeyStatsActor(cmdKey))

  case class UpdateStats(execTimeMs: Long)

  case class CmdKeyStatsSnapshot(cmdKey: DefendCommandKey, median: Long, p95Time: Long, p99Time: Long)
}
