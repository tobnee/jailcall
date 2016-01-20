package net.atinu.jailcall

import scala.concurrent.Future

trait NamedCommand {

  def cmdKey: CommandKey
}

sealed trait JailedExecution[+R, +E] extends NamedCommand {

  def execute: E
}

trait AsyncJailedExecution[+R] extends NamedCommand with JailedExecution[R, Future[R]]

trait SyncJailedExecution[+R] extends NamedCommand with JailedExecution[R, R]

trait StaticFallback[+R] { self: JailedExecution[R, _] =>

  def fallback: R
}

trait CmdFallback[+R] { self: JailedExecution[R, _] =>

  def fallback: JailedExecution[R, _]
}

trait SuccessCategorization[R] { self: JailedExecution[R, _] =>

  def categorize: PartialFunction[R, SuccessCategory]
}