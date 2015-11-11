package net.atinu.akka.defender

import scala.concurrent.Future

trait DefendCommand[T] {

  def cmdKey: String

  def execute: Future[T]
}
