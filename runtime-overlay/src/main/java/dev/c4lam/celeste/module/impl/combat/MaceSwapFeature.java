package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.action.SwordAttackCoordinator;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Selects a mace for physical attacks.  Its optional Stun Slam is a short,
 * physical-click-initiated transaction: axe attack, next-tick mace attack,
 * then a separate restore tick back to the exact original hotbar slot.
 */
public final class MaceSwapFeature extends MaceSwapCombatFeature {
    private static final int NORMAL_SWAP_PRIORITY = 170;
    private static final int STUN_SLAM_PRIORITY = 200;
    private static final double STUN_RANGE = 6.0D;

    private SwordAttackCoordinator.Registration attackRegistration;
    private StunStage stunStage = StunStage.NONE;
    private LivingEntity stunTarget;
    private ClientPlayerEntity stunPlayer;
    private int originalSlot = -1;
    private int activeSlot = -1;
    private boolean silent;
    private boolean slotOwned;
    private boolean firstHitConfirmed;
    private boolean automaticStun;

    @Override
    public void onEnable(ModuleContext context) {
        if (attackRegistration != null) attackRegistration.close();
        attackRegistration = SwordAttackCoordinator.register(this, new SwordAttackCoordinator.AttackPolicy() {
            @Override public SwordAttackCoordinator.SwapIntent select(MinecraftClient client) {
                PhysicalAttackSwap selected = selectPhysicalAttack(client);
                return selected == null ? null : new SwordAttackCoordinator.SwapIntent(MaceSwapFeature.this,
                    selected.slot(), selected.priority(), selected.silent(), selected.stunSlam(),
                    selected.holdTicks(), selected.target());
            }
            @Override public boolean meleeOnly() { return true; }
            @Override public void opened(ClientPlayerEntity player, int slot, boolean silent) {
                beginStunOpening(player, slot, silent);
            }
            @Override public void finished(MinecraftClient client, ClientPlayerEntity player, boolean retained) {
                if (retained) finishStunOpening(client, player); else finishNormalPhysicalAttack();
            }
            @Override public void attacked(MinecraftClient client, net.minecraft.entity.Entity target) {
                if (target instanceof LivingEntity living) onVanillaEntityAttack(client, living);
            }
            @Override public boolean queue(MinecraftClient client, LivingEntity target) {
                return queueAutomaticStun(client, target);
            }
        });
    }

    @Override
    public String name() {
        return "Mace Swap";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        if (stunStage == StunStage.NONE) return;
        if (stunStage == StunStage.RESTORE) {
            restoreStunTransaction(context.client());
            releaseIntent();
            return;
        }
        if (!ownsStunTransaction(context.client())
            || !validStunTarget(context.client(), stunTarget)
            || MaceSwapCombatLoadout.immediateWeaponSlot(context.client(), stunTarget, MaceSwapCombatLoadout.Weapon.MACE) < 0) {
            clearStunTransaction(context.client(), true);
            releaseIntent();
            return;
        }
        requestRotation(stunTarget, STUN_SLAM_PRIORITY);
    }

