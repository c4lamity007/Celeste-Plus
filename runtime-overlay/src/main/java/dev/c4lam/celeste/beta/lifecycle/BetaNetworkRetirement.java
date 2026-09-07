package dev.c4lam.celeste.beta.lifecycle;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/** Observes completed removal from game-owned event loops before payload retirement. */
public final class BetaNetworkRetirement {
    private static final Set<CompletableFuture<Void>> pending = ConcurrentHashMap.newKeySet();
    private BetaNetworkRetirement() { }

    public static void track(CompletableFuture<Void> completion) {
        pending.add(completion);
        completion.whenComplete((unused, failure) -> {
            if (failure == null) pending.remove(completion);
        });
    }

    public static CompletionStage<Void> drained() {
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).minimalCompletionStage();
    }
}
