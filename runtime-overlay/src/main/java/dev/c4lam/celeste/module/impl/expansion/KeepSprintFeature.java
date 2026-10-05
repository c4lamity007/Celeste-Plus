package dev.c4lam.celeste.module.impl.expansion;

import dev.c4lam.celeste.action.LocalInputOwnership;
import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;

/**
 * Requests Minecraft's ordinary sprint binding on the tick after a sprint
 * attack. Vanilla still owns the restart decision and reports the same input
 * state the local movement code consumes.
 */
public final class KeepSprintFeature implements ModuleFeature {
    private static KeepSprintFeature activeFeature;

    private ClientPlayerEntity queuedPlayer;
    private long restartAtTick = Long.MAX_VALUE;
    private boolean ownsSprintKey;

    @Override
    public String name() {
        return "Keep Sprint";
    }

    @Override
    public void onEnable(ModuleContext context) {
        activeFeature = this;
    }

    @Override
    public void onStartTick(ModuleContext context) {
        MinecraftClient client = context.client();
        if (ActionScheduler.getInstance().tickNumber() < restartAtTick) {
            return;
        }

        restartAtTick = Long.MAX_VALUE;
        ClientPlayerEntity player = client.player;
        if (player == null || player != queuedPlayer || client.currentScreen != null
            || !player.isAlive() || player.isSpectator() || player.hasVehicle()
            || player.horizontalCollision || player.isSneaking() || player.input.playerInput.sneak()
            || player.isUsingItem() || player.isGliding() || player.hasBlindnessEffect()
            || player.input.getMovementInput().y <= 1.0E-4F
            || (player.getHungerManager().getFoodLevel() <= 6 && !player.getAbilities().flying)) {
            queuedPlayer = null;
            return;
        }

        queuedPlayer = null;
        ownsSprintKey = LocalInputOwnership.setSynthetic(
            client, client.options.sprintKey, true, ownsSprintKey);
    }

    @Override
    public void onEndTick(ModuleContext context) {
        releaseKey(context.client());
    }

    @Override
    public void onDisable(ModuleContext context) {
        if (activeFeature == this) {
            activeFeature = null;
        }
        reset(context.client());
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        if (activeFeature == this) {
            activeFeature = null;
        }
        reset(context.client());
    }

    public static void afterAttack(MinecraftClient client, Entity target, boolean wasSprinting) {
        KeepSprintFeature feature = activeFeature;
        if (feature == null || !wasSprinting || client == null || client.player == null
            || target == null || client.currentScreen != null) {
            return;
        }

        feature.queuedPlayer = client.player;
        feature.restartAtTick = ActionScheduler.getInstance().tickNumber() + 1L;
    }

    private void reset(MinecraftClient client) {
        queuedPlayer = null;
        restartAtTick = Long.MAX_VALUE;
        releaseKey(client);
    }

    private void releaseKey(MinecraftClient client) {
        LocalInputOwnership.release(client, client.options.sprintKey, ownsSprintKey);
        ownsSprintKey = false;
    }
}
