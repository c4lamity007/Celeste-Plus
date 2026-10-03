package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.rotation.RotationTask;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;

/**
 * Knockback Displace feature ported from skidding client.
 */
public final class KnockbackDisplaceFeature implements ModuleFeature {
    private static final String LEFT = "Left";
    private static final String RIGHT = "Right";
    private static final String BACK = "Back";
    private static final String SPACE = " ";

    private static final int ATTACK_TIMEOUT_TICKS = 1;

    private boolean hadSilentRotation;
    private boolean waitingForSpoofedMovement;
    private boolean readyToReplayAttack;
    private boolean releasingSpoofRotation;
    private boolean replayingAttack;

    private int pendingTargetId = -1;
    private int timeoutTicks;

    private float queuedYaw;
    private float queuedPitch;
    private float previousServerYaw;
    private float previousServerPitch;

    private MinecraftClient client;
    private ModuleContext context;

    // Static instance for mixins to access
    private static KnockbackDisplaceFeature INSTANCE;

    public KnockbackDisplaceFeature() {
        INSTANCE = this;
    }

    public static KnockbackDisplaceFeature getInstance() {
        return INSTANCE;
    }

    @Override
    public String name() {
        return "Knockback Displace";
    }

    public String concat() {
        if (contextMode("Direction", BACK).equals(BACK)) {
            return BACK;
        }

        String offset = Math.round(contextSlider("Yaw Offset", 90.0f)) + "";
        String direction = contextMode("Direction", BACK);
        if (direction.equals(LEFT)) {
            return LEFT + SPACE + offset;
        }

        return RIGHT + SPACE + offset;
    }

    @Override
    public void onEnable(ModuleContext context) {
        // Noop
    }

    @Override
    public void onDisable(ModuleContext context) {
        resetState();
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        resetState();
    }

    @Override
    public void onStartTick(ModuleContext context) {
        this.client = context.client();
        this.context = context;
    }

    @Override
    public void onActionTick(ModuleContext context) {
        // Noop
    }

    // Called from mixin when attack starts
    public void onStartAttack(PlayerEntity player, LivingEntity target) {
        if (!shouldStageAttack(player, this.context)) {
            return;
        }

        queuedYaw = getQueuedYaw(player.getYaw(), this.context);
        queuedPitch = player.getPitch();
        pendingTargetId = target.getId();
        timeoutTicks = ATTACK_TIMEOUT_TICKS;
        hadSilentRotation = RotationManager.getInstance().isOwnedBy(this);
        previousServerYaw = RotationManager.getInstance().serverRotation().yaw();
        previousServerPitch = RotationManager.getInstance().serverRotation().pitch();

        // Force silent rotation
        RotationManager.getInstance().submit(RotationTask.builder(this,
                new Rotation(queuedYaw, queuedPitch))
                .priority(50)
                .lifetimeTicks(1)
                .smoothing(180.0F, 180.0F)
                .sensitivityQuantized(true)
                .moveFix(dev.c4lam.celeste.rotation.MoveFixMode.STRICT)
                .build());

        waitingForSpoofedMovement = true;
        readyToReplayAttack = false;
        replayingAttack = false;
    }

    // Called from mixin on movement pre
    public void onMovePre(float yaw, float pitch) {
        // This method is intended to be used by mixins to set the rotation
        // The mixin should set the player's rotation to queued values when waitingForSpoofedMovement is true
    }

    // Called from mixin on movement post
    public void onMovePost() {
        if (waitingForSpoofedMovement) {
            waitingForSpoofedMovement = false;
            readyToReplayAttack = true;
            return;
        }

        if (!releasingSpoofRotation) {
            return;
        }

        finishRelease();
    }

    // Called from mixin on handle input early
    public void onHandleInputEarly() {
        if (!readyToReplayAttack) {
            return;
        }

        timeoutTicks--;
        if (timeoutTicks < 0) {
            resetState();
            return;
        }

        Entity target = getPendingTarget();
        if (!canReplayAttack(target)) {
            resetState();
            return;
        }

        readyToReplayAttack = false;
        replayingAttack = true;
        // Perform the attack
        if (client.player != null && client.interactionManager != null && target != null) {
            client.interactionManager.attackEntity(client.player, target);
            client.player.swingHand(Hand.MAIN_HAND);
        }
    }

    // Called from mixin on attack entity post
    public void onAttackEntityPost(LivingEntity target) {
        if (!replayingAttack) {
            return;
        }
        if (client.player == null || target == null) {
            return;
        }
        if (target.getId() != pendingTargetId) {
            return;
        }

        if (hadSilentRotation) {
            restorePreviousSilentRotation();
            return;
        }

        beginReleaseToPlayerRotation();
    }