    @Override
    public void onActionTick(ModuleContext context) {
        if ((stunStage != StunStage.AXE && stunStage != StunStage.MACE) || stunTarget == null) return;
        MinecraftClient client = context.client();
        if (!validStunTarget(client, stunTarget)) {
            clearStunTransaction(client, true);
            return;
        }

        ActionScheduler.getInstance().submit(this, STUN_SLAM_PRIORITY, actionClient -> {
            if ((stunStage != StunStage.AXE && stunStage != StunStage.MACE)
                || !ownsStunTransaction(actionClient)
                || !validStunTarget(actionClient, stunTarget)
                || !rotationReady(stunTarget, 7.5D)) {
                clearStunTransaction(actionClient, true);
                return;
            }
            MaceSwapCombatLoadout.Weapon weapon = stunStage == StunStage.AXE
                ? MaceSwapCombatLoadout.Weapon.AXE : MaceSwapCombatLoadout.Weapon.MACE;
            int slot = MaceSwapCombatLoadout.immediateWeaponSlot(actionClient, stunTarget, weapon);
            if (slot < 0 || !MaceSwapVanillaCombatReach.canAttack(
                actionClient, stunTarget, slot, STUN_RANGE, true)
                || !selectStunSlot(actionClient, slot)) {
                clearStunTransaction(actionClient, true);
                return;
            }
            if (!attackWithStunSlot(actionClient, stunTarget, weapon)) {
                clearStunTransaction(actionClient, true);
                return;
            }
            if (weapon == MaceSwapCombatLoadout.Weapon.AXE) {
                stunStage = StunStage.MACE;
            } else {
                MaceSwapCombatLoadout.markMaceAction();
                stunStage = StunStage.RESTORE;
            }
        });
    }

    /** Queues the same Mace Swap-owned combo for a blocking Auto Mace target. */
    public boolean queueAutomaticStun(MinecraftClient client, LivingEntity target) {
        if (!MaceSwapCombatLoadout.enabled(name()) || !MaceSwapCombatLoadout.maceSwapAutoStunSlam()
            || client == null || client.player == null || stunStage != StunStage.NONE
            || target == null || !target.isBlocking() || !MaceSwapCombatLoadout.maceCooldownElapsed()
            || !validStunTarget(client, target)) {
            return false;
        }
        int axe = MaceSwapCombatLoadout.immediateWeaponSlot(client, MaceSwapCombatLoadout.Weapon.AXE);
        int mace = MaceSwapCombatLoadout.immediateWeaponSlot(client, target, MaceSwapCombatLoadout.Weapon.MACE);
        if (axe < 0 || mace < 0
            || target.getBoundingBox().squaredMagnitude(client.player.getEyePos()) > STUN_RANGE * STUN_RANGE) {
            return false;
        }
        beginStunTransaction(client, target, MaceSwapCombatLoadout.maceSwapSilent(), true);
        return true;
    }

    /**
     * Called from the existing MinecraftClient#doAttack hook.  A normal Mace
     * Swap returns one ordinary mace scope; Stun Slam returns an axe scope and
     * leaves it open for the next-tick mace half.
     */
    public PhysicalAttackSwap selectPhysicalAttack(MinecraftClient client) {
        if (!MaceSwapCombatLoadout.enabled(name()) || client == null || client.player == null
            || client.world == null || client.interactionManager == null
            || client.currentScreen != null || client.player.hasVehicle()
            || client.interactionManager.isBreakingBlock()
            || stunStage != StunStage.NONE) {
            return null;
        }
        LivingEntity target = resolvePhysicalTarget(client);
        int visible = client.player.getInventory().getSelectedSlot();
        int maceSlot = MaceSwapCombatLoadout.immediateWeaponSlot(client, target, MaceSwapCombatLoadout.Weapon.MACE);
        if (maceSlot < 0 || target == null
            || !MaceSwapVanillaCombatReach.canAttack(client, target, maceSlot, STUN_RANGE, true)) {
            return null;
        }

        boolean silentSwap = MaceSwapCombatLoadout.maceSwapSilent();
        if (MaceSwapCombatLoadout.maceSwapAutoStunSlam() && target.isBlocking()) {
            // Cooldown is consulted only before a brand-new combo begins.
            // Once the axe hit has started, the next-tick mace hit and restore
            // always complete without being delayed by this value.
            if (!MaceSwapCombatLoadout.maceCooldownElapsed()) {
                return null;
            }
            int axeSlot = MaceSwapCombatLoadout.immediateWeaponSlot(client, MaceSwapCombatLoadout.Weapon.AXE);
            if (axeSlot >= 0 && MaceSwapVanillaCombatReach.canAttack(client, target, axeSlot, STUN_RANGE, true)) {
                beginStunTransaction(client, target, silentSwap, false);
                return new PhysicalAttackSwap(axeSlot, silentSwap, true, 3, STUN_SLAM_PRIORITY, target);
            }
        }

        if (maceSlot == visible || !MaceSwapCombatLoadout.maceCooldownElapsed()) {
            return null;
        }
        return new PhysicalAttackSwap(maceSlot, silentSwap, false, 1, NORMAL_SWAP_PRIORITY, target);
    }

