package net.atinu.akka.defender.internal

import com.typesafe.config.ConfigFactory
import net.atinu.akka.defender.util.ActorTest
import net.atinu.akka.defender.{ Jailcall, CommandKey }

class DispatcherLookupTest extends ActorTest("DispatcherLookupTest", DispatcherLookupTest.config) {

  val msgKey = CommandKey("aKey")
  val defendExt = Jailcall(system)

  test("the default dispatcher is loaded for a non blocking command") {
    val ld = new DispatcherLookup(system.dispatchers)
    val dh = ld.lookupDispatcher(msgKey, IsolationConfig.default, needsIsolation = false)
    dh should be a 'success
    dh.get should be a 'default
  }

  test("a new bulkheading dispatcher is loaded for a blocking command") {
    val ld = new DispatcherLookup(system.dispatchers)
    val dh = ld.lookupDispatcher(msgKey, IsolationConfig.default, needsIsolation = true)
    dh should be a 'success
    dh.get should be a 'custom
  }

  test("a custom dispatcher is loaded for a command") {
    val ld = new DispatcherLookup(system.dispatchers)
    val dh = ld.lookupDispatcher(msgKey, IsolationConfig.fromDispatcherName("a-dispatcher"), needsIsolation = true)
    dh should be a 'success
    dh.get should be a 'custom
  }

  test("lookup fails if dispatcher is not found") {
    val ld = new DispatcherLookup(system.dispatchers)
    val dh = ld.lookupDispatcher(msgKey, IsolationConfig.fromDispatcherName("not-a-dispatcher"), needsIsolation = true)
    dh should be a 'failure
  }
}

object DispatcherLookupTest {

  val config = ConfigFactory.parseString("""
                 | a-dispatcher = {
                 |      executor = "thread-pool-executor"
                 |      thread-pool-executor {
                 |        core-pool-size-min = 10
                 |        core-pool-size-max = 10
                 |      }
                 |      throughput = 1
                 |    }
                 |""".stripMargin)
}
