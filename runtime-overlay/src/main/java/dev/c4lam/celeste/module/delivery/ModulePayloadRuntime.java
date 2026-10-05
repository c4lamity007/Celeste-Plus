package dev.c4lam.celeste.module.delivery;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;

/** Owner-thread activation state machine. Downloads never install from a worker callback. */
public final class ModulePayloadRuntime implements AutoCloseable {
    public enum State { OFF, FETCHING, STARTING, AVAILABLE, RETIRING, FAILED }
    public interface Listener {
        void installed(String id, ModuleFeature feature);
        void retiring(String id, ModuleFeature feature);
        void failed(String id, Throwable failure);
    }
    private static final class Slot {
        final String id;
        volatile long generation;
        volatile boolean wanted;
        volatile boolean started;
        volatile State state = State.OFF;
        volatile ModulePayloadLease lease;
        boolean quarantined;
        boolean retryRequired;
        ModuleScope scope;
        ModuleFeature feature;
        CompletableFuture<Void> pending = CompletableFuture.completedFuture(null);
        Slot(String id) { this.id = id; }
    }
    private final Map<String, Slot> slots = new LinkedHashMap<>();
    private volatile Map<String, Slot> publishedSlots = Map.of();
    private final ModulePayloadProvider provider;
    private final Consumer<Runnable> owner;
    private final Function<String, ModuleContext> context;
    private final Listener listener;
    private final Thread ownerThread;
    private final ModuleHookRegistry hooks = new ModuleHookRegistry(this::authorized);
    private volatile boolean closed;
    private final java.util.concurrent.ConcurrentLinkedQueue<ModulePayloadLease> quarantinedDeliveries = new java.util.concurrent.ConcurrentLinkedQueue<>();

    public ModulePayloadRuntime(ModulePayloadProvider provider, Consumer<Runnable> owner,
                                Function<String, ModuleContext> context, Listener listener) {
        this.provider = Objects.requireNonNull(provider);
        this.owner = Objects.requireNonNull(owner);
        this.context = Objects.requireNonNull(context);
        this.listener = Objects.requireNonNull(listener);
        ownerThread = Thread.currentThread();
    }

    private void checkOwner() {
        if (Thread.currentThread() != ownerThread) throw new IllegalStateException("Module lifecycle requires its owner thread");
    }

    public void register(String id) {
        checkOwner();
        if (closed || id == null || !id.matches("[a-z0-9]+(?:_[a-z0-9]+)*") || slots.putIfAbsent(id, new Slot(id)) != null)
            throw new IllegalArgumentException("Invalid or duplicate module ID");
        publishedSlots = Map.copyOf(slots);
    }

    public ModuleHookRegistry hooks() { return hooks; }

    public void synchronize(Set<String> desired) {
        checkOwner();
        if (closed) return;
        if (!slots.keySet().containsAll(desired)) throw new IllegalArgumentException("Unknown enabled module");
        for (Slot slot : slots.values()) {
            boolean wanted = desired.contains(slot.id);
            if (!wanted && slot.state == State.FAILED && slot.lease == null && !slot.quarantined) {
                slot.retryRequired = false;
                slot.state = State.OFF;
            }
            slot.wanted = wanted;
            if (!wanted && (slot.state == State.FETCHING || slot.state == State.AVAILABLE)) retire(slot);
            else if (wanted && slot.state == State.OFF) acquire(slot);
            else if (wanted && slot.state == State.AVAILABLE && !ready(slot.id)) {
                listener.failed(slot.id, new SecurityException("Module authorization expired"));
                retire(slot);
            }
        }
    }

    private void acquire(Slot slot) {
        slot.state = State.FETCHING;
        long generation = ++slot.generation;
        CompletableFuture<Void> pending = new CompletableFuture<>();
        slot.pending = pending;
        try {
            provider.acquire(slot.id, generation).whenComplete((lease, failure) -> {
                try { owner.accept(() -> {
                    try { received(slot, generation, lease, failure); pending.complete(null); }
                    catch (Throwable rejected) { pending.completeExceptionally(rejected); }
                }); }
                catch (Throwable schedulingFailure) {
                    try { closeUninstalled(lease); }
                    catch (Throwable cleanupFailure) { schedulingFailure.addSuppressed(cleanupFailure); }
                    pending.completeExceptionally(schedulingFailure);
                }
            });
        } catch (Throwable failure) {
            if(quarantineCleanup(slot,failure))pending.completeExceptionally(unwrap(failure));
            else{fail(slot, failure);pending.complete(null);}
        }
    }