    private LivingEntity resolvePhysicalTarget(MinecraftClient client) {
        if (client.crosshairTarget instanceof EntityHitResult hit
            && acceptableClickTarget(client, hit.getEntity())) {
            return (LivingEntity) hit.getEntity();
        }
        return nearestLookTarget(client);
    }

    private LivingEntity nearestLookTarget(MinecraftClient client) {
        double range = MaceSwapVanillaCombatReach.heldHitRange(client);
        Vec3d start = client.player.getEyePos();
        Vec3d look = client.player.getRotationVec(1.0F);
        LivingEntity best = null;
        double bestScore = range * range;
        Vec3d end = start.add(look.multiply(range));
        Box area = client.player.getBoundingBox().stretch(look.multiply(range)).expand(1.0D);
        for (net.minecraft.entity.Entity entity : client.world.getOtherEntities(client.player, area)) {
            if (!acceptableClickTarget(client, entity)) {
                continue;
            }
            Box box = entity.getBoundingBox().expand(0.3D);
            var hit = box.raycast(start, end);
            if (hit.isEmpty()) {
                continue;
            }
            double distance = start.squaredDistanceTo(hit.get());
            if (distance <= bestScore) {
                bestScore = distance;
                best = (LivingEntity) entity;
            }
        }
        return best;
    }

    private boolean acceptableClickTarget(MinecraftClient client, net.minecraft.entity.Entity entity) {
        if (!(entity instanceof LivingEntity living) || living == client.player
            || !living.isAlive() || living.isRemoved()) {
            return false;
        }
        if (living instanceof PlayerEntity player
            && (player.isSpectator() || friends.isFriend(player))) {
            return false;
        }
        return true;
    }

    /** Marks that the vanilla first (axe) attack really reached its entity. */
    public void onVanillaEntityAttack(MinecraftClient client, LivingEntity attacked) {
        if (stunStage == StunStage.AXE
            && client != null && client.player == stunPlayer && attacked == stunTarget) {
            firstHitConfirmed = true;
        }
    }

    /** Records the one shared Auto Mace/Mace Swap cadence after a normal physical swap attack. */
    public void finishNormalPhysicalAttack() {
        MaceSwapCombatLoadout.markMaceAction();
    }

    /** Called after the original vanilla click returns and its slot scope ends. */
    public void finishStunOpening(MinecraftClient client, ClientPlayerEntity player) {
        if (stunStage != StunStage.AXE || player != stunPlayer || !firstHitConfirmed) {
            clearStunTransaction(client, true);
            return;
        }
        stunStage = StunStage.MACE;
    }

    /** Coordinator calls this only after it acquired/selected the first axe slot. */
    public void beginStunOpening(ClientPlayerEntity player, int selectedSlot, boolean selectedSilently) {
        if (stunStage != StunStage.AXE || player != stunPlayer) return;
        activeSlot = selectedSlot;
        silent = selectedSilently;
        slotOwned = true;
    }

    private void beginStunTransaction(MinecraftClient client, LivingEntity target, boolean silentSwap,
                                      boolean automatic) {
        clearStunTransaction(client, false);
        stunPlayer = client.player;
        stunTarget = target;
        originalSlot = client.player.getInventory().getSelectedSlot();
        activeSlot = originalSlot;
        silent = silentSwap;
        automaticStun = automatic;
        slotOwned = false;
        firstHitConfirmed = false;
        stunStage = StunStage.AXE;
    }

