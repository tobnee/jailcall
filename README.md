# jailcall

Defend your [Akka](https://github.com/akka/akka) applications from failures in distributed systems through the use of:

- Call stats based circuit breakers
- Automatic command bulkheading
- Insight into you remote calls by providing latency and call statistics

[![Build Status](https://travis-ci.org/tobnee/jailcall.svg?branch=master)](https://travis-ci.org/tobnee/akka-defender)

## Related Projects
- [akka-core](http://doc.akka.io/docs/akka/2.4.1/common/circuitbreaker.html): Already has a minimal circuit breaker implementation
- [hystrix](https://github.com/Netflix/Hystrix): A lot of general ideas and patterns have been adopted from the hystrix library