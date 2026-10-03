package dev.c4lam.celeste.module.delivery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Identity-based restoration for overlapping optional owners of a parent registry. */
public final class ScopedRegistry<K, V> {
    private final Map<K, V> registry;
    private final Map<K, Chain> chains = new HashMap<>();
    private final class Chain {
        final K key;
        final V baseline;
        final ArrayList<Lease> owners = new ArrayList<>();
        Chain(K key, V baseline) { this.key = key; this.baseline = baseline; }
    }
    public ScopedRegistry(Map<K, V> registry) { this.registry = Objects.requireNonNull(registry); }
    public synchronized Lease put(K key, V value) {
        Objects.requireNonNull(key); Objects.requireNonNull(value);
        Chain chain = chains.get(key);
        // A third party may have replaced this key without using our API. Its value becomes
        // a new baseline; closing an older owner must never resurrect obsolete replacements.
        if (chain == null || chain.owners.isEmpty() || registry.get(key) != chain.owners.getLast().value) {
            chain = new Chain(key, registry.get(key));
            chains.put(key, chain);
        }
        Lease lease = new Lease(chain, value);
        chain.owners.add(lease);
        registry.put(key, value);
        return lease;
    }
    public final class Lease implements AutoCloseable {
        private Chain chain;
        private V value;
        private Lease(Chain chain, V value) { this.chain = chain; this.value = value; }
        @Override public void close() {
            synchronized (ScopedRegistry.this) {
                if (chain == null) return;
                Chain owner = chain;
                owner.owners.remove(this);
                if (registry.get(owner.key) == value) {
                    V replacement = owner.owners.isEmpty() ? owner.baseline : owner.owners.getLast().value;
                    if (replacement == null) registry.remove(owner.key); else registry.put(owner.key, replacement);
                }
                if (owner.owners.isEmpty()) chains.remove(owner.key, owner);
                chain = null;
                value = null;
            }
        }
    }
}
