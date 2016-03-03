package net.atinu.jailcall

import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.Future

trait NamedCommand {

  def cmdKey: CommandKey
}

sealed trait JailedExecution[RC] extends NamedCommand {

  type R = RC
  type E

  def execute: E
}

trait AsyncJailedExecution[RC] extends NamedCommand with JailedExecution[RC] {

  type E = Future[RC]

}

trait SyncJailedExecution[RC] extends NamedCommand with JailedExecution[RC] {

  type E = RC
}

trait StaticFallback { self: JailedExecution[_] =>

  def fallback: R
}

trait CmdFallback { self: JailedExecution[_] =>

  def fallback: JailedExecution[R @uncheckedVariance]
}

trait SuccessCategorization { self: JailedExecution[_] =>

  def categorize: PartialFunction[R @uncheckedVariance, ResultCategory]
}