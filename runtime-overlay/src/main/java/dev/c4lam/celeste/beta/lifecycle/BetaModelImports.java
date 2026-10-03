package dev.c4lam.celeste.beta.lifecycle;

import dev.c4lam.celeste.module.delivery.ModuleScope;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Tracks actual model parsing and its queued delivery before releasing a module's loader. */
public final class BetaModelImports {
    private static final Set<CompletableFuture<Void>> pending = new HashSet<>();
    private static boolean retiring;
    private BetaModelImports() { }

    public static <T> void submit(Supplier<T> source, Consumer<Runnable> clientQueue,
                                  BiConsumer<T, Throwable> install) {
        ModuleScope scope = ModuleScope.current();
        CompletableFuture<Void> completion = new CompletableFuture<>();
        synchronized (pending) {
            if (retiring) throw new IllegalStateException("Model imports have stopped");
            if (scope != null) scope.ownTransient(() -> { }, completion);
            pending.add(completion);
        }
        CompletableFuture<Void> delivered = new CompletableFuture<>();
        try {
            CompletableFuture<Void> worker = CompletableFuture.runAsync(() -> {
                T result = null;
                Throwable failure = null;
                try (var ownership = scope == null ? null : scope.tryEnter()) {
                    if (scope != null && ownership == null) { delivered.complete(null); return; }
                    result = source.get();
                } catch (Throwable error) { failure = error; }
                // No child installation is queued once this activation has retired.
                if (scope != null && !scope.allowed()) { delivered.complete(null); return; }
                T model = result;
                Throwable error = failure;
                try {
                    clientQueue.accept(() -> {
                        try (var ownership = scope == null ? null : scope.tryEnter()) {
                            if (scope == null || ownership != null) install.accept(model, error);
                            delivered.complete(null);
                        } catch (Throwable installFailure) {
                            delivered.completeExceptionally(installFailure);
                        }
                    });
                } catch (Throwable schedulingFailure) {
                    delivered.completeExceptionally(schedulingFailure);
                }
            });
            CompletableFuture.allOf(worker, delivered).whenComplete((unused, failure) -> {
                if (failure == null) {
                    synchronized (pending) { pending.remove(completion); }
                    completion.complete(null);
                } else completion.completeExceptionally(failure);
            });
        } catch (Throwable schedulingFailure) {
            completion.completeExceptionally(schedulingFailure);
        }
    }

    public static CompletionStage<Void> drained() {
        synchronized (pending) {
            retiring = true;
            return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).minimalCompletionStage();
        }
    }
}
