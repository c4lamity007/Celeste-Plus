package dev.c4lam.celeste.module.delivery;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiPredicate;

/** Resident named-operation router. Only declared host operations can hold child callbacks. */
public final class ModuleHookRegistry {
    static final Object UNAVAILABLE = new Object();
    @FunctionalInterface public interface Handler { Object invoke(Object[] arguments) throws Exception; }
    private record Key(String module, String operation) { }
    private final Map<String, Integer> operations = new HashMap<>();
    private final Map<Key, Registration> handlers = new HashMap<>();
    private final BiPredicate<String, Long> authorized;

    public ModuleHookRegistry(BiPredicate<String, Long> authorized) {
        this.authorized = Objects.requireNonNull(authorized);
        ModuleHookAbi.declare(this);
    }

    public synchronized void declare(String operation, int arity) {
        if (operation == null || operation.isBlank() || arity < 0)
            throw new IllegalArgumentException("Invalid module hook declaration");
        Integer previous = operations.putIfAbsent(operation, arity);
        if (previous != null && previous != arity) throw new IllegalStateException("Hook ABI changed");
    }

    public synchronized Registration connect(String module, long generation, String operation, Handler handler) {
        if (!operations.containsKey(operation)) throw new IllegalArgumentException("Unknown module hook: " + operation);
        Key key = new Key(Objects.requireNonNull(module), operation);
        if (handlers.containsKey(key)) throw new IllegalStateException("Module hook is already attached");
        Registration registration = new Registration(key, generation, Objects.requireNonNull(handler));
        handlers.put(key, registration);
        return registration;
    }

    /** Caller supplies a vanilla/disabled fallback. Module-specific values must never cross this ABI. */
    public Object invoke(String module, String operation, Object fallback, Object... arguments) throws Exception {
        Registration registration;
        synchronized (this) {
            Integer arity = operations.get(operation);
            if (arity == null || arguments.length != arity) throw new IllegalArgumentException("Invalid module hook call");
            registration = handlers.get(new Key(module, operation));
        }
        if (registration == null || !authorized.test(module, registration.generation)) return fallback;
        Handler handler;
        synchronized (registration) {
            if (registration.closed || registration.handler == null) return fallback;
            handler = registration.handler;
            registration.executing++;
        }
        try { Object result = handler.invoke(arguments); return result == UNAVAILABLE ? fallback : result; }
        finally {
            synchronized (registration) {
                registration.executing--;
                if (registration.closed && registration.executing == 0) registration.detached.complete(null);
            }
        }
    }

    public final class Registration implements AutoCloseable {
        private final Key key;
        private final long generation;
        private final CompletableFuture<Void> detached = new CompletableFuture<>();
        private Handler handler;
        private boolean closed;
        private int executing;
        private Registration(Key key, long generation, Handler handler) {
            this.key = key; this.generation = generation; this.handler = handler;
        }
        @Override public void close() {
            synchronized (ModuleHookRegistry.this) { handlers.remove(key, this); }
            synchronized (this) {
                if (closed) return;
                closed = true;
                handler = null;
                if (executing == 0) detached.complete(null);
            }
        }
        public CompletionStage<Void> detached() { return detached.minimalCompletionStage(); }
    }
}

