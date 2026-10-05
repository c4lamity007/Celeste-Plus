package dev.c4lam.celeste.action;

import dev.c4lam.celeste.gui.HudTransparentScreen;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.delivery.ModuleScope;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** Resident arbitration and slot transport only. Item decisions and sequences belong to payload policies. */
@SuppressWarnings("try")
public final class HeldUseBroker {
    public static final HeldUseBroker INSTANCE = new HeldUseBroker();
    private static final int PRIORITY = 190;
    public interface Policy {
        void start(ModuleContext context);
        void action(ModuleContext context);
        void stop(MinecraftClient client);
        boolean wantsUse();
        default boolean canYield() { return false; }
        default boolean blockOnRetire() { return false; }
    }
    private final CopyOnWriteArrayList<Registration> registrations = new CopyOnWriteArrayList<>();
    private Registration active;
    private Object observedWorld, observedPlayer;
    private long startTick = Long.MIN_VALUE, actionTick = Long.MIN_VALUE;
    private boolean blockUntilRelease;
    private final ModuleScope cleanupScope = new ModuleScope();
    private final boolean[] consumedKeys = new boolean[349];
    private long resumeTick;
    private boolean silent;
    private ClientPlayerEntity transportPlayer;
    private int visibleSlot = -1, logicalSlot = -1;
    private long interactionTick = Long.MIN_VALUE;
    private HeldUseBroker() { }

    public Registration register(Policy policy, int priority) {
        ModuleScope scope = ModuleScope.current();
        if (scope != null && !scope.canRegister())
            throw new IllegalStateException("Module registration is not authorized");
        Registration registration = new Registration(policy, priority, ModuleScope.current());
        registrations.add(registration);
        try {
            if (registration.scope != null) registration.scope.own(registration, registration.detached());
            return registration;
        } catch (RuntimeException failure) { registration.close(); throw failure; }
    }
    public boolean active(Policy policy) { return active != null && active.policy == policy; }
    public boolean authorized(Policy policy) {
        for (Registration registration : registrations)
            if (registration.policy == policy) return registration.allowed();
        return false;
    }
    public void run(Policy policy, Runnable callback) {
        for (Registration registration : registrations) if (registration.policy == policy) {
            registration.call(current -> { callback.run(); return null; }, null); return;
        }
    }
    public void onStartTick(ModuleContext context) {
        long tick = ActionScheduler.getInstance().tickNumber();
        if (startTick == tick) return;
        startTick = tick;
        MinecraftClient client = context.client();
        if (client == null || client.world != observedWorld || client.player != observedPlayer) {
            releaseTransport(client); blockUntilRelease = false; resumeTick = 0;
            observedWorld = client == null ? null : client.world;
            observedPlayer = client == null ? null : client.player;
        }
        if (client != null && client.getWindow() != null && client.isWindowFocused())
            for (int key = 32; key < consumedKeys.length; key++)
                if (consumedKeys[key] && !InputUtil.isKeyPressed(client.getWindow(), key)) consumedKeys[key] = false;
        boolean held = client != null && client.options != null && UseInputLease.physicalDown(client);
        if (!held) { blockUntilRelease = false; resumeTick = 0; }
        if (active != null && !active.allowed()) active.close();
        if (client != null && client.currentScreen instanceof HudTransparentScreen) {
            UseInputLease.getInstance().release(this, client); return;
        }
        if (blockUntilRelease) {
            holdCleanupInput(client, tick, held);
            return;
        }
        if (held && tick < resumeTick && active != null) {
            active.call(policy -> {
                if (silent && !renew(client)) releaseTransport(client);
                UseInputLease.getInstance().acquire(this, PRIORITY, tick, client);
                return null;
            }, null);
            return;
        }
        Registration previous = active;
        if (active != null && active.call(Policy::canYield, false)) {
            for (Registration candidate : registrations.stream().sorted((a,b) -> Integer.compare(b.priority, a.priority)).toList()) {
                if (candidate.priority <= previous.priority || !candidate.allowed()) continue;
                active = candidate;
                if (candidate.call(policy -> { policy.start(context); return policy.wantsUse(); }, false)) return;
            }
            active = previous;
        }
        if (active != null) {
            Registration current = active;
            boolean keep = current.call(policy -> { policy.start(context); return policy.wantsUse(); }, false);
            if (keep) return;
            active = null;
        }
        for (Registration candidate : registrations.stream().sorted((a,b) -> Integer.compare(b.priority, a.priority)).toList()) {
            if (candidate == previous || !candidate.allowed()) continue;
            active = candidate;
            if (candidate.call(policy -> { policy.start(context); return policy.wantsUse(); }, false)) return;
            active = null;
        }
        releaseTransport(client);
        UseInputLease.getInstance().release(this, client);
    }
    public void onActionTick(ModuleContext context) {
        long tick = ActionScheduler.getInstance().tickNumber();
        if (actionTick == tick) return;
        actionTick = tick;
        Registration current = active;
        if (current != null && ActionScheduler.getInstance().tickNumber() < resumeTick) {
            current.call(policy -> { ActionScheduler.getInstance().submit(this, PRIORITY, client -> { }); return null; }, null);
            return;
        }
        if (current != null) current.call(policy -> { policy.action(context); return null; }, null);
    }
    private void holdCleanupInput(MinecraftClient client, long tick, boolean held) {
        // Finishing input suppression after interruption is resident cleanup, never module execution.
        try (var ownership = cleanupScope.enter()) {
            if (held) UseInputLease.getInstance().acquire(this, PRIORITY, tick, client);
            else UseInputLease.getInstance().release(this, client);
        }
    }
    public boolean consumeKey(int key, boolean down) {
        if (key < 32 || key >= consumedKeys.length || !down || consumedKeys[key]) return false;
        consumedKeys[key] = true; return true;
    }
    public boolean consumedKey(int key) { return key >= 32 && key < consumedKeys.length && consumedKeys[key]; }
    public void continueAfter(long tick) { resumeTick = Math.max(resumeTick, tick); }
    public void blockUntilRelease() { blockUntilRelease = true; }

    public boolean silent() { return silent; }
    public boolean ownsTransport(MinecraftClient client) {
        return !silent || client != null && client.player == transportPlayer
            && client.player.getInventory().getSelectedSlot() == visibleSlot
            && SilentSlotManager.getInstance().isOwnedBy(this)
            && SilentSlotManager.getInstance().requestedSlot() == logicalSlot;
    }
    public boolean takeover(MinecraftClient client) {
        return silent && (client == null || client.player != transportPlayer
            || client.player.getInventory().getSelectedSlot() != visibleSlot);
    }
    public int selected(ClientPlayerEntity player) {
        return silent && player == transportPlayer && logicalSlot >= 0 && logicalSlot < 9
            ? logicalSlot : player.getInventory().getSelectedSlot();
    }
    public ItemStack held(ClientPlayerEntity player) {
        int slot = selected(player); return slot >= 0 && slot < 9 ? player.getInventory().getStack(slot) : ItemStack.EMPTY;
    }
    public boolean select(MinecraftClient client, int slot, boolean silently) {
        if (slot < 0 || slot >= 9) return false;
        if (selected(client.player) == slot) return true;
        SilentSlotManager slots = SilentSlotManager.getInstance();
        if (silently || silent) {
            if (!silent) { silent = true; transportPlayer = client.player; visibleSlot = client.player.getInventory().getSelectedSlot(); logicalSlot = visibleSlot; }
            if (!slots.acquire(this, slot, PRIORITY, 2)) return false;
            logicalSlot = slot; return true;
        }
        return slots.selectVisibleSlot(client, slot);
    }
    public boolean renew(MinecraftClient client) {
        return !silent || ownsTransport(client) && SilentSlotManager.getInstance().acquire(this, logicalSlot, PRIORITY, 2);
    }
    public boolean project(MinecraftClient client, java.util.function.BooleanSupplier operation) {
        if (!silent) return operation.getAsBoolean();
        if (!ownsTransport(client)) return false;
        SilentSlotManager slots = SilentSlotManager.getInstance();
        int previous = slots.beginLocalProjection(client.player.getInventory());
        try { return operation.getAsBoolean(); }
        finally { slots.endLocalProjection(client.player.getInventory(), previous); }
    }
    public void interacted() { interactionTick = ActionScheduler.getInstance().tickNumber(); SilentSlotManager.getInstance().noteInteraction(this); }
    public void releaseTransport(MinecraftClient client) {
        if (silent && SilentSlotManager.getInstance().isOwnedBy(this) && client != null && client.isOnThread()) {
            SilentSlotManager slots = SilentSlotManager.getInstance();
            if (interactionTick == ActionScheduler.getInstance().tickNumber()) slots.releaseAfterAction(this);
            slots.release(this);
        }
        silent = false; transportPlayer = null; visibleSlot = logicalSlot = -1;
    }
    public final class Registration implements AutoCloseable {
        private volatile Policy policy;
        private final int priority;
        private final ModuleScope scope;
        private final CompletableFuture<Void> detached = new CompletableFuture<>();
        private int executing;
        private boolean closed, cleaned;
        private Registration(Policy policy, int priority, ModuleScope scope) {
            this.policy = java.util.Objects.requireNonNull(policy); this.priority = priority; this.scope = scope;
        }
        private synchronized boolean allowed() { return !closed && policy != null && (scope == null || scope.allowed()); }
        private <T> T call(Function<Policy,T> invocation, T fallback) {
            Policy current;
            synchronized (this) { if (!allowed()) return fallback; current = policy; executing++; }
            ModuleScope.Context context = null;
            try {
                if (scope != null && (context = scope.tryEnter()) == null) return fallback;
                return invocation.apply(current);
            } finally {
                if (context != null) context.close();
                synchronized (this) { executing--; complete(); }
            }
        }
        public CompletionStage<Void> detached() { return detached.minimalCompletionStage(); }
        private void complete() { if (closed && cleaned && executing == 0) detached.complete(null); }
        @Override public void close() {
            Policy retiring;
            synchronized (this) { if (closed) return; closed = true; retiring = policy; policy = null; }
            registrations.remove(this);
            MinecraftClient client = MinecraftClient.getInstance();
            Runnable cleanup = () -> {
                try {
                    if (active == this) {
                        if (retiring.blockOnRetire()) blockUntilRelease = true;
                        active = null;
                        retiring.stop(client);
                        releaseTransport(client);
                        if (blockUntilRelease && client != null && client.options != null && UseInputLease.physicalDown(client))
                            holdCleanupInput(client, ActionScheduler.getInstance().tickNumber(), true);
                        else UseInputLease.getInstance().release(HeldUseBroker.this, client);
                    } else retiring.stop(client);
                    if (registrations.isEmpty()) {
                        blockUntilRelease = false; resumeTick = 0;
                        releaseTransport(client);
                        UseInputLease.getInstance().release(HeldUseBroker.this, client);
                        ActionScheduler.getInstance().cancel(HeldUseBroker.this);
                    }
                    synchronized (this) { cleaned = true; complete(); }
                } catch (Throwable failure) { detached.completeExceptionally(failure); }
            };
            if (client == null || client.isOnThread()) cleanup.run(); else client.execute(cleanup);
        }
    }
}
