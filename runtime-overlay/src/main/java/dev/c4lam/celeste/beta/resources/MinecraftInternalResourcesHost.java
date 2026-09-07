package dev.c4lam.celeste.beta.resources;

import dev.celestebeta.host.ResourceReloadBridge;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourcePack;

/**
 * Connects verified internal assets to installed, version-checked reload hooks.
 * Own state lives here, never in an added Minecraft field or a chooser profile.
 * The beta bootstrap must install the actual hooks before connecting this host.
 */
public final class MinecraftInternalResourcesHost implements AutoCloseable, ResourceReloadBridge.Handler {
    /** Client-thread boundary; executeLater must enqueue, never invoke inline. */
    public interface Control {
        Object clientIdentity();
        boolean isOnThread();
        boolean isReloading();
        CompletableFuture<Void> requestReload();
        void executeLater(Runnable task);
        void executeAfterDelay(Runnable task, java.util.function.Consumer<Throwable> rejected);
        void reportFailure(Throwable failure);
    }

    private record Attempt(boolean internalIncluded) { }

    private final Control control;
    private final InternalMemoryResources assets;
    private final ResourceReloadBridge.Registration registration;
    private final Map<CompletableFuture<?>, Attempt> attempts = new IdentityHashMap<>();
    private final List<CompletableFuture<?>> failedVanillaAttempts = new ArrayList<>();
    private CompletableFuture<Void> transition;
    private CompletableFuture<Void> retirement;
    private CompletableFuture<?> lastStartedAttempt;
    private Throwable firstFailure;
    private boolean excludeInternal;
    private boolean recovering;
    private boolean settlementQueued;
    private boolean resourcesRetired = true;
    private boolean closed;

    public static MinecraftInternalResourcesHost connect(MinecraftClient client, InternalMemoryResources assets) {
        Objects.requireNonNull(client, "client");
        ResourceReloadBridge.assertVisibleFrom(client.getClass().getClassLoader());
        ResourceReloadBridge.assertVisibleFrom(MinecraftInternalResourcesHost.class.getClassLoader());
        return new MinecraftInternalResourcesHost(new Control() {
            public Object clientIdentity() { return client; }
            public boolean isOnThread() { return client.isOnThread(); }
            public boolean isReloading() { return client.getOverlay() != null; }
            public CompletableFuture<Void> requestReload() { return client.reloadResources(); }
            public void executeLater(Runnable task) { client.send(task); }
            public void executeAfterDelay(Runnable task, java.util.function.Consumer<Throwable> rejected) {
                // Do not initialize the JVM-global delay scheduler from a payload
                // protection domain: its inherited access context can retain us.
                CompletableFuture.runAsync(() -> {
                    try {
                        Thread.sleep(50L);
                        client.send(task);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        rejected.accept(failure);
                    } catch (RuntimeException | LinkageError failure) { rejected.accept(failure); }
                });
            }
            public void reportFailure(Throwable failure) {
                // The owning session reports the original failure to the launcher.
            }
        }, assets);
    }

    /** Exposed for a host's scheduling adapter and offline resource-manager checks. */
    public MinecraftInternalResourcesHost(Control control, InternalMemoryResources assets) {
        this.control = Objects.requireNonNull(control, "control");
        this.assets = Objects.requireNonNull(assets, "assets");
        requireClientThread();
        if (control.isReloading() || assets.isActive()) {
            throw new IllegalStateException("Connect internal assets while the client and layer are idle");
        }
        registration = ResourceReloadBridge.install(control.clientIdentity(), this);
    }

    public CompletableFuture<Void> activateAndReload() {
        requireIdle();
        excludeInternal = false;
        return assets.activateAndReload(this::requestObservedReload);
    }

    public CompletableFuture<Void> deactivateAndReload() {
        requireIdle();
        return assets.deactivateAndReload(this::requestObservedReload);
    }

