package dev.c4lam.celeste.module.delivery;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

/** Tracks detach/termination completion, without waiting on a game or render callback. */
public final class ModuleScope implements AutoCloseable {
    private static final ThreadLocal<ModuleScope> CURRENT = new ThreadLocal<>();
    private record Owned(AutoCloseable resource, CompletionStage<Void> drained) { }
    private final List<Owned> owned = new ArrayList<>();
    private final List<AutoCloseable> deferred = new ArrayList<>();
    private final java.util.function.Consumer<Runnable> owner;
    private final CompletableFuture<Void> retired = new CompletableFuture<>();
    private final BooleanSupplier authorized;
    private final BooleanSupplier registrationAuthorized;
    private final String moduleId;
    private final long drainTimeoutMillis;
    private volatile boolean closed;
    private int entered;
    private final CompletableFuture<Void> contextsDrained = new CompletableFuture<>();

    public ModuleScope() { this(() -> true); }
    public ModuleScope(BooleanSupplier authorized) { this(authorized, Runnable::run); }
    public ModuleScope(BooleanSupplier authorized, java.util.function.Consumer<Runnable> owner) { this("", authorized, owner); }
    public ModuleScope(String moduleId, BooleanSupplier authorized, java.util.function.Consumer<Runnable> owner) {
        this(moduleId, authorized, authorized, owner);
    }
    public ModuleScope(String moduleId, BooleanSupplier authorized, BooleanSupplier registrationAuthorized,
                       java.util.function.Consumer<Runnable> owner) {
        this(moduleId,authorized,registrationAuthorized,owner,30_000);
    }
    public ModuleScope(String moduleId, BooleanSupplier authorized, BooleanSupplier registrationAuthorized,
                       java.util.function.Consumer<Runnable> owner, long drainTimeoutMillis) {
        if(drainTimeoutMillis < 1)throw new IllegalArgumentException("Drain deadline must be positive");
        this.drainTimeoutMillis=drainTimeoutMillis;
        this.moduleId = Objects.requireNonNull(moduleId);
        this.authorized = Objects.requireNonNull(authorized);
        this.registrationAuthorized = Objects.requireNonNull(registrationAuthorized);
        this.owner = Objects.requireNonNull(owner);
    }
    public String moduleId() { return moduleId; }
    public String registrationName(String name) { return moduleId.isEmpty() ? name : name + "/module/" + moduleId; }
    public String registrationPath(String path) { return moduleId.isEmpty() ? path : "module/" + moduleId + "/" + path; }
    public static void executeCurrent(java.util.function.Consumer<Runnable> scheduler, Runnable action) {
        ModuleScope scope = current();
        if (scope == null) scheduler.accept(action); else scope.execute(scheduler, action);
    }
    public static ModuleScope current() { return CURRENT.get(); }
    public boolean allowed() { return !closed && authorized.getAsBoolean(); }
    /** Inert registration during authenticated factory/onEnable; this never authorizes callback execution. */
    public boolean canRegister() {
        return CURRENT.get() == this && !closed && registrationAuthorized.getAsBoolean();
    }

    /** Dynamic ownership only, never authorization. It is intentionally not inherited by workers. */
    public synchronized Context enter() {
        if (closed) throw new IllegalStateException("Module scope is retiring");
        ModuleScope previous = CURRENT.get();
        CURRENT.set(this);
        entered++;
        return new Context(previous);
    }

    /** Atomically reject a new callback once scope retirement begins. */
    public synchronized Context tryEnter() {
        return closed || !authorized.getAsBoolean() ? null : enter();
    }

