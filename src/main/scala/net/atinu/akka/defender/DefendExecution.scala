package net.atinu.akka.defender

import scala.concurrent.Future

trait NamedCommand[T] {

  def cmdKey: DefendCommandKey
}

trait DefendExecution[T] extends NamedCommand[T] {

  def execute: Future[T]
}

trait SyncDefendExecution[T] extends NamedCommand[T] {

  def execute: T
}

trait StaticFallback[T] { self: NamedCommand[T] =>

  def fallback: T
}

trait CmdFallback[T] { self: NamedCommand[T] =>

  def fallback: NamedCommand[T]
}