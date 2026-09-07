package dev.c4lam.celeste.beta.lifecycle;

import dev.celestebeta.host.DetachableCallbacks;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** One payload's optional late-runtime subscriptions; ordinary startup still registers directly. */
public final class BetaSubscriptions implements AutoCloseable {
    private static volatile BetaSubscriptions current;

    private final List<DetachableCallbacks.Registration> registrations = new ArrayList<>();
    private final CompletableFuture<Void> detached = new CompletableFuture<>();
    private boolean closed;
    private int attaching;
    private int retiring;

    private BetaSubscriptions() { }

    /** Called once before any late-runtime services register their listeners. */
    public static synchronized BetaSubscriptions open() {
        if (current != null) throw new IllegalStateException("The payload subscription scope is already configured");
        DetachableCallbacks.assertVisibleFrom(BetaSubscriptions.class.getClassLoader());
        current = new BetaSubscriptions();
        return current;
    }

    /** Owners/interfaces must come from the game; names stay stable between beta releases. */
    public static <T> void register(Object owner, String name, Class<T> type, T listener, Consumer<? super T> registrar) {
        Objects.requireNonNull(registrar, "registrar");
        BetaSubscriptions scope = current;
        if (scope == null) {
            registrar.accept(listener);
        } else {
            scope.attach(owner, name, type, listener, registrar);
        }
    }

    private <T> void attach(Object owner, String name, Class<T> type, T listener, Consumer<? super T> registrar) {
        synchronized (this) {
            if (closed) throw new IllegalStateException("The payload subscription scope is closed");
            attaching++;
        }
        try {
            DetachableCallbacks.assertVisibleFrom(type.getClassLoader());
            DetachableCallbacks.Registration registration = DetachableCallbacks.connect(owner, name, type, listener, registrar);
            boolean retire;
            synchronized (this) {
                retire = closed;
                if (retire) retiring++;
                else registrations.add(registration);
            }
            if (retire) {
                retire(registration);
                throw new IllegalStateException("The payload subscription scope closed during registration");
            }
        } finally {
            boolean drained;
            synchronized (this) { attaching--; drained = drained(); }
            if (drained) detached.complete(null);
        }
    }

    /** Stop new calls after feature shutdown. Executing calls finish normally; this never waits on them. */
    @Override public void close() {
        List<DetachableCallbacks.Registration> pending;
        boolean drained;
        synchronized (this) {
            if (closed) return;
            closed = true;
            pending = List.copyOf(registrations);
            registrations.clear();
            retiring += pending.size();
            drained = drained();
        }
        for (DetachableCallbacks.Registration registration : pending) retire(registration);
        if (drained) detached.complete(null);
    }

    private void retire(DetachableCallbacks.Registration registration) {
        registration.close();
        registration.detached().whenComplete((ignored, failure) -> {
            boolean drained;
            synchronized (this) { retiring--; drained = drained(); }
            if (failure != null) detached.completeExceptionally(failure);
            else if (drained) detached.complete(null);
        });
    }

    private boolean drained() { return closed && attaching == 0 && retiring == 0; }

    /** Await outside event callbacks, after close, before retiring the payload's retained bytes/classes. */
    public CompletionStage<Void> detached() { return detached.minimalCompletionStage(); }
}