    /** Wait for an existing reload and its overlay fade, retire owned render state,
     * replace resources, then wait again before releasing the bridge and storage. */
    public CompletableFuture<Void> retireWhenIdle(Runnable beforeRemoval) {
        requireClientThread();
        Objects.requireNonNull(beforeRemoval, "beforeRemoval");
        if (retirement != null) return retirement.thenApply(unused -> null);
        if (closed) return CompletableFuture.completedFuture(null);
        retirement = new CompletableFuture<>();
        awaitIdle(() -> {
            beforeRemoval.run();
            deactivateAndReload().whenComplete((unused, failure) -> {
                if (failure != null) retirement.completeExceptionally(failure);
                else awaitIdle(() -> {
                    close();
                    retirement.complete(null);
                });
            });
        });
        // Cancellation of an observer must never cancel the actual game cleanup.
        return retirement.thenApply(unused -> null);
    }

    private void awaitIdle(Runnable action) {
        if (retirement.isDone()) return;
        try {
            requireClientThread();
            if (closed) throw new IllegalStateException("Internal resource host closed during retirement");
            if (busy()) {
                // One delayed check at a time; never spin in the render task queue.
                control.executeAfterDelay(() -> awaitIdle(action), retirement::completeExceptionally);
            } else {
                action.run();
            }
        } catch (RuntimeException | LinkageError failure) {
            retirement.completeExceptionally(failure);
        }
    }

    private boolean busy() {
        return transition != null || recovering || settlementQueued || !attempts.isEmpty() || control.isReloading();
    }

