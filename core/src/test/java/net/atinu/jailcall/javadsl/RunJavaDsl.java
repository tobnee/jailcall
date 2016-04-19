package net.atinu.jailcall.javadsl;

import akka.actor.ActorSystem;
import akka.actor.Status;
import akka.dispatch.Futures;
import akka.testkit.JavaTestKit;
import junit.framework.Assert;
import net.atinu.jailcall.BlockingCommand;
import net.atinu.jailcall.ScalaFutureCommand;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.concurrent.Future;

import java.util.Optional;

public class RunJavaDsl {

    private static ActorSystem system;

    @Test
    public void testDsl() throws Exception {
        new JavaTestKit(system) {{
            final JavaTestKit sender = new JavaTestKit(system);

            JailcallExecutor executor = Jailcall.get(system).executor();
            executor.executeToRef(new MyCommand(), sender.getRef());
            sender.expectMsgAllOf(JailcallExecutionResult.of("hey jo"));

            executor.executeToRef(new MyFutureCommand(), sender.getRef());
            sender.expectMsgAllOf(JailcallExecutionResult.of("abc"));

            executor.executeToRef(new MyFutureFailureCommand(), sender.getRef());
            Status.Failure res = sender.expectMsgClass(Status.Failure.class);
            Assert.assertTrue("is JEE", res.cause() instanceof JailcallExecutionException);
            JailcallExecutionException cause = (JailcallExecutionException) res.cause();
            Assert.assertEquals(Optional.empty(), cause.getOriginalSender());

            JavaTestKit.shutdownActorSystem(system);
        }};
    }

    @BeforeClass
    public static void setup() {
        system = ActorSystem.create();
    }

    @AfterClass
    public static void teardown() {
        JavaTestKit.shutdownActorSystem(system);
        system = null;
    }

    public static class MyCommand extends BlockingCommand<String> {

        @Override
        public String execute() {
            return "hey jo";
        }
    }

    public static class MyFutureCommand extends ScalaFutureCommand<String> {

        @Override
        public Future<String> execute() {
            return Futures.successful("abc");
        }
    }

    public static class MyFutureFailureCommand extends ScalaFutureCommand<String> {

        @Override
        public Future<String> execute() {
            return Futures.failed(new IllegalStateException("ohhh"));
        }
    }
}
