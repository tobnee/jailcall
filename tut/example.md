## Example

### Create a `JailedCommand`
```scala
import net.atinu.jailcall._
import scala.concurrent.Future

case class UserRepos(user: String, repos: List[String])

class GitHubApiCall(user: String) extends ScalaFutureCommand[UserRepos] {

  def execute = getGitHupRepos()
  
  def getGitHupRepos(): Future[UserRepos] = ???
}
```

### Create an anonymous `JailedExecution`
```scala
def anonymousGitHubApiCall(user: String) = new ScalaFutureExecution[UserRepos] {

  def cmdKey = CommandKey("get-repos-for-user")

  def execute = getGitHupRepos()
  
  def getGitHupRepos() = ???
}
```

### Pipe a result to a future
Using the Future-API **jailcall** can be used outside actors or in situations where the actor does not need to transform command
results. 
```scala
import akka.actor.ActorSystem

object JailcallApp extends App {
  val system = ActorSystem("JailcallSystem")
  implicit def executionContext = system.dispatcher
  
  Jailcall(system).executor.executeToFuture(new GitHubApiCall("tobnee"))
}
```

### Process result with an actor including transformation
Often it is useful to encapsulate the command execution within an actor. This has the advantage that the remote interaction
looks like a local actor interaction from the outside, since a protocol is the visible interface. This mode of operation also
allows for stateful command processing like in a command pipeline with several stages or retries using the `Scheduler` or a
`BackoffSupervisor`.
```scala
import akka.actor.Actor
import akka.actor.Status

case class GetReposForUser(user: String)
case class GetUserReposResponse(repos: UserRepos)
case class GetUserReposFailedResponse(failure: Throwable)

class GithubApiActor(jailcall: JailcallExecutor) extends Actor {
  // execution context for the command
  import context.dispatcher
  
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

### Create a `JailedCommand` with fallback
To increase the resiliency of a command it can make sense to provide fallback logic in case something goes wrong with a
command execution. Fallbacks in *jallcall* are also commands and will be processed with the same rules as others.
```scala
case class UserRepos(user: String, repos: List[String])

class GitHubApiCall(user: String, cacheLookup: ScalaFutureExecution[UserRepos]) extends 
  ScalaFutureCommand[UserRepos] with CmdFallback {

  def execute = getGitHupRepos()
  
  def getGitHupRepos(): Future[UserRepos] = ???
  
  def fallback = cacheLookup
}
```

## Filter bad requests
Sometimes you want to mark a command as a failure even if it is considered a success during normal command processing.
A good example is a HTTP BadRequest. In this case the `Future` will be executed successfully but the command is not
considered to be a success. Another situation where you would want to mark a command as bad request is when the command
yields a failure but the fallback logic should not be executed. In both cases a failed command with the result BadRequestException
signals *jailcall* that it should treat the result as a bad request. Bad requests are also not treated as failures in the
error statistics meaning that bad requests can not lead to open circuit breakers.
```scala
import scala.concurrent.ExecutionContext

case class SimpleHttpResponse(status: Int, body: String)

class GitHubApiCall(user: String)(implicit ec: ExecutionContext) extends ScalaFutureCommand[UserRepos] {
 
   def execute = ScalaFutureExecution
     .filterBadRequest(getGitHupRepos())(isBadRequest)
     .map(toUserRepo)
   
   def isBadRequest(req: SimpleHttpResponse): Boolean = req.status / 100 == 4 
   
   def getGitHupRepos(): Future[SimpleHttpResponse] = ???
   
   def toUserRepo(req: SimpleHttpResponse): UserRepos = ???
}
```

### Get stats for a command
*jailcall* offers you ways to query statistics about runtime behaviour given the `CommandKey` of the command in question 
```scala
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
```scala
class StatsDashboardActor(jailcall: JailcallExecutor, cmdKey: CommandKey) extends Actor {
  jailcall.metricsBus.subscribe(self, cmdKey)

  def receive = {
    case metrics: CmdKeyStatsSnapshot => 
    // ...
  }
}
```
