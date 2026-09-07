package dev.c4lam.celeste.beta.lifecycle;

import dev.c4lam.celeste.auth.ArtifactIntegrity;
import dev.c4lam.celeste.auth.AuthBootstrap;
import dev.c4lam.celeste.beta.resources.InternalMemoryResources;
import dev.c4lam.celeste.beta.resources.MinecraftInternalResourcesHost;
import dev.c4lam.celeste.render.GuiPipelines;
import dev.c4lam.celeste.compat.BetaRenderPipelines;
import dev.c4lam.celeste.compat.BetaTextureLease;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.Consumer;
import net.minecraft.client.MinecraftClient;

/**
 * Payload-side transaction: retained bytes, internal resource reload, required
 * authentication, then services. The host must install every required method
 * hook and provide the signed release context before calling this entrypoint.
 */
public final class BetaPayloadSession {
    private final MinecraftClient client;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final AtomicBoolean retiring = new AtomicBoolean();
    private volatile String phase = "preparing";
    private volatile BetaRuntime runtime;
    private InternalMemoryResources assets;
    private MinecraftInternalResourcesHost resourceHost;
    private BetaRenderPipelines.Lease pipelines;
    private BetaTextureLease textures;
    private Throwable failure;

    private BetaPayloadSession(MinecraftClient client) { this.client = client; }

    /** JDK-only linkage surface for the second bootstrap's retained-byte visitor. */
    public static BetaPayloadSession startFromRetainedParts(Consumer<Consumer<Map<String, byte[]>>> source,
                                                            String catalogSha256, String expectedDigest) {
        Objects.requireNonNull(source, "source");
        return start(visitor -> {
            try {
                source.accept(part -> {
                    try { visitor.accept(part); }
                    catch (IOException error) { throw new UncheckedIOException(error); }
                });
            } catch (UncheckedIOException error) { throw error.getCause(); }
        }, catalogSha256, expectedDigest);
    }

    /**
     * This does not authorize arbitrary bytes: the caller supplies the exact
     * source retained by its verified two-part loader and the server-signed
     * catalog/digest. Runtime auth subsequently checks that release again.
     * The returned handle exposes asynchronous failure and retirement.
     */
    public static BetaPayloadSession start(ArtifactIntegrity.VerifiedMemorySource retained,
                                          String catalogSha256, String expectedDigest) {
        Objects.requireNonNull(retained, "retained");
        requireHash(catalogSha256);
        requireHash(expectedDigest);
        dev.c4lam.celeste.compat.RuntimeLogging.quietBeta();
        BetaPayloadSession session = new BetaPayloadSession(MinecraftClient.getInstance());
        session.onClient(() -> session.prepare(retained, catalogSha256, expectedDigest))
            .thenCompose(stage -> stage)
            .thenCompose(unused -> session.onClient(session::startRuntime))
            .thenCompose(stage -> stage)
            .whenComplete((unused, error) -> session.onClient(() -> {
                if (error != null) {
                    session.remember(error);
                    session.ready.completeExceptionally(unwrap(error));
                    session.retire();
                } else if (session.stopRequested.get()) {
                    session.ready.completeExceptionally(new CancellationException("Beta startup was stopped"));
                    session.retire();
                } else {
                    session.phase = "active";
                    session.ready.complete(null);
                }
                return null;
            }).exceptionally(schedulingFailure -> {
                session.schedulingFailed(schedulingFailure);
                return null;
            }));
        return session;
    }

    public CompletionStage<Void> ready() { return ready.minimalCompletionStage(); }
    public CompletionStage<Void> stopped() { return stopped.minimalCompletionStage(); }

    public CompletionStage<Void> stop() {
        stopRequested.set(true);
        // A resource activation must settle before retirement can replace its
        // views. During authentication, request stop immediately so ready settles.
        onClient(() -> {
            if (runtime != null) {
                runtime.stop();
                retire();
            } else if (phase.equals("active")) {
                retire();
            }
            return null;
        }).exceptionally(error -> { schedulingFailed(error); return null; });
        return stopped();
    }

    public Map<String, String> status() {
        Map<String, String> result = new HashMap<>();
        result.put("phase", phase);
        BetaRuntime current = runtime;
        if (current != null) result.putAll(current.status());
        result.put("resourcePhase", phase);
        return Map.copyOf(result);
    }