    private boolean ownsStunTransaction(MinecraftClient client) {
        if (client.player == null || client.player != stunPlayer
            || originalSlot < 0 || originalSlot > 8) return false;
        if (silent) {
            if (automaticStun && stunStage == StunStage.AXE && !slotOwned) return true;
            return slotOwned && SilentSlotManager.getInstance().isOwnedBy(this)
                && client.player.getInventory().getSelectedSlot() == originalSlot;
        }
        return (automaticStun && stunStage == StunStage.AXE && !slotOwned)
            || (slotOwned && client.player.getInventory().getSelectedSlot() == activeSlot);
    }

    private boolean selectStunSlot(MinecraftClient client, int slot) {
        if (client.player == null || slot < 0 || slot > 8) return false;
        SilentSlotManager slots = SilentSlotManager.getInstance();
        if (silent) {
            if (!slots.acquire(this, slot, STUN_SLAM_PRIORITY, 3)) return false;
        } else if (client.player.getInventory().getSelectedSlot() != slot
            && !slots.selectVisibleSlot(client, slot)) {
            return false;
        }
        activeSlot = slot;
        slotOwned = true;
        return true;
    }

    private boolean attackWithStunSlot(MinecraftClient client, LivingEntity target,
                                       MaceSwapCombatLoadout.Weapon weapon) {
        int slot = MaceSwapCombatLoadout.immediateWeaponSlot(client, target, weapon);
        if (client.player == null || !validStunTarget(client, target)
            || slot < 0 || !MaceSwapVanillaCombatReach.canAttack(client, target, slot, STUN_RANGE, true)) {
            return false;
        }
        SilentSlotManager slots = SilentSlotManager.getInstance();
        PlayerInventory inventory = client.player.getInventory();
        int projection = silent ? slots.beginLocalProjection(inventory) : -1;
        try {
            client.interactionManager.attackEntity(client.player, target);
            client.player.swingHand(Hand.MAIN_HAND);
            MaceSwapCombatLoadout.markSwap(client, activeSlot != originalSlot);
            return true;
        } finally {
            if (silent) slots.endLocalProjection(inventory, projection);
        }
    }

    private void restoreStunTransaction(MinecraftClient client) {
        if (client == null || client.player == null || client.player != stunPlayer) {
            clearStunTransaction(client, false);
            return;
        }
        SilentSlotManager slots = SilentSlotManager.getInstance();
        if (silent) {
            if (slotOwned && slots.isOwnedBy(this)) slots.release(this);
        } else if (slotOwned && client.player.getInventory().getSelectedSlot() == activeSlot) {
            slots.selectVisibleSlot(client, originalSlot);
        }
        clearStunState();
    }

    private void clearStunTransaction(MinecraftClient client, boolean restore) {
        if (restore) restoreStunTransaction(client);
        else clearStunState();
    }

    private void clearStunState() {
        stunStage = StunStage.NONE;
        stunTarget = null;
        stunPlayer = null;
        originalSlot = -1;
        activeSlot = -1;
        silent = false;
        slotOwned = false;
        firstHitConfirmed = false;
        automaticStun = false;
    }

    private boolean validStunTarget(MinecraftClient client, LivingEntity target) {
        return MaceSwapTargeting.validLivingTarget(client, target, friends, STUN_RANGE, true);
    }

    @Override
    protected void clearTarget() {
        if (attackRegistration != null) { attackRegistration.close(); attackRegistration = null; }
        clearStunTransaction(MinecraftClient.getInstance(), true);
    }

    @Override
    protected void resetFeatureState() {
        clearStunState();
    }

    public record PhysicalAttackSwap(int slot, boolean silent, boolean stunSlam, int holdTicks,
                                     int priority, LivingEntity target) { }

    private enum StunStage { NONE, AXE, MACE, RESTORE }
}
