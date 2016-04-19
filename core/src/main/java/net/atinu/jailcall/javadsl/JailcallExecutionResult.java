package net.atinu.jailcall.javadsl;

import akka.actor.ActorRef;

import java.util.Objects;
import java.util.Optional;

public final class JailcallExecutionResult<T> {

    public static <T> JailcallExecutionResult<T> of(T result) {
        return new JailcallExecutionResult<>(result, Optional.empty());
    }

    public static <T> JailcallExecutionResult<T> of(T result, ActorRef sender) {
        return new JailcallExecutionResult<>(result, Optional.of(sender));
    }

    private final T result;
    private final Optional<ActorRef> sender;

    public JailcallExecutionResult(T result, Optional<ActorRef> sender) {
        this.result = result;
        this.sender = sender;
    }

    public T getResult() {
        return result;
    }

    public Optional<ActorRef> getSender() {
        return sender;
    }

    @Override
    public String toString() {
        return "JailcallExecutionResult{" +
                "result=" + result +
                ", sender=" + sender +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JailcallExecutionResult<?> that = (JailcallExecutionResult<?>) o;
        return Objects.equals(result, that.result) &&
                Objects.equals(sender, that.sender);
    }

    @Override
    public int hashCode() {
        return Objects.hash(result, sender);
    }
}
