package net.atinu.jailcall.javadsl;

import akka.actor.ActorRef;
import net.atinu.jailcall.common.BaseJailcallExecutionException;

import java.util.Optional;

public class JailcallExecutionException extends BaseJailcallExecutionException {

    private final Optional<ActorRef> originalSender;

    public JailcallExecutionException(Throwable failure, Optional<ActorRef> originalSender) {
        super(failure);
        this.originalSender = originalSender;
    }

    public Optional<ActorRef> getOriginalSender() {
        return originalSender;
    }
}
