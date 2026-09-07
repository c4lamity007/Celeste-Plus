package dev.c4lam.celeste.compat;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Payload-owned state keyed by game object identity; values must not refer back to their owners. */
final class WeakIdentityStates<T> {
    private final ReferenceQueue<Object> retired = new ReferenceQueue<>();
    private final Map<Key, T> values = new HashMap<>();
    private final Supplier<T> factory;

    WeakIdentityStates(Supplier<T> factory) { this.factory = Objects.requireNonNull(factory, "factory"); }

    synchronized T get(Object owner) {
        Objects.requireNonNull(owner, "owner");
        prune();
        T value = values.get(new Key(owner));
        if (value == null) {
            value = Objects.requireNonNull(factory.get(), "state");
            values.put(new Key(owner, retired), value);
        }
        return value;
    }

    synchronized void clear() {
        values.clear();
        prune();
    }

    private void prune() {
        Key key;
        while ((key = (Key) retired.poll()) != null) values.remove(key);
    }

    private static final class Key extends WeakReference<Object> {
        private final int hash;

        private Key(Object owner) { super(owner); hash = System.identityHashCode(owner); }
        private Key(Object owner, ReferenceQueue<Object> queue) { super(owner, queue); hash = System.identityHashCode(owner); }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            Object owner = get();
            return owner != null && other instanceof Key key && owner == key.get();
        }
    }
}