    private CompletionStage<Void> prepare(ArtifactIntegrity.VerifiedMemorySource retained,
                                          String catalogSha256, String expectedDigest) {
        requireRunning();
        try {
            if (!expectedDigest.equals(ArtifactIntegrity.digestVerifiedMemory(retained))) {
                throw new IOException("Retained payload digest does not match the signed release");
            }
            // Derive resources from those same retained maps, never a separate
            // asset archive whose content could diverge from integrity checks.
            Map<String, byte[]> snapshot = new HashMap<>();
            retained.visitParts(part -> {
                for (Map.Entry<String, byte[]> entry : part.entrySet()) {
                    String name = entry.getKey();
                    if (name.endsWith("/") || !(name.startsWith("assets/celeste/") || name.equals("pack.png"))) continue;
                    if (snapshot.putIfAbsent(name, entry.getValue().clone()) != null) {
                        throw new IOException("Duplicate internal resource in retained payload");
                    }
                }
            });
            textures = BetaTextureLease.open(client.getTextureManager(), snapshot.keySet());
            try { assets = new InternalMemoryResources(snapshot); }
            finally { snapshot.values().forEach(bytes -> java.util.Arrays.fill(bytes, (byte) 0)); }
            ArtifactIntegrity.installVerifiedMemorySource(retained);
            AuthBootstrap.configureBetaRuntime(catalogSha256);
            pipelines = BetaRenderPipelines.open();
            GuiPipelines.register();
            resourceHost = MinecraftInternalResourcesHost.connect(client, assets);
            phase = "loading resources";
            return resourceHost.activateAndReload();
        } catch (IOException error) {
            throw new CompletionException(error);
        }
    }

    private CompletionStage<Void> startRuntime() {
        requireRunning();
        phase = "authenticating";
        runtime = BetaRuntime.start();
        // Revocation and startup failure also retire assets, including when
        // stopped() is exceptional. Runtime failure must never orphan the layer.
        runtime.stopped().whenComplete((unused, error) -> onClient(() -> {
            if (error != null) remember(error);
            retire();
            return null;
        }).exceptionally(schedulingFailure -> { schedulingFailed(schedulingFailure); return null; }));
        return runtime.ready();
    }

    private void retire() {
        if (!retiring.compareAndSet(false, true)) return;
        stopRequested.set(true);
        phase = "stopping";
        if (!ready.isDone()) ready.completeExceptionally(new CancellationException("Beta runtime stopped before ready"));
        CompletionStage<Void> serviceStop = runtime == null
            ? CompletableFuture.completedFuture(null) : runtime.stop();
        serviceStop.handle((unused, error) -> error)
            .thenCompose(error -> onClient(() -> {
                if (error != null) remember(error);
                // Shader reload visits globally registered pipelines. Retire only
                // this payload's entries after render callbacks stop, before its
                // shader files leave the resource manager.
                if (resourceHost == null) {
                    if (pipelines != null) pipelines.close();
                    if (textures != null) textures.close();
                    if (assets != null) assets.close();
                    return CompletableFuture.<Void>completedFuture(null);
                }
                return resourceHost.retireWhenIdle(() -> {
                    if (pipelines != null) pipelines.close();
                    if (textures != null) textures.close();
                });
            }))
            .thenCompose(stage -> stage)
            .whenComplete((unused, error) -> onClient(() -> {
                if (error != null) remember(error);
                if (error == null) {
                    resourceHost = null;
                    assets = null;
                    pipelines = null;
                    textures = null;
                    runtime = null;
                }
                // Failed removal deliberately retains the host/storage; the
                // classloader must remain alive if Minecraft still owns views.
                if (failure == null) {
                    phase = "stopped";
                    stopped.complete(null);
                } else {
                    phase = "failed";
                    stopped.completeExceptionally(failure);
                }
                return null;
            }).exceptionally(schedulingFailure -> { schedulingFailed(schedulingFailure); return null; }));
    }

    private <T> CompletableFuture<T> onClient(Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            // Always enqueue to leave the reload/auth callback before releasing
            // its bridge registration or starting another resource transition.
            client.send(() -> {
                try { result.complete(action.get()); }
                catch (Throwable error) { result.completeExceptionally(error); }
            });
        } catch (RuntimeException | LinkageError error) { result.completeExceptionally(error); }
        return result;
    }

    private void requireRunning() {
        if (stopRequested.get()) throw new CancellationException("Beta startup was stopped");
    }
    private void remember(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof CancellationException && stopRequested.get()) return;
        if (failure == null) failure = cause;
        else if (failure != cause) failure.addSuppressed(cause);
    }
    private void schedulingFailed(Throwable error) {
        phase = "failed";
        ready.completeExceptionally(unwrap(error));
        stopped.completeExceptionally(unwrap(error));
    }
    private static Throwable unwrap(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        return error;
    }
    private static void requireHash(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Expected a SHA-256 release value");
    }
}