    private void received(Slot slot, long generation, ModulePayloadLease lease, Throwable failure) {
        checkOwner();
        if (closed || !slot.wanted || slot.generation != generation || slot.state != State.FETCHING) {
            closeUninstalled(lease);
            if(quarantineCleanup(slot,failure))throw new java.util.concurrent.CompletionException(unwrap(failure));
            if (slot.state == State.RETIRING && slot.lease == null) slot.state = State.OFF;
            return;
        }
        if (failure != null || lease == null) {
            closeUninstalled(lease);
            if(quarantineCleanup(slot,failure))throw new java.util.concurrent.CompletionException(unwrap(failure));
            fail(slot, failure == null ? new IllegalStateException("Delivery returned no module lease") : failure);
            return;
        }
        slot.state = State.STARTING;
        slot.lease = lease;
        slot.scope = new ModuleScope(slot.id, () -> authorized(slot.id, generation), () -> registrationAuthorized(slot.id, generation), owner);
        try {
            if (!lease.authorized()) throw new SecurityException("Module lease is no longer authorized");
            ClassLoader loader = lease.definingLoader();
            if (loader == null || loader == ModuleFeature.class.getClassLoader())
                throw new SecurityException("Module implementation must have an independent class loader");
            ModuleFeature feature;
            try (var ownership = slot.scope.enter()) {
                feature = lease.create(new ModuleServices(slot.id, generation, slot.scope, hooks, () -> context.apply(slot.id)));
            }
            if (feature == null || feature.getClass().getClassLoader() != loader)
                throw new SecurityException("Module entrypoint returned resident or foreign implementation");
            slot.feature = feature;
            if (!lease.authorized()) throw new SecurityException("Module authorization expired during initialization");
            slot.state = State.AVAILABLE;
            listener.installed(slot.id, feature);
        } catch (Throwable problem) {
            fail(slot, problem);
            retire(slot);
        }
    }

    private void fail(Slot slot, Throwable failure) {
        slot.state = State.FAILED;
        slot.retryRequired = true;
        slot.wanted = false;
        listener.failed(slot.id, failure);
    }
    private static Throwable unwrap(Throwable failure){
        while((failure instanceof java.util.concurrent.CompletionException || failure instanceof java.util.concurrent.ExecutionException)
                && failure.getCause()!=null)failure=failure.getCause();
        return failure;
    }
    private boolean quarantineCleanup(Slot slot,Throwable failure){
        Throwable cause=unwrap(failure);
        if(!(cause instanceof ModulePayloadProvider.CleanupFailure))return false;
        slot.quarantined=true;fail(slot,cause);return true;
    }

    private boolean registrationAuthorized(String id, long generation) {
        Slot slot = publishedSlots.get(id);
        return !closed && slot != null && slot.generation == generation && slot.wanted
            && (slot.state == State.STARTING || slot.state == State.AVAILABLE)
            && slot.lease != null && slot.lease.authorized();
    }

    public boolean authorized(String id, long generation) {
        Slot slot = publishedSlots.get(id);
        return !closed && slot != null && slot.generation == generation && slot.state == State.AVAILABLE
            && slot.wanted && slot.started && slot.lease != null && slot.lease.authorized();
    }

    public boolean authorized(String id) {
        Slot slot = publishedSlots.get(id);
        return slot != null && authorized(id, slot.generation);
    }

    /** Loaded and authorized to initialize, but callbacks remain blocked until onEnable completes. */
    public boolean ready(String id) {
        Slot slot = publishedSlots.get(id);
        return !closed && slot != null && slot.state == State.AVAILABLE && slot.wanted
            && slot.lease != null && slot.lease.authorized();
    }

