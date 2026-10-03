package dev.c4lam.celeste.module.delivery;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** A parent-owned waiter: never block the client thread while child workers stop. */
public final class ModuleRetirement {
    private ModuleRetirement() { }
    public static CompletionStage<Void> stopWorker(ExecutorService worker) {
        if (worker == null) return CompletableFuture.completedFuture(null);
        worker.shutdownNow();
        CompletableFuture<Void> stopped = new CompletableFuture<>();
        Thread.ofVirtual().name("celeste-module-worker-retirement").start(() -> {
            try {
                if (!worker.awaitTermination(30, TimeUnit.SECONDS))
                    throw new TimeoutException("Module worker did not terminate within 30 seconds");
                stopped.complete(null);
            } catch (Throwable failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                stopped.completeExceptionally(failure);
            }
        });
        return stopped.minimalCompletionStage();
    }
}
