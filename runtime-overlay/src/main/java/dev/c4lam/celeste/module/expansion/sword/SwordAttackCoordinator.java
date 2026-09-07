package dev.c4lam.celeste.module.expansion.sword;

import dev.c4lam.celeste.hud.HudTargetTracker;
import dev.c4lam.celeste.module.impl.combat.MaceSwapFeature;
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
    private static final AttackScope PASS = new AttackScope(true, null, null, -1, true, -1, false, null, false);
    private static final AttackScope DENY = new AttackScope(false, null, null, -1, true, -1, false, null, false);

    private static AntiMissFeature antiMiss;
    private static BreachSwapFeature breachSwap;
    private static TotemHitFeature totemHit;
    private static WTapFeature wTap;
    private static STapFeature sTap;
    private static MaceSwapFeature maceSwap;
    private static AttackScope vanillaClick;

    private SwordAttackCoordinator() {
    }

    static void install(AntiMissFeature antiMissFeature,
                        BreachSwapFeature breachSwapFeature,
                        TotemHitFeature totemHitFeature,
                        WTapFeature wTapFeature,
                        STapFeature sTapFeature) {
        antiMiss = antiMissFeature;
        breachSwap = breachSwapFeature;
        totemHit = totemHitFeature;
        wTap = wTapFeature;
        sTap = sTapFeature;
    }

    /** Installs the Mace-page physical attack policy without moving its module. */
    public static void installMaceSwap(MaceSwapFeature maceSwapFeature) {
        maceSwap = maceSwapFeature;
    }

    /** Lets Auto Mace hand a blocking target to Mace Swap's higher-priority combo. */
    public static boolean queueAutomaticMaceStun(MinecraftClient client, LivingEntity target) {
        return maceSwap != null && maceSwap.queueAutomaticStun(client, target);
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

        MaceSwapFeature.PhysicalAttackSwap maceSelection = maceSwap == null
            ? null : maceSwap.selectPhysicalAttack(client);
        SwapIntent maceIntent = maceSelection == null ? null
            : new SwapIntent(maceSwap, maceSelection.slot(), maceSelection.priority(), maceSelection.silent(),
                maceSelection.stunSlam(), maceSelection.holdTicks(), maceSelection.target());
        SwapIntent intent = first(
            totemHit == null ? null : totemHit.select(client),
            breachSwap == null ? null : breachSwap.select(client),
            maceIntent
        );
        if (antiMiss != null && antiMiss.shouldCancel(client, intent != null)) {
            return DENY;
        }
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
        if (intent.stunSlam && intent.owner == maceSwap) {
            maceSwap.beginStunOpening(client.player, intent.slot, intent.silent);
        }
        // Spear doAttack is charge-gated and sends STAB. A left-click that
        // Mace Swap owns must not go through that path: slot packet already
        // went out, then a mace/axe attackEntity like sword.
        boolean replaceVanillaClick = intent.owner == maceSwap
            && inventory.getStack(visibleSlot).get(DataComponentTypes.PIERCING_WEAPON) != null;
        return new AttackScope(true, intent.owner, client.player, visibleSlot,
            intent.silent, projectionToken, intent.stunSlam, intent.clickTarget, replaceVanillaClick);
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
        if (scope.stunSlam && scope.owner == maceSwap) {
            if (scope.silent) {
                SilentSlotManager.getInstance().endLocalProjection(
                    scope.player == null ? null : scope.player.getInventory(), scope.projectionToken);
            }
            maceSwap.finishStunOpening(client, scope.player);
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
        if (scope.owner == maceSwap) {
            maceSwap.finishNormalPhysicalAttack();
        }
    }

    /** Called only when vanilla's interaction manager is actually attacking an entity. */
    public static void onVanillaEntityAttack(MinecraftClient client, Entity entity) {
        dev.c4lam.celeste.module.impl.visual.PlayerModelsFeature.recordAttack(client, entity);
        HudTargetTracker.getInstance().markAttacked(client, entity);
        if (maceSwap != null && entity instanceof LivingEntity living) {
            maceSwap.onVanillaEntityAttack(client, living);
        }
        if (!(entity instanceof LivingEntity living)
            || !SwordExpansionTargets.validLiving(client, living, 6.0D, true)) {
            return;
        }
        if (wTap != null) {
            wTap.onAttack(client, living);
        }
        if (sTap != null) {
            sTap.onAttack(client, living);
        }
    }

    private static SwapIntent first(SwapIntent... intents) {
        SwapIntent selected = null;
        for (SwapIntent intent : intents) {
            if (intent != null && (selected == null || intent.priority > selected.priority)) {
                selected = intent;
            }
        }
        return selected;
    }

    static final class SwapIntent {
        private final Object owner;
        private final int slot;
        private final int priority;
        private final boolean silent;

        private final boolean stunSlam;
        private final int holdTicks;

        private final LivingEntity clickTarget;

        SwapIntent(Object owner, int slot, int priority, boolean silent) {
            this(owner, slot, priority, silent, false, 1, null);
        }

        SwapIntent(Object owner, int slot, int priority, boolean silent,
                   boolean stunSlam, int holdTicks) {
            this(owner, slot, priority, silent, stunSlam, holdTicks, null);
        }

        SwapIntent(Object owner, int slot, int priority, boolean silent,
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

        private AttackScope(boolean proceed, Object owner, ClientPlayerEntity player, int visibleSlot,
                            boolean silent, int projectionToken, boolean stunSlam,
                            LivingEntity meleeTarget, boolean replaceVanillaClick) {
            this.proceed = proceed;
            this.owner = owner;
            this.player = player;
            this.visibleSlot = visibleSlot;
            this.silent = silent;
            this.projectionToken = projectionToken;
            this.stunSlam = stunSlam;
            this.meleeTarget = meleeTarget;
            this.replaceVanillaClick = replaceVanillaClick;
        }

        public boolean proceed() {
            return proceed;
        }

        public boolean replaceVanillaClick() {
            return replaceVanillaClick;
        }
    }
}