    public void started(String id) {
        checkOwner();
        if (!ready(id)) throw new IllegalStateException("Module initialization lost authorization");
        slots.get(id).started = true;
    }

    public void paused(String id) {
        checkOwner();
        Slot slot = slots.get(id);
        if (slot != null) slot.started = false;
    }

    public void abort(String id, Throwable failure) {
        checkOwner();
        Slot slot = slots.get(id);
        if (slot == null) throw new IllegalArgumentException("Unknown module");
        fail(slot, failure);
        retire(slot);
    }

    public ModuleScope.Context enter(String id) {
        checkOwner();
        Slot slot = slots.get(id);
        if (slot == null || slot.scope == null || !slot.scope.open())
            throw new IllegalStateException("Module scope is unavailable");
        return slot.scope.enter();
    }

    private void retire(Slot slot) {
        if (slot.quarantined || slot.state == State.RETIRING || slot.state == State.OFF) return;
        boolean fetching=slot.state==State.FETCHING;
        long retiringGeneration=slot.generation;
        slot.started = false;
        slot.state = State.RETIRING;
        ++slot.generation;
        if (slot.lease == null) {
            // Commit retirement before cancellation can synchronously complete
            // an unstarted acquisition. Its completion remains the drain barrier.
            if(fetching)try{provider.cancel(slot.id,retiringGeneration);}
            catch(Throwable failure){
                slot.quarantined=true;fail(slot,failure);slot.pending.completeExceptionally(failure);
            }
            return; // Providers without cancellation still close late deliveries without installing.
        }
        Throwable stopFailure = null;
        if (slot.feature != null) {
            try { listener.retiring(slot.id, slot.feature); }
            catch (Throwable failure) { stopFailure = failure; }
        }
        slot.feature = null;
        slot.scope.close();
        Throwable cleanupFailure = stopFailure;
        CompletableFuture<Void> pending = new CompletableFuture<>();
        slot.pending = pending;
        slot.scope.retired().whenComplete((ignored, failure) -> {
            try { owner.accept(() -> {
                try {
                    Throwable problem = cleanupFailure != null ? cleanupFailure : failure;
                    if (problem != null) {
                        // A failed drain may still own executing code. Do not pretend that it unloaded.
                        slot.quarantined = true;
                        fail(slot, problem);
                        pending.completeExceptionally(problem);
                        return;
                    }
                    try { slot.lease.close(); }
                    catch (Throwable closeFailure) {
                        slot.quarantined = true;
                        fail(slot, closeFailure);
                        pending.completeExceptionally(closeFailure);
                        return;
                    }
                    slot.lease = null;
                    slot.scope = null;
                    slot.state = slot.retryRequired ? State.FAILED : State.OFF;
                    pending.complete(null);
                } catch (Throwable unexpected) { pending.completeExceptionally(unexpected); }
            }); } catch (Throwable schedulingFailure) { pending.completeExceptionally(schedulingFailure); }
        });
    }

    private void closeUninstalled(ModulePayloadLease lease) {
        if (lease == null) return;
        try { lease.close(); }
        catch (Throwable failure) {
            // Keep a failed cleanup reachable and fail shutdown verification; never report it unloaded.
            quarantinedDeliveries.add(lease);
            throw new java.util.concurrent.CompletionException("Uninstalled module cleanup failed", failure);
        }
    }

    public State state(String id) {
        Slot slot = publishedSlots.get(id);
        return slot == null ? State.OFF : slot.state;
    }

    public Map<String, State> states() {
        checkOwner();
        Map<String, State> result = new HashMap<>();
        slots.forEach((id, slot) -> result.put(id, slot.state));
        return Map.copyOf(result);
    }

    @Override public void close() {
        checkOwner();
        if (closed) return;
        closed = true;
        for (Slot slot : slots.values()) { slot.wanted = false; retire(slot); }
    }

    public CompletionStage<Void> stopped() {
        checkOwner();
        if (!closed) throw new IllegalStateException("Stop modules before observing retirement");
        return CompletableFuture.allOf(slots.values().stream().map(slot -> slot.pending)
            .toArray(CompletableFuture[]::new)).minimalCompletionStage();
    }
}
