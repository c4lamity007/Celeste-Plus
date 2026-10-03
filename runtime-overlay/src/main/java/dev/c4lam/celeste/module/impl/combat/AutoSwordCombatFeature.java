package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.action.GameplayFoundation;
import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.rotation.MoveFixMode;
import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.rotation.RotationTask;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.util.function.Predicate;

/** Shared lifecycle and validation for client-thread combat features. */
abstract class AutoSwordCombatFeature implements ModuleFeature {
    private static final double RANGE_EPSILON = 1.0E-7D;
    protected final FriendManager friends = FriendManager.getInstance();

    @Override
    public final void onDisable(ModuleContext context) {
        clearTarget();
        resetFeatureState();
        GameplayFoundation.releaseOwner(this);
    }

    @Override
    public final void onWorldChanged(ModuleContext context) {
        clearTarget();
        resetFeatureState();
        GameplayFoundation.releaseOwner(this);
    }

    protected void clearTarget() {
    }

    protected void resetFeatureState() {
    }

    protected final void releaseIntent() {
        GameplayFoundation.releaseOwner(this);
    }

    protected final void requestRotation(Entity entity, int priority) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || entity == null) {
            return;
        }
        requestRotation(Rotation.lookingAt(client.player, AutoSwordTargeting.aimPoint(entity)), priority);
    }

    protected final void requestRotation(Vec3d point, int priority) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || point == null) {
            return;
        }
        requestRotation(Rotation.lookingAt(client.player, point), priority);
    }

    private void requestRotation(Rotation rotation, int priority) {
        RotationManager.getInstance().submit(RotationTask.builder(this, rotation)
            .priority(priority)
            .lifetimeTicks(1)
            .smoothing(180.0F, 180.0F)
            .sensitivityQuantized(true)
            .moveFix(MoveFixMode.STRICT)
            .build());
    }

    protected final boolean rotationReady(Entity target, double toleranceDegrees) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || target == null) {
            return false;
        }
        RotationManager rotations = RotationManager.getInstance();
        return rotations.isOwnedBy(this)
            && rotations.serverRotation().angularDistanceTo(
                Rotation.lookingAt(client.player, AutoSwordTargeting.aimPoint(target))) <= toleranceDegrees;
    }

    protected final boolean rotationReady(Vec3d point, double toleranceDegrees) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || point == null) {
            return false;
        }
        RotationManager rotations = RotationManager.getInstance();
        return rotations.isOwnedBy(this)
            && rotations.serverRotation().angularDistanceTo(Rotation.lookingAt(client.player, point))
            <= toleranceDegrees;
    }

    protected final boolean submitAttack(int priority, LivingEntity target, double range,
                                         double cooldown, boolean requireCritical,
                                         AutoSwordCombatLoadout.Weapon weapon) {
        return submitAttack(priority, target, range, cooldown, requireCritical, weapon, client -> true);
    }

    /**
     * Aura presentation may track farther targets, but real entity attacks are
     * capped to the start-of-tick held stack's vanilla hit range (spear reach
     * when a spear is held). Beyond that cap this schedules only one ordinary
     * hand-swing animation when the previous swing has finished; it never
     * calls attackEntity.
     */
    protected final boolean submitCappedAuraAttack(
        int priority,
        LivingEntity target,
        double presentationRange,
        double cooldown,
        boolean requireCritical,
        AutoSwordCombatLoadout.Weapon weapon,
        Predicate<MinecraftClient> extraValidation
    ) {
        return submitCappedAuraAttack(priority, target, presentationRange, cooldown,
            requireCritical, weapon, extraValidation, () -> { });
    }

    /** Runs {@code afterAttack} only after this feature actually swings. */
    protected final boolean submitCappedAuraAttack(
        int priority,
        LivingEntity target,
        double presentationRange,
        double cooldown,
        boolean requireCritical,
        AutoSwordCombatLoadout.Weapon weapon,
        Predicate<MinecraftClient> extraValidation,
        Runnable afterAttack
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || target == null) {
            return false;
        }
        double hitCap = AutoSwordVanillaCombatReach.heldHitRange(client);
        double hitboxDistanceSquared = target.getBoundingBox().squaredMagnitude(client.player.getEyePos());
        if (hitboxDistanceSquared <= hitCap * hitCap + RANGE_EPSILON) {
            return submitAttack(priority, target, hitCap,
                cooldown, requireCritical, weapon, extraValidation, afterAttack);
        }
        if (!AutoSwordTargeting.validLivingTarget(client, target, friends, presentationRange, true)
            || AutoSwordCombatLoadout.attackSlot(client, target, weapon) < 0
            || !AutoSwordTargeting.attackReady(client, cooldown)
            || (requireCritical && !AutoSwordTargeting.inCriticalWindow(client.player))
            || client.player.handSwinging
            || !extraValidation.test(client)) {
            return false;
        }

        return ActionScheduler.getInstance().submit(this, priority, actionClient -> {
            if (!AutoSwordTargeting.validLivingTarget(actionClient, target, friends, presentationRange, true)
                || target.getBoundingBox().squaredMagnitude(actionClient.player.getEyePos())
                    <= AutoSwordVanillaCombatReach.heldHitRange(actionClient) * AutoSwordVanillaCombatReach.heldHitRange(actionClient)
                        + RANGE_EPSILON
                || !AutoSwordTargeting.attackReady(actionClient, cooldown)
                || (requireCritical && !AutoSwordTargeting.inCriticalWindow(actionClient.player))
                || AutoSwordCombatLoadout.attackSlot(actionClient, target, weapon) < 0
                || !rotationReady(target, 7.5D)
                || !extraValidation.test(actionClient)
                || actionClient.player.handSwinging) {
                return;
            }
            actionClient.player.swingHand(Hand.MAIN_HAND);
            afterAttack.run();
        });
    }

    protected final boolean submitAttack(int priority, LivingEntity target, double range,
                                         double cooldown, boolean requireCritical,
                                         AutoSwordCombatLoadout.Weapon weapon,
                                         Predicate<MinecraftClient> extraValidation) {
        return submitAttack(priority, target, range, cooldown, requireCritical, weapon,
            extraValidation, () -> { });
    }

    /** Runs {@code afterAttack} only once the vanilla entity attack has run. */
    protected final boolean submitAttack(int priority, LivingEntity target, double range,
                                         double cooldown, boolean requireCritical,
                                         AutoSwordCombatLoadout.Weapon weapon,
                                         Predicate<MinecraftClient> extraValidation,
                                         Runnable afterAttack) {
        MinecraftClient client = MinecraftClient.getInstance();
        int initialSlot = AutoSwordCombatLoadout.attackSlot(client, target, weapon);
        if (!AutoSwordTargeting.validLivingTarget(client, target, friends, range, true)
            || initialSlot < 0
            || !AutoSwordVanillaCombatReach.canAttack(client, target, initialSlot, range, true)
            || !AutoSwordTargeting.attackReady(client, cooldown)
            || (requireCritical && !AutoSwordTargeting.inCriticalWindow(client.player))
            || !extraValidation.test(client)) {
            return false;
        }

        return ActionScheduler.getInstance().submit(this, priority, actionClient -> {
            if (!AutoSwordTargeting.validLivingTarget(actionClient, target, friends, range, true)
                || !AutoSwordTargeting.attackReady(actionClient, cooldown)
                || (requireCritical && !AutoSwordTargeting.inCriticalWindow(actionClient.player))
                || !rotationReady(target, 7.5D)
                || !extraValidation.test(actionClient)) {
                return;
            }

            int slot = AutoSwordCombatLoadout.attackSlot(actionClient, target, weapon);
            if (slot < 0
                || !AutoSwordVanillaCombatReach.canAttack(actionClient, target, slot, range, true)) {
                return;
            }
            boolean swapped = slot != actionClient.player.getInventory().getSelectedSlot();
            boolean silent = AutoSwordCombatLoadout.silentSwap(actionClient, target, weapon);
            AutoSwordCombatLoadout.runWithSlot(this, actionClient, slot, priority, silent, () -> {
                if (!AutoSwordTargeting.validLivingTarget(actionClient, target, friends, range, true)) {
                    return;
                }
                actionClient.interactionManager.attackEntity(actionClient.player, target);
                actionClient.player.swingHand(Hand.MAIN_HAND);
                AutoSwordCombatLoadout.markSwap(actionClient, swapped);
                afterAttack.run();
            });
        });
    }

    protected final boolean validPlayer(MinecraftClient client, PlayerEntity player, double range) {
        return AutoSwordTargeting.validPlayerTarget(client, player, friends, range, true);
    }
}
