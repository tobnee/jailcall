package net.atinu.akka.defender

import scala.concurrent.Future

trait NamedCommand {

  def cmdKey: DefendCommandKey
}

trait DefendExecution[+R, +E] extends NamedCommand {

  def execute: E
}

trait AsyncDefendExecution[+R] extends NamedCommand with DefendExecution[R, Future[R]]

trait SyncDefendExecution[+R] extends NamedCommand with DefendExecution[R, R]

trait StaticFallback[+R] { self: DefendExecution[R, _] =>

  def fallback: R
}

trait CmdFallback[+R] { self: DefendExecution[R, _] =>

  def fallback: DefendExecution[R, _]
}

trait SuccessCategorization[R] { self: DefendExecution[R, _] =>

  def categorize: PartialFunction[R, SuccessCategory]
}