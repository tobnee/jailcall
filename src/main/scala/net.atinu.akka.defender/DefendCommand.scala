package net.atinu.akka.defender

import scala.concurrent.Future

trait DefendCommand[T] {

  def cmdKey: String

  def execute: Future[T]
}

trait StaticFallback[T] { self: DefendCommand[T] =>

  def fallback: T
}

trait CmdFallback[T] { self: DefendCommand[T] =>

  def fallback: DefendCommand[T]
}