## What does it do
If an application interacts with other applications via the network it is wise to protect the application from failures
like network hiccups or slow/unavailable peers. Akka already offers nice utilities to help with those issues like 
Dispatchers, Supervision, Schedulers, Circuit Breakers and Streams. While those abstractions are great and worth 
knowing, making you applications reliable can still be a cumbersome and time intensive task, which requires a certain 
depth in using Akka and related tools. For that reason **Jailcall** aims to provide a safe-by-default approach for 
distributed communication with Akka on top of existing Akka abstractions.

## How it works
The user specifies a so-called `JailedExecution` for every remote call which should be protected. The `JailedExecution`
is a wrapper for this operation, giving it an identity in the form of a `CommandKey`. A `(Sync/Aync)JailedCommand` is a
implementation of a `JailedExecution`, which builds the command name based on the of the command class.

One possible command would be to get repository names from a user at Github using the Github-API.

```tut:silent
import net.atinu.jailcall._

case class UserRepos(user: String, repos: List[String])

class GitHubApiCall(user: String) extends AsyncJailedCommand[UserRepos] {

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
    
    val repos: Future[JailcallExecutionResult[UserRepos]] = 
        Jailcall(system).executor.executeToFuture(new GitHubApiCall("tobnee"))
}
```

For each command type a dedicated Actor is created to manage the execution of commands. During processing *jailcall* 
collects statistics about processing times as well as error statistics. If the execution of a single command takes too 
long *jailcall* will kill the supervised execution (timeout). If the command execution for a given type will result in 
too many errors in the current rolling time window, *jailcall* will prevent future command executions from being started 
(circuit breaker). The default for those cases is to report this error back to the caller, together 
with the information when the command can be executed again.

## Fallbacks
If the command operation offers a meaningful default result if can make sense to specify a fallback in the command 
definition.

```tut:silent
import net.atinu.jailcall._

case class UserRepos(user: String, repos: List[String])

class GitHubApiCallWithDefault(user: String) extends AsyncJailedCommand[UserRepos] with StaticFallback {

    def execute = getGitHupRepos()
    
    def getGitHupRepos() = ???
    
    def fallback = UserRepos(user, Nil)

}
```

This will lead to a useful fallback result if the execution of a command is stopped due to an open circuit breaker or 
a timeout.

## Treat successful executions as failure
The default behaviour of *jailcall* is to treat a successful command execution as failure. But sometimes it requires 
additional logic to make the command processing aware of processing failures.

# Other
*jailcall* will create one `JailedCommandExecutor` actor per command type, which is determined by the command key.
The `JailedCommandExecutor` is responsible for managing the command flow and collecting command metrics during the process.
Each `JailedCommandExecutor` has a dedicated `CmdKeyStatsActor`, which will take the metrics data (call stats and
latencies), aggregate it and report it back to the parent `JailedCommandExecutor`. Those aggregated statistics are the
basis for opening a circuit breaker once a relevant number of calls has failed to succeed during the last x seconds. 
The circuit breaker will then stop allowing calls and only close again if one of the intermediary test calls succeeds.

For an individual call the `JailedCommandExecutor` ensures that it will only be allowed to run a configured time slot. If
a command execution fails to do so, it will either fail or return a specified fallback value or even the result of a 
fallback command.

If an individual command is blocking, *jailcall* automatically limits its resource consumption to avoid other parts of the
system to be affected (bulkheading).