# akka-defender

Defend your applications from failures in distributed systems through the use:

- Call stats based circuit breakers
- Bulkheading of commands
- Histrogram supported timeout baselining (planned)

## How it works

*akka-defender* will create one `AkkaDefendExecutor` actor per command type, which is determined by the command key.
`AkkaDefendExecutor` is responsible for managing the command flow and collecting command metrics during the process.
Each `AkkaDefendExecutor` has a dedicated `AkkaDefendCmdKeyStatsActor`, which will take the metrics data (call stats and
latencies), aggregate it and report it back to the parent `AkkaDefendExecutor`. Those aggregated statistics are the
basis for opening a circuit breaker once a relevant number of calls has failed to succeed during the last x seconds. 
The circuit breaker will then stop allowing calls and only close again if one of the intermediary test calls succeeds.

For an individual call the `AkkaDefendExecutor` ensures that it will only be allowed to run a configured time slot. If
a command execution fails to do so, it will either fail or return a specified fallback value or even the result of a 
fallback command.

If an individual command is blocking, *akka-defender* can limit its resource consumption and avoid other parts of the 
system from going down (bulkheading).

## Related Projects
- [akka-core](http://doc.akka.io/docs/akka/2.4.1/common/circuitbreaker.html): Already has a minimal circuit breaker implementation
- [hystrix](https://github.com/Netflix/Hystrix): A lot of general ideas and patterns have been adopted from the hystrix library
