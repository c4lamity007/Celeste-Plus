package dev.c4lam.celeste.beta.lifecycle;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Observable one-shot lifecycle; completion represents service retirement, not JVM/GPU unloading. */
public final class BetaRuntimeLifecycle {
    private enum Phase { NEW, STARTING, READY, STOPPING, STOPPED, FAILED }

    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private Phase phase = Phase.NEW;
    private Throwable failure;

    public synchronized boolean beginStart() {
        if (phase != Phase.NEW) return false;
        phase = Phase.STARTING;
        return true;
    }

    public synchronized boolean stopping() {
        return phase == Phase.STOPPING || phase == Phase.STOPPED || phase == Phase.FAILED;
    }

    /** Caller has finished initializing authorized services on the client thread. */
    public boolean activate() {
        synchronized (this) {
            if (phase != Phase.STARTING) return false;
            phase = Phase.READY;
        }
        ready.complete(null);
        return true;
    }

    public void requestStop() {
        synchronized (this) {
            if (phase == Phase.STOPPED || phase == Phase.FAILED) return;
            phase = Phase.STOPPING;
        }
        ready.completeExceptionally(new CancellationException("Beta runtime stopped before it became ready"));
    }

    /** Keep the original failure and any cleanup failures without reopening an already retired runtime. */
    public void fail(Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        synchronized (this) {
            if (phase == Phase.STOPPED || phase == Phase.FAILED) return;
            if (failure == null) failure = cause;
            else if (failure != cause) failure.addSuppressed(cause);
            phase = Phase.STOPPING;
        }
        ready.completeExceptionally(cause);
    }

    /** Cleanup could not even be scheduled; exceptional stop completion must not be treated as retirement. */
    public void stopSchedulingFailed(Throwable cause) {
        fail(cause);
        synchronized (this) { phase = Phase.FAILED; }
        stopped.completeExceptionally(cause);
    }

    /** Called only after local cleanup, callback drainage, and observed authorization-worker termination. */
    public void finish() {
        Throwable terminal;
        synchronized (this) {
            if (phase == Phase.STOPPED || phase == Phase.FAILED) return;
            if (phase != Phase.STOPPING) throw new IllegalStateException("Stop must begin before retirement completes");
            terminal = failure;
            phase = terminal == null ? Phase.STOPPED : Phase.FAILED;
        }
        if (terminal == null) stopped.complete(null);
        else stopped.completeExceptionally(terminal);
    }

    public CompletionStage<Void> ready() { return ready.minimalCompletionStage(); }
    public CompletionStage<Void> stopped() { return stopped.minimalCompletionStage(); }

    public synchronized Map<String, String> snapshot() {
        return Map.of("phase", phase.name(), "failure", failure == null ? ""
            : failure.getClass().getSimpleName() + ": " + Objects.toString(failure.getMessage(), ""));
    }
}