    private CompletableFuture<Void> requestObservedReload() {
        CompletableFuture<Void> observed = new CompletableFuture<>();
        transition = observed;
        firstFailure = null;
        lastStartedAttempt = null;
        try {
            CompletableFuture<Void> vanilla = Objects.requireNonNull(control.requestReload(), "vanilla reload future");
            // With an idle client, the real input hook must run synchronously.
            // A successful ordinary reload alone cannot prove our assets loaded.
            if (lastStartedAttempt != vanilla) {
                throw new IllegalStateException("The Minecraft internal resource reload hooks are not active");
            }
        } catch (RuntimeException | Error failure) {
            transition = null;
            firstFailure = null;
            // The input hook runs before Minecraft constructs the reload and
            // installs its overlay. A synchronous error in that gap has no
            // result callback to release either token, so permit explicit retry.
            // Do not infer resource retirement: the manager may already own our
            // view and only a successful replacement permits storage close.
            if (lastStartedAttempt != null && !control.isReloading()
                    && attempts.containsKey(lastStartedAttempt)) {
                try {
                    registration.abandonSubmission(lastStartedAttempt);
                    attempts.remove(lastStartedAttempt);
                    lastStartedAttempt.completeExceptionally(failure);
                } catch (RuntimeException | Error cleanupFailure) {
                    if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
        return observed;
    }

    @Override
    public List<?> augment(Object client, boolean forced, CompletableFuture<?> attempt, List<?> original) {
        requireClient(client);
        List<ResourcePack> selected = new ArrayList<>(original.size());
        for (Object pack : original) {
            if (!(pack instanceof ResourcePack resourcePack)) {
                throw new IllegalArgumentException("Reload input is not a Minecraft resource pack");
            }
            selected.add(resourcePack);
        }
        // Keep original identity in the dormant case, including after failure.
        List<?> result = original;
        if (!excludeInternal && assets.isActive()) {
            result = assets.augmentReloadPacks(selected);
        }
        boolean included = result != original;
        attempts.put(attempt, new Attempt(included));
        lastStartedAttempt = attempt;
        if (included) resourcesRetired = false;
        return result;
    }

    @Override
    public boolean beforeResult(Object client, boolean forced, CompletableFuture<?> attempt, Optional<?> failure) {
        requireClient(client);
        Attempt current = attempts.get(attempt);
        if (current == null || failure.isEmpty()) return false;
        if (!current.internalIncluded() && transition == null && !recovering) return false;
        Throwable cause = failure.get() instanceof Throwable throwable ? throwable
            : new IllegalStateException("Minecraft supplied an invalid resource reload failure");
        rememberFailure(cause);
        failedVanillaAttempts.add(attempt);
        if (!current.internalIncluded() || recovering) return false;

        // This attempt contained our assets. First try a fresh selected-pack
        // reload without them, before vanilla's recovery clears pack choices.
        excludeInternal = true;
        recovering = true;
        try {
            control.reportFailure(cause);
        } catch (RuntimeException reportFailure) {
            if (reportFailure != cause) cause.addSuppressed(reportFailure);
        }
        try {
            control.requestReload();
            return true;
        } catch (RuntimeException | Error recoveryFailure) {
            if (recoveryFailure != cause) cause.addSuppressed(recoveryFailure);
            // Original vanilla error handling remains available if queueing fails.
            return false;
        }
    }

    @Override
    public void afterResult(Object client, boolean forced, CompletableFuture<?> attempt,
                            Optional<?> failure, Throwable callbackFailure) {
        requireClient(client);
        Attempt current = attempts.get(attempt);
        if (current == null) return;
        if (failure.isEmpty() && callbackFailure != null
                && !(callbackFailure instanceof ResourceReloadBridge.HookFailureException)) {
            // SplashOverlay catches a throwing success callback and immediately
            // invokes the same token again with the failure. Keep ownership so
            // that second callback can take the normal internal cleanup path.
            rememberFailure(callbackFailure);
            return;
        }
        attempts.remove(attempt);
        if (callbackFailure != null) {
            rememberFailure(callbackFailure);
            queueSettlement(false);
        } else if (failure.isEmpty()) {
            // The original callback has completed renderer/server-pack work.
            // The replacement resource manager already retired previous views.
            resourcesRetired = !current.internalIncluded();
            queueSettlement(true);
        } else if (forced && !current.internalIncluded()) {
            // Vanilla's forced retry also failed; it has now run its own final
            // failure handling. Retain asset bytes for explicit later recovery.
            queueSettlement(false);
        }
        // A first failed attempt is intentionally unsettled while its queued
        // cleanup or vanilla fallback runs. Raw reload futures can fail early.
    }

    private void rememberFailure(Throwable failure) {
        if (firstFailure == null) firstFailure = failure;
        else if (firstFailure != failure) firstFailure.addSuppressed(failure);
    }

    private void queueSettlement(boolean completedReload) {
        if (settlementQueued) return;
        settlementQueued = true;
        control.executeLater(() -> {
            requireClientThread();
            settlementQueued = false;
            recovering = false;
            CompletableFuture<Void> observed = transition;
            transition = null;
            Throwable failure = firstFailure;
            firstFailure = null;
            List<CompletableFuture<?>> abandoned = List.copyOf(failedVanillaAttempts);
            failedVanillaAttempts.clear();
            if (failure != null) {
                for (CompletableFuture<?> vanilla : abandoned) vanilla.completeExceptionally(failure);
            }
            if (observed != null) {
                if (failure != null) observed.completeExceptionally(failure);
                else if (completedReload) observed.complete(null);
                else observed.completeExceptionally(new IllegalStateException("Resource reload did not complete"));
            }
        });
    }

    private void requireIdle() {
        requireClientThread();
        if (closed || busy()) {
            throw new IllegalStateException("Wait for the current Minecraft resource reload to finish");
        }
    }

    private void requireClient(Object client) {
        requireClientThread();
        if (closed || client != control.clientIdentity()) {
            throw new IllegalStateException("Resource callback belongs to another client or a closed host");
        }
    }

    private void requireClientThread() {
        if (!control.isOnThread()) throw new IllegalStateException("Internal resources require the Minecraft client thread");
    }

    /** Close after successful deactivation or successful cleanup of failed activation. */
    @Override
    public void close() {
        requireClientThread();
        if (closed) return;
        requireIdle();
        if (!resourcesRetired) throw new IllegalStateException("Reload without Celeste assets before closing their storage");
        registration.close();
        assets.close();
        closed = true;
    }
}
