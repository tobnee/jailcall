package net.atinu.jailcall.common

import scala.util.control.NoStackTrace

class BaseJailcallExecutionException(failure: Throwable)
  extends RuntimeException("jaillcall execution error", failure) with NoStackTrace
