package net.atinu.jailcall.javadsl;

import akka.actor.ActorSystem;
import akka.actor.Extension;

public final class Jailcall implements Extension {

    public static Jailcall get(ActorSystem system) {
        return new Jailcall((net.atinu.jailcall.Jailcall) net.atinu.jailcall.Jailcall.get(system));
    }

    private final net.atinu.jailcall.Jailcall wrappedExtension;

    private Jailcall(net.atinu.jailcall.Jailcall wrappedExtension) {
        this.wrappedExtension = wrappedExtension;
    }

    public JailcallExecutor executor() {
        return new JailcallExecutor(this.wrappedExtension.ice());
    }

}
