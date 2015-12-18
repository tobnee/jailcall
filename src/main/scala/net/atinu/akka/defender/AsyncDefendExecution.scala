package net.atinu.akka.defender

import scala.concurrent.Future

trait NamedCommand[R] {

  def cmdKey: DefendCommandKey
}

trait DefendExecution[E] {

  def execute: E
}

trait AsyncDefendExecution[R] extends NamedCommand[R] with DefendExecution[Future[R]]

trait SyncDefendExecution[R] extends NamedCommand[R] with DefendExecution[R]

trait StaticFallback[R] { self: NamedCommand[R] =>

  def fallback: R
}

trait CmdFallback[R] { self: NamedCommand[R] =>

  def fallback: NamedCommand[R]
}