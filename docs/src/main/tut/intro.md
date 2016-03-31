## What does it do
If an application interacts with other applications via the network it is wise to protect the application from failures
like network hiccups or slow/unavailable peers. Akka already offers nice utilities to help with those issues like 
Dispatchers, Supervision, Schedulers, Circuit Breakers and Streams. While those abstractions are great and worth 
knowing, making you applications reliable can still be a cumbersome and time intensive task, which requires a certain 
depth in using Akka and related tools. For that reason **jailcall** aims to provide a safe-by-default approach for 
distributed communication with Akka on top of existing Akka abstractions.

## How do you get it
The current release targets Scala 2.11.x together with the Akka 2.3.x and 2.4.x series.

```scala
libraryDependencies += "net.atinu" %% "jailcall" % "0.1.0"

// one of
libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.4.2"
libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.3.14"
```

## How it works
The user specifies a so-called `JailedExecution` for every remote call which should be protected. A `JailedExecution`
is combination of an operation and an identity in the form of a `CommandKey`. There are specific `JailedExecution` 
variants for certain classes of execution. For example the `ScalaFutureExecution` is the variant, which supports wrapping
a `scala.concurrent.Future` into a command. A `ScalaFutureCommand` is a implementation of a `ScalaFutureExecution`, which 
builds the `CommandKey` based on the name of the command class.

One possible command would be to get repository names from a user at Github using the Github-API.

```tut:silent
import net.atinu.jailcall._

case class UserRepos(user: String, repos: List[String])

class GitHubApiCall(user: String) extends ScalaFutureCommand[UserRepos] {

  def execute = getGitHupRepos()
    
  def getGitHupRepos() = ???
}
```

The command can then be instantiated and executed.

```tut:silent
import scala.concurrent.Future
import akka.actor.ActorSystem

object JailcallApp extends App {
  val system = ActorSystem("JailcallSystem")
  
  // get a JailcallExecutor from the Jailcall Akka extension
  val jailcall = Jailcall(system).executor
  
  // build an instance of a command which can be reused in future calls
  val jailcallExec = new GitHubApiCall("tobnee")
  
  // execute the command and obtain the result as a future
  // this might fail because of the timeout and circuit breaker 
  // logic provided by jailcall
  val repos: Future[UserRepos] = jailcall.executeToFuture(jailcallExec)
}
```

For each command type a dedicated Actor is created to manage the execution of commands. During processing **jailcall** 
collects statistics about processing times as well as error statistics. If the execution of a single command takes too 
long **jailcall** will kill the supervised execution (timeout). If the command execution for a given type will result in 
too many errors in the current rolling time window (20 seconds by default), **jailcall** will prevent future command 
executions from being started (circuit breaker). The default for those cases is to report this error back to the caller, 
together with the information when the command can be executed again.