package net.atinu.jailcall

import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.{ ExecutionContext, Future }

trait NamedCommand {

  def cmdKey: CommandKey
}

/**
 * Parent class for all execution types supported by jailcall.
 *
 * @tparam RC result type of the execution
 */
sealed trait JailedExecution[RC] extends NamedCommand {

  type R = RC

  /** container type of the execution result */
  type E

  def execute: E
}

/**
 * A [[JailedExecution]] based on a [[scala.concurrent.Future]]
 *
 * @tparam RC result type of the execution
 */
trait ScalaFutureExecution[RC] extends NamedCommand with JailedExecution[RC] {

  type E = Future[RC]

}

/**
 * A [[JailedExecution]] based on a blocking operation
 *
 * @tparam RC result type of the execution
 */
trait BlockingExecution[RC] extends NamedCommand with JailedExecution[RC] {

  type E = RC
}

/**
 * Provides a fallback for a failed [[JailedExecution]]
 */
trait CmdFallback { self: JailedExecution[_] =>

  def fallback: JailedExecution[R @uncheckedVariance]
}

object ScalaFutureExecution {

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