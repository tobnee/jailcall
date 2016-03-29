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
libraryDependencies += "net.atinu" %% "jailcall" % "0.1.0-SNAPSHOT"

// one of
libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.4.2"
libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.3.14"
```

## How it works
The user specifies a so-called `JailedExecution` for every remote call which should be protected. The `JailedExecution`
is a wrapper for this operation, giving it an identity in the form of a `CommandKey`. A `ScalaFutureCommand` is a
implementation of a `JailedExecution`, which builds the command name based on the name of the command class.

One possible command would be to get repository names from a user at Github using the Github-API.

```scala
import net.atinu.jailcall._

case class UserRepos(user: String, repos: List[String])

class GitHubApiCall(user: String) extends ScalaFutureCommand[UserRepos] {

  def execute = getGitHupRepos()
    
  def getGitHupRepos() = ???
}
```

The command can then be instantiated and executed.

```scala
import scala.concurrent.Future
import akka.actor.ActorSystem

object JailcallApp extends App {
  val system = ActorSystem("JailcallSystem")
  val jailcall = Jailcall(system).executor
  
  val repos: Future[UserRepos] = 
      jailcall.executeToFuture(new GitHubApiCall("tobnee"))
}
```

For each command type a dedicated Actor is created to manage the execution of commands. During processing *jailcall* 
collects statistics about processing times as well as error statistics. If the execution of a single command takes too 
long *jailcall* will kill the supervised execution (timeout). If the command execution for a given type will result in 
too many errors in the current rolling time window (20 seconds by default), *jailcall* will prevent future command 
executions from being started (circuit breaker). The default for those cases is to report this error back to the caller, 
together with the information when the command can be executed again.