    public final class Context implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final ModuleScope previous;
        private boolean ended;
        private Context(ModuleScope previous) { this.previous = previous; }
        @Override public void close() {
            if (ended) return;
            if (Thread.currentThread() != owner || CURRENT.get() != ModuleScope.this)
                throw new IllegalStateException("Module ownership context closed out of order");
            ended = true;
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            synchronized (ModuleScope.this) {
                entered--;
                if (closed && entered == 0) contextsDrained.complete(null);
            }
        }
    }

    public synchronized <T extends AutoCloseable> T own(T resource, CompletionStage<Void> drained) {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(drained, "drained");
        if (closed) throw new IllegalStateException("Module scope is retiring");
        owned.add(new Owned(resource, drained));
        return resource;
    }

    /** Finite operations do not accumulate one completed ownership entry per rendered frame. */
    public synchronized <T extends AutoCloseable> T ownTransient(T resource, CompletionStage<Void> drained) {
        Objects.requireNonNull(resource); Objects.requireNonNull(drained);
        if (closed) throw new IllegalStateException("Module scope is retiring");
        Owned operation = new Owned(resource, drained);
        owned.add(operation);
        drained.whenComplete((ignored, failure) -> {
            if (failure == null) synchronized (this) { owned.removeIf(entry -> entry == operation); }
        });
        return resource;
    }

    public <T extends AutoCloseable> T own(T resource) {
        return own(resource, CompletableFuture.completedFuture(null));
    }

    /** GPU/native resources are released on the owner only after callbacks and workers drain. */
    public synchronized <T extends AutoCloseable> T releaseAfterCallbacks(T resource) {
        if (closed) throw new IllegalStateException("Module scope is retiring");
        deferred.add(Objects.requireNonNull(resource));
        return resource;
    }

    /** Track queued owner/render work without retaining completed operations. */
    public CompletionStage<Void> execute(java.util.function.Consumer<Runnable> scheduler, Runnable callback) {
        Objects.requireNonNull(scheduler); Objects.requireNonNull(callback);
        CompletableFuture<Void> completed = new CompletableFuture<>();
        ownTransient(() -> { }, completed);
        try { scheduler.accept(() -> {
            try (var ownership = tryEnter()) {
                if (ownership != null) callback.run();
                completed.complete(null);
            } catch (Throwable failure) { completed.completeExceptionally(failure); }
        }); } catch (Throwable failure) { completed.completeExceptionally(failure); }
        return completed.minimalCompletionStage();
    }

    public boolean open() { return !closed; }

    @Override public void close() {
        List<Owned> snapshot;
        synchronized (this) {
            if (closed) return;
            closed = true;
            // CompletableFuture cancels its scheduled timeout when retirement succeeds or fails sooner.
            retired.orTimeout(drainTimeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
            snapshot = List.copyOf(owned);
            owned.clear();
            if (entered == 0) contextsDrained.complete(null);
        }
        List<CompletableFuture<?>> completions = new ArrayList<>();
        completions.add(contextsDrained);
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            Owned entry = snapshot.get(i);
            try { entry.resource.close(); }
            catch (Throwable failure) { completions.add(CompletableFuture.failedFuture(failure)); }
            completions.add(entry.drained.toCompletableFuture());
        }
        CompletableFuture.allOf(completions.toArray(CompletableFuture[]::new))
            .whenComplete((ignored, failure) -> {
                if (failure != null) { retired.completeExceptionally(failure); return; }
                if (retired.isDone()) return; // timed out: retain resources in the quarantined scope
                try { owner.accept(() -> {
                    if (retired.isDone()) return;
                    Throwable problem = null;
                    List<AutoCloseable> resources;
                    synchronized (this) { resources = List.copyOf(deferred); }
                    for (int i = resources.size() - 1; i >= 0; i--) {
                        try { resources.get(i).close(); }
                        catch (Throwable releaseFailure) {
                            if (problem == null) problem = releaseFailure; else problem.addSuppressed(releaseFailure);
                        }
                    }
                    if (problem == null) {
                        synchronized (this) { deferred.clear(); }
                        retired.complete(null);
                    } else retired.completeExceptionally(problem);
                }); } catch (Throwable schedulingFailure) { retired.completeExceptionally(schedulingFailure); }
            });
    }

    public CompletionStage<Void> retired() { return retired.minimalCompletionStage(); }
}
