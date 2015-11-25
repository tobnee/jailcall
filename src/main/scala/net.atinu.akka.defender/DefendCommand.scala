package net.atinu.akka.defender

import scala.concurrent.Future

sealed trait NamedCommand[T] {

  def cmdKey: String
}

trait DefendCommand[T] extends NamedCommand[T] {

  def execute: Future[T]
}

trait SyncDefendCommand[T] extends NamedCommand[T] {

  def execute: T
}

trait StaticFallback[T] { self: NamedCommand[T] =>

  def fallback: T
}

trait CmdFallback[T] { self: NamedCommand[T] =>

  def fallback: DefendCommand[T]
}