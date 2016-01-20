# jailcall

Defend your [Akka](https://github.com/akka/akka) applications from failures in distributed systems through the use of:

- Call stats based circuit breakers
- Automatic command bulkheading
- Insight into you remote calls by providing latency and call statistics

[![Build Status](https://travis-ci.org/tobnee/jailcall.svg?branch=master)](https://travis-ci.org/tobnee/akka-defender)
## How it works
The user specifies a so-called `JailedExecution` for every remote call which should be protected. The `JailedExecution`
is a wrapper for this operation, giving it an identity in the form of a `CommandKey`.

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

## Example

```scala
// within an actor
Jailcall(system).jailcall.executeToRef(new AsyncJailedExecution[String] {
  def cmdKey = CommandKey("a-foo-api-call")
  def execute: Future[String] = doFooApiCall()
})
```

## Related Projects
- [akka-core](http://doc.akka.io/docs/akka/2.4.1/common/circuitbreaker.html): Already has a minimal circuit breaker implementation
- [hystrix](https://github.com/Netflix/Hystrix): A lot of general ideas and patterns have been adopted from the hystrix library