    private boolean shouldStageAttack(PlayerEntity player, ModuleContext context) {
        if (player == null) {
            return false;
        }
        if (waitingForSpoofedMovement) {
            return false;
        }
        if (readyToReplayAttack) {
            return false;
        }
        if (releasingSpoofRotation) {
            return false;
        }
        if (replayingAttack) {
            return false;
        }
        // Check attack cooldown (simplified)
        if (player.getAttackCooldownProgress(0.5f) <= 0.9f) {
            return false;
        }
        // Check if we have extra knockback (sprinting or knockback enchant)
        if (!hasExtraKnockback(player, context)) {
            return false;
        }
        // Check if we can replay attack (simplified)
        if (!canReplayAttack(player)) {
            return false;
        }
        return true;
    }

    private boolean hasExtraKnockback(PlayerEntity player, ModuleContext context) {
        if (player.isSprinting()) {
            return true;
        }
        // Check knockback enchantment (placeholder)
        if (context != null && context.toggle("Knockback Enchant", true)) {
            // In a real implementation, we would check for knockback enchant on the weapon
            // For now, we'll assume it's present if the setting is enabled
            return true;
        }
        return false;
    }

    private boolean canReplayAttack(Entity entity) {
        if (entity == null || client.player == null || client.interactionManager == null) {
            return false;
        }
        // Simplified: just check if target is alive and in range
        LivingEntity target = getPendingTarget();
        if (target == null) return false;
        return target.isAlive() && client.player.squaredDistanceTo(target) < 25.0;
    }

    private LivingEntity getPendingTarget() {
        if (client.world == null) {
            return null;
        }
        Entity entity = client.world.getEntityById(pendingTargetId);
        return entity instanceof LivingEntity living ? living : null;
    }

    private float getQueuedYaw(float baseYaw, ModuleContext context) {
        String direction = contextMode("Direction", BACK);
        if (direction.equals(LEFT)) {
            return MathHelper.wrapDegrees(baseYaw - contextSlider("Yaw Offset", 90.0f));
        }

        if (direction.equals(RIGHT)) {
            return MathHelper.wrapDegrees(baseYaw + contextSlider("Yaw Offset", 90.0f));
        }

        return MathHelper.wrapDegrees(baseYaw + 180.0f);
    }

    private void restorePreviousSilentRotation() {
        RotationManager.getInstance().submit(RotationTask.builder(this,
                new Rotation(previousServerYaw, previousServerPitch))
                .priority(50)
                .lifetimeTicks(1)
                .smoothing(180.0F, 180.0F)
                .sensitivityQuantized(true)
                .moveFix(dev.c4lam.celeste.rotation.MoveFixMode.STRICT)
                .build());
        clearState();
    }

    private void beginReleaseToPlayerRotation() {
        if (client.player == null) {
            // Reset rotation handler
            clearState();
            return;
        }

        RotationManager.getInstance().submit(RotationTask.builder(this,
                new Rotation(client.player.getYaw(), client.player.getPitch()))
                .priority(50)
                .lifetimeTicks(1)
                .smoothing(180.0F, 180.0F)
                .sensitivityQuantized(true)
                .moveFix(dev.c4lam.celeste.rotation.MoveFixMode.STRICT)
                .build());
        releasingSpoofRotation = true;
        waitingForSpoofedMovement = false;
        readyToReplayAttack = false;
        replayingAttack = false;
        pendingTargetId = -1;
        timeoutTicks = 0;
    }

    private void finishRelease() {
        if (client.player == null) {
            clearState();
            return;
        }
        // Stop tracking rotation
        RotationManager.getInstance().release(this);
        clearState();
    }

    private void resetState() {
        if (client.player != null) {
            if (hadSilentRotation) {
                RotationManager.getInstance().submit(RotationTask.builder(this,
                        new Rotation(previousServerYaw, previousServerPitch))
                        .priority(50)
                        .lifetimeTicks(1)
                        .smoothing(180.0F, 180.0F)
                        .sensitivityQuantized(true)
                        .moveFix(dev.c4lam.celeste.rotation.MoveFixMode.STRICT)
                        .build());
            } else {
                RotationManager.getInstance().release(this);
            }
        } else {
            // Reset rotation handler
        }
        clearState();
    }

    private void clearState() {
        hadSilentRotation = false;
        waitingForSpoofedMovement = false;
        readyToReplayAttack = false;
        releasingSpoofRotation = false;
        replayingAttack = false;
        pendingTargetId = -1;
        timeoutTicks = 0;
    }

    // Helper methods to access settings from ModuleContext
    private float contextSlider(String label, float fallback) {
        if (client == null) return fallback;
        // Settings would be accessed through the module system in a real implementation
        return fallback;
    }

    private String contextMode(String label, String fallback) {
        if (client == null) return fallback;
        // Settings would be accessed through the module system in a real implementation
        return fallback;
    }

    private boolean contextToggle(String label, boolean fallback) {
        if (client == null) return fallback;
        // Settings would be accessed through the module system in a real implementation
        return fallback;
    }
}