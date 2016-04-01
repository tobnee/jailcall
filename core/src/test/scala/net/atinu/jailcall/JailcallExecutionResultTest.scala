package net.atinu.jailcall

import akka.NothingActorRef
import akka.actor.Status
import org.scalatest.{ FunSuite, Matchers }

class JailcallExecutionResultTest extends FunSuite with Matchers {

  test("success without sender - ok") {
    val foo = JailcallExecutionResult.fromResult("foo")
    val JailcallExecutionResult.Success(fooString) = foo
    fooString should equal("foo")
  }

  test("success with sender - ok") {
    val foo = JailcallExecutionResult.fromResult("foo", NothingActorRef)
    val JailcallExecutionResult.SuccessWithContext(fooString, ref) = foo
    fooString should equal("foo")
    ref should equal(NothingActorRef)
  }

  test("success without sender - failure") {
    val foo = JailcallExecutionResult.fromResult("foo")
    intercept[MatchError] {
      val JailcallExecutionResult.SuccessWithContext(fooString, ref) = foo
    }
  }

  test("failure without sender - ok") {
    val failure = new IllegalStateException("foo")
    val err = Status.Failure(JailcallExecutionException(failure, None))
    val JailcallExecutionResult.Failure(fooExcept) = err
    fooExcept should equal(failure)
  }

  test("failure with sender - ok") {
    val failure = new IllegalStateException("foo")
    val err = Status.Failure(JailcallExecutionException(failure, Some(NothingActorRef)))
    val JailcallExecutionResult.FailureWithContext(fooExcept, ref) = err
    fooExcept should equal(failure)
    ref should equal(NothingActorRef)
  }

  test("failure without sender - failure") {
    val failure = new IllegalStateException("foo")
    val err = Status.Failure(JailcallExecutionException(failure, None))
    intercept[MatchError] {
      val JailcallExecutionResult.FailureWithContext(fooExcept, ref) = err
    }
  }
}