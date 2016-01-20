package akka.jailcall

import java.util.UUID

import akka.dispatch.{ Dispatcher, DispatcherPrerequisites, MessageDispatcher, MessageDispatcherConfigurator }
import com.typesafe.config.Config

class JailcallDispatcherConfigurator(config: Config, prerequisites: DispatcherPrerequisites)
    extends MessageDispatcherConfigurator(config, prerequisites) {

  import akka.util.Helpers.ConfigOps

  override def dispatcher(): MessageDispatcher = {
    new Dispatcher(
      this,
      "akka-defend-bulkhead-" + UUID.randomUUID(),
      config.getInt("throughput"),
      config.getNanosDuration("throughput-deadline-time"),
      configureExecutor(),
      config.getMillisDuration("shutdown-timeout")
    )
  }

}
