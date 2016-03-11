## Example

### Create a `JailedCommand`
```tut:silent
import net.atinu.jailcall._

case class UserRepos(user: String, repos: List[String])

class GitHubApiCall(user: String) extends AsyncJailedCommand[UserRepos] {

  def execute = getGitHupRepos()
  
  def getGitHupRepos() = ???
}
```

### Create an anonymous `JailedExecution`
```tut:silent
def anonymousGitHubApiCall(user: String) = new AsyncJailedExecution[UserRepos] {

  def cmdKey = CommandKey("get-repos-for-user")

  def execute = getGitHupRepos()
  
  def getGitHupRepos() = ???
}
```

### Pipe a result to a future
Using the Future-API **jailcall** can be used outside actors or in situations where the actor does not need to transform command
results. 
```tut:silent
import scala.concurrent.Future
import akka.actor.ActorSystem

object JailcallApp extends App {
  val system = ActorSystem("JailcallSystem")
  
  Jailcall(system).executor.executeToFuture(new GitHubApiCall("tobnee"))
}
```

### Process result with an actor including transformation
Often it is useful to encapsulate the command execution within an actor. This has the advantage that the remote interaction
looks like a local actor interaction from the outside, since a protocol is the visible interface. This mode of operation also
allows for stateful command processing like in a command pipeline with several stages or retries using the `Scheduler` or a
`BackoffSupervisor`.
```tut:silent
import akka.actor.Actor
import akka.actor.Status

case class GetReposForUser(user: String)
case class GetUserReposResponse(repos: UserRepos)
case class GetUserReposFailedResponse(failure: Throwable)

class GithubApiActor(jailcall: JailcallExecutor) extends Actor {
  
  def receive = {
    case GetReposForUser(user) => 
      jailcall.executeToRefWithContext(new GitHubApiCall(user))

    case JailcallExecutionResult.Success(result: UserRepos, originalSender) =>
      originalSender ! GetUserReposResponse(result)
    
    case JailcallExecutionResult.FailureStatus(exception, originalSender) =>
      originalSender ! GetUserReposFailedResponse(exception)
  }
}
```

### Get stats for a command
*jailcall* offers you ways to query statistics about runtime behaviour given the `CommandKey` of the command in question 
```tut:silent
object JailcallApp extends App {
  val system = ActorSystem("JailcallSystem")
  val jailcall = Jailcall(system).executor
  
  val cmdKey = CommandKey("my-command")
  // create and call commands with that key ...
  
  val metrics: CmdKeyStatsSnapshot = jailcall.statsFor(cmdKey)
  
  // obtain stats about execution times
  val mean = metrics.latencyStats.mean
  val p95Time = metrics.latencyStats.p95Time
  
  // obtain stats about call statistics
  val failureCount = metrics.callStats.failureCount
}
```

### Subscribe to stats
```tut:silent
class StatsDashboardActor(jailcall: JailcallExecutor, cmdKey: CommandKey) extends Actor {
  jailcall.metricsBus.subscribe(self, cmdKey)

  def receive = {
    case metrics: CmdKeyStatsSnapshot => 
    // ...
  }
}
```