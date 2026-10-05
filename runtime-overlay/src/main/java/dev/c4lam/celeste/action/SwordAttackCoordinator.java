package dev.c4lam.celeste.action;

import dev.c4lam.celeste.hud.HudTargetTracker;
import dev.c4lam.celeste.module.delivery.ModuleScope;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;

/**
 * Bridges two narrow vanilla hooks to module state. It never creates an attack:
 * the original user/vanilla invocation either proceeds once or is cancelled.
 */
public final class SwordAttackCoordinator {
    private static final AttackScope PASS = new AttackScope(true, null, null, -1, true, -1, false, null, false, null);
    private static final AttackScope DENY = new AttackScope(false, null, null, -1, true, -1, false, null, false, null);

    private static final CopyOnWriteArrayList<Registration> POLICIES = new CopyOnWriteArrayList<>();
    private static AttackScope vanillaClick;

    private SwordAttackCoordinator() { }

    /** Resident data-only contract; all selection and module behavior stays in child code. */
    public interface AttackPolicy {
        default SwapIntent select(MinecraftClient client) { return null; }
        default boolean cancel(MinecraftClient client, boolean validatedReplacement) { return false; }
        default boolean meleeOnly() { return false; }
        default void opened(ClientPlayerEntity player, int slot, boolean silent) { }
        default void finished(MinecraftClient client, ClientPlayerEntity player, boolean retained) { }
        default void attacked(MinecraftClient client, Entity target) { }
        default boolean queue(MinecraftClient client, LivingEntity target) { return false; }
    }

    public static Registration register(Object owner, AttackPolicy policy) {
        ModuleScope scope = ModuleScope.current();
        if (scope != null && !scope.canRegister())
            throw new IllegalStateException("Module registration is not authorized");
        Registration registration = new Registration(owner, policy, ModuleScope.current());
        POLICIES.add(registration);
        try {
            if (registration.scope != null) registration.scope.own(registration, registration.detached());
            return registration;
        } catch (RuntimeException failure) { registration.close(); throw failure; }
    }

    public static final class Registration implements AutoCloseable {
        private final Object owner;
        private final ModuleScope scope;
        private final CompletableFuture<Void> detached = new CompletableFuture<>();
        private AttackPolicy policy;
        private int executing;
        private boolean closed;
        private Registration(Object owner, AttackPolicy policy, ModuleScope scope) {
            this.owner = java.util.Objects.requireNonNull(owner);
            this.policy = java.util.Objects.requireNonNull(policy);
            this.scope = scope;
        }
        private <T> T call(Function<AttackPolicy,T> action, T fallback) {
            AttackPolicy current;
            synchronized (this) {
                if (closed || policy == null || scope != null && !scope.allowed()) return fallback;
                executing++;
                current = policy;
            }
            ModuleScope.Context ownership = null;
            try {
                if (scope != null) {
                    try { ownership = scope.enter(); }
                    catch (IllegalStateException retiring) {
                        if (!scope.open()) return fallback;
                        throw retiring;
                    }
                }
                return action.apply(current);
            } finally {
                if (ownership != null) ownership.close();
                synchronized (this) {
                    executing--;
                    if (closed && executing == 0) detached.complete(null);
                }
            }
        }
        @Override public void close() {
            POLICIES.remove(this);
            synchronized (this) {
                closed = true;
                policy = null;
                if (executing == 0) detached.complete(null);
            }
        }
        public CompletionStage<Void> detached() { return detached.minimalCompletionStage(); }
    }

    // Legacy startup compatibility: activation now performs scoped registration.
    public static void install(Object... unused) { }
    public static void installMaceSwap(Object unused) { }

    /** Generic capability request; disabled/missing providers have no implementation loaded. */
    public static boolean queueAutomaticMaceStun(MinecraftClient client, LivingEntity target) {
        for (Registration registration : POLICIES)
            if (registration.call(policy -> policy.queue(client, target), false)) return true;
        return false;
    }

    /** Called at the head of MinecraftClient.doAttack. */
    public static AttackScope beginVanillaAttack(MinecraftClient client) {
        AttackScope scope = openVanillaAttack(client);
        vanillaClick = scope;
        return scope;
    }

    private static AttackScope openVanillaAttack(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null
            || client.interactionManager == null || client.currentScreen != null) {
            return PASS;
        }

        SwapIntent intent = null;
        Registration selected = null;
        for (Registration registration : POLICIES) {
            SwapIntent candidate = registration.call(policy -> policy.select(client), null);
            if (candidate != null && candidate.owner == registration.owner
                && (intent == null || candidate.priority > intent.priority)) {
                intent = candidate;
                selected = registration;
            }
        }
        boolean hasSelection = intent != null;
        for (Registration registration : POLICIES)
            if (registration.call(policy -> policy.cancel(client, hasSelection), false)) return DENY;
        if (intent == null) {
            return PASS;
        }

        PlayerInventory inventory = client.player.getInventory();
        int visibleSlot = inventory.getSelectedSlot();
        if (intent.slot < 0 || intent.slot > 8) {
            return PASS;
        }
        if (intent.slot == visibleSlot && !intent.stunSlam) {
            return PASS;
        }
        SilentSlotManager slots = SilentSlotManager.getInstance();
        int projectionToken = -1;
        if (intent.silent) {
            if (!slots.acquire(intent.owner, intent.slot, intent.priority, intent.holdTicks)) {
                return PASS;
            }
            projectionToken = slots.beginLocalProjection(inventory);
        } else if (!slots.selectVisibleSlot(client, intent.slot)) {
            return PASS;
        }
        if (intent.stunSlam && selected != null) {
            SwapIntent opening = intent;
            selected.call(policy -> { policy.opened(client.player, opening.slot, opening.silent); return null; }, null);
        }
        // Spear doAttack is charge-gated and sends STAB. A left-click that
        // Mace Swap owns must not go through that path: slot packet already
        // went out, then a mace/axe attackEntity like sword.
        boolean replaceVanillaClick = selected != null && selected.call(AttackPolicy::meleeOnly, false)
            && inventory.getStack(visibleSlot).get(DataComponentTypes.PIERCING_WEAPON) != null;
        return new AttackScope(true, intent.owner, client.player, visibleSlot,
            intent.silent, projectionToken, intent.stunSlam, intent.clickTarget, replaceVanillaClick, selected);
    }

    /**
     * After the swapped slot packet is sent, vanilla {@code doAttack} uses this
     * crosshair for the left-click. Held-item reach (spear) can be past the
     * default 3-block pick, so the approved target is placed here.
     */
    public static void prepareVanillaClick(MinecraftClient client, AttackScope scope) {
        if (client == null || scope == null || scope.meleeTarget == null
            || scope.meleeTarget.isRemoved()) {
            return;
        }
        client.crosshairTarget = new EntityHitResult(
            scope.meleeTarget, scope.meleeTarget.getBoundingBox().getCenter());
    }

    /**
     * Vanilla {@code doAttack} reads MAINHAND via {@code getStackInHand}. Return
     * the already-swapped stack so the click is the same melee as a sword swap.
     */
    public static ItemStack mainHandForVanillaClick(ItemStack vanilla) {
        if (vanillaClick == null || vanillaClick.owner == null) {
            return vanilla;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return vanilla;
        }
        int slot = vanillaClick.silent
            ? SilentSlotManager.getInstance().requestedSlot()
            : client.player.getInventory().getSelectedSlot();
        if (slot < 0 || slot > 8) {
            return vanilla;
        }
        return client.player.getInventory().getStack(slot);
    }

    /**
     * Spear left-click: do not run vanilla {@code doAttack} (full-charge STAB).
     * The attack key already selected/swapped; send the same melee as sword.
     */
    public static boolean performSwappedMelee(MinecraftClient client, AttackScope scope) {
        if (client == null || client.player == null || client.interactionManager == null
            || scope == null || scope.meleeTarget == null || scope.meleeTarget.isRemoved()) {
            return false;
        }
        client.interactionManager.attackEntity(client.player, scope.meleeTarget);
        client.player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    /** Called at every normal return from MinecraftClient.doAttack. */
    public static void finishVanillaAttack(MinecraftClient client, AttackScope scope) {
        try {
            finishVanillaAttackBody(client, scope);
        } finally {
            if (vanillaClick == scope) {
                vanillaClick = null;
            }
        }
    }

    private static void finishVanillaAttackBody(MinecraftClient client, AttackScope scope) {
        if (scope == null || scope.owner == null) {
            return;
        }
        if (scope.stunSlam) {
            if (scope.silent) {
                SilentSlotManager.getInstance().endLocalProjection(
                    scope.player == null ? null : scope.player.getInventory(), scope.projectionToken);
            }
            boolean finished = scope.registration != null && scope.registration.call(policy -> {
                policy.finished(client, scope.player, true); return true;
            }, false);
            if (!finished) SilentSlotManager.getInstance().releaseAfterAction(scope.owner);
            return;
        }
        if (client != null && client.player == scope.player
            && scope.visibleSlot >= 0 && scope.visibleSlot <= 8) {
            if (scope.silent) {
                try {
                    SilentSlotManager.getInstance().endLocalProjection(
                        scope.player.getInventory(), scope.projectionToken);
                } finally {
                    SilentSlotManager.getInstance().releaseAfterAction(scope.owner);
                }
            } else {
                SilentSlotManager.getInstance().restoreVisibleNextTick(
                    scope.player, scope.visibleSlot,
                    client.player.getInventory().getSelectedSlot());
            }
        } else if (scope.silent) {
            try {
                SilentSlotManager.getInstance().endLocalProjection(
                    scope.player == null ? null : scope.player.getInventory(), scope.projectionToken);
            } finally {
                SilentSlotManager.getInstance().releaseAfterAction(scope.owner);
            }
        }
        if (scope.registration != null) scope.registration.call(policy -> {
            policy.finished(client, scope.player, false); return null;
        }, null);
    }

    /** Called only when vanilla's interaction manager is actually attacking an entity. */
    public static void onVanillaEntityAttack(MinecraftClient client, Entity entity) {
        HudTargetTracker.getInstance().markAttacked(client, entity);
        for (Registration registration : POLICIES) registration.call(policy -> {
            policy.attacked(client, entity); return null;
        }, null);
    }

    public static final class SwapIntent {
        private final Object owner;
        private final int slot;
        private final int priority;
        private final boolean silent;

        private final boolean stunSlam;
        private final int holdTicks;

        private final LivingEntity clickTarget;

        public SwapIntent(Object owner, int slot, int priority, boolean silent) {
            this(owner, slot, priority, silent, false, 1, null);
        }

        public SwapIntent(Object owner, int slot, int priority, boolean silent,
                   boolean stunSlam, int holdTicks) {
            this(owner, slot, priority, silent, stunSlam, holdTicks, null);
        }

        public SwapIntent(Object owner, int slot, int priority, boolean silent,
                   boolean stunSlam, int holdTicks, LivingEntity clickTarget) {
            this.owner = owner;
            this.slot = slot;
            this.priority = priority;
            this.silent = silent;
            this.stunSlam = stunSlam;
            this.holdTicks = Math.max(1, holdTicks);
            this.clickTarget = clickTarget;
        }
    }

    public static final class AttackScope {
        private final boolean proceed;
        private final Object owner;
        private final ClientPlayerEntity player;
        private final int visibleSlot;
        private final boolean silent;
        private final int projectionToken;
        private final boolean stunSlam;
        private final LivingEntity meleeTarget;
        private final boolean replaceVanillaClick;
        private final Registration registration;

        private AttackScope(boolean proceed, Object owner, ClientPlayerEntity player, int visibleSlot,
                            boolean silent, int projectionToken, boolean stunSlam,
                            LivingEntity meleeTarget, boolean replaceVanillaClick, Registration registration) {
            this.proceed = proceed;
            this.owner = owner;
            this.player = player;
            this.visibleSlot = visibleSlot;
            this.silent = silent;
            this.projectionToken = projectionToken;
            this.stunSlam = stunSlam;
            this.meleeTarget = meleeTarget;
            this.replaceVanillaClick = replaceVanillaClick;
            this.registration = registration;
        }

        public boolean proceed() {
            return proceed;
        }

        public boolean replaceVanillaClick() {
            return replaceVanillaClick;
        }
    }
}
