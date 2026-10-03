package dev.c4lam.celeste.module.delivery;

import dev.celestebeta.host.VisualHookBridge;
import java.util.concurrent.CompletableFuture;

/** A render pass can outlive its opening callback; retirement waits for its matching finish. */
public final class ScopedHandPass implements VisualHookBridge.HandPass {
    private VisualHookBridge.HandPass delegate;
    private final CompletableFuture<Void> finished = new CompletableFuture<>();
    private ScopedHandPass(VisualHookBridge.HandPass delegate) { this.delegate = delegate; }
    public static VisualHookBridge.HandPass wrap(VisualHookBridge.HandPass pass) {
        if (pass == null) return null;
        ModuleScope scope = ModuleScope.current();
        if (scope == null) return pass;
        ScopedHandPass owned = new ScopedHandPass(pass);
        try { scope.ownTransient(() -> { }, owned.finished); }
        catch (RuntimeException failure) {
            try { owned.finish(false); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        return owned;
    }
    @Override public void finish(boolean success) {
        VisualHookBridge.HandPass current;
        synchronized (this) { current = delegate; delegate = null; }
        if (current == null) return;
        try {
            current.finish(success);
            finished.complete(null);
        } catch (RuntimeException | Error failure) {
            finished.completeExceptionally(failure);
            throw failure;
        }
    }
}
