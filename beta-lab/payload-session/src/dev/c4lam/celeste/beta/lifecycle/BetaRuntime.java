package dev.c4lam.celeste.beta.lifecycle;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
public final class BetaRuntime {
    public static BetaRuntime current;
    public static int starts;
    public final CompletableFuture<Void> ready = new CompletableFuture<>(), stopped = new CompletableFuture<>();
    public static BetaRuntime start() { starts++; return current = new BetaRuntime(); }
    public CompletionStage<Void> ready() { return ready; }
    public CompletionStage<Void> stopped() { return stopped; }
    public CompletionStage<Void> stop() { return stopped; }
    public Map<String, String> status() { return Map.of(); }
}
