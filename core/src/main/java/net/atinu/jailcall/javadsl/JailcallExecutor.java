package net.atinu.jailcall.javadsl;

import akka.actor.ActorContext;
import akka.actor.ActorRef;
import net.atinu.jailcall.JailedExecution;
import net.atinu.jailcall.internal.InternalJailcallExecutor;
import scala.concurrent.Future;

public final class JailcallExecutor {

    private final InternalJailcallExecutor executor;

    JailcallExecutor(net.atinu.jailcall.internal.InternalJailcallExecutor executor) {
        this.executor = executor;
    }

    public void executeToRef(JailedExecution<?, ?> cmd, ActorRef receiver) {
        executor.executeToRef(cmd, true, receiver);
    }

    public void executeToRefWithContext(JailedExecution<?, ?> cmd, ActorRef receiver, ActorContext senderContext) {
        executor.executeToRefWithContext(cmd, true, receiver, senderContext);
    }

    public <T> Future<T> executeToFuture(JailedExecution<T, ?> cmd) {
        return executor.executeToFuture(cmd, true);
    }
}
