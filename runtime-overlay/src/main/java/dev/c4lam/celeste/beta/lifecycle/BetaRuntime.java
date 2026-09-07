package dev.c4lam.celeste.beta.lifecycle;

import dev.c4lam.celeste.CelesteClient;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/** Stable host-facing facade after verified memory sources, resources, and compatible hooks are installed. */
public final class BetaRuntime {
    private final CelesteClient client = new CelesteClient();

    private BetaRuntime() { }

    /** Starts mandatory authentication asynchronously; the returned object itself does not imply readiness. */
    public static BetaRuntime start() {
        BetaRuntime runtime = new BetaRuntime();
        runtime.client.startAfterGameStarted();
        return runtime;
    }

    public CompletionStage<Void> ready() { return client.lateReady(); }
    public CompletionStage<Void> stopped() { return client.lateStopped(); }
    public CompletionStage<Void> stop() { return client.stopAfterGameStarted(); }
    public Map<String, String> status() { return client.lateStatus(); }
}
