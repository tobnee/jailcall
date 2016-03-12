package net.atinu.jailcall

import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.{ ExecutionContext, Future }

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

trait CmdFallback { self: JailedExecution[_] =>

  def fallback: JailedExecution[R @uncheckedVariance]
}

object AsyncJailedExecution {

  def filterBadRequest[T](in: Future[T])(isBadRequest: T => Boolean)(implicit ec: ExecutionContext): Future[T] = {
    in.flatMap {
      case x if isBadRequest(x) => Future.failed(BadRequestException.apply("result $x categorized as bad request"))
      case ok => in
    }
  }

  def categorizeResult[T](in: Future[T])(cat: PartialFunction[T, ResultCategory])(implicit ec: ExecutionContext): Future[T] = {
    in.flatMap { res =>
      cat.applyOrElse(res, (x: T) => IsSuccess) match {
        case IsSuccess => Future.successful(res)
        case IsBadRequest => Future.failed(BadRequestException.apply("result $res categorized as bad request"))
      }
    }
  }
}