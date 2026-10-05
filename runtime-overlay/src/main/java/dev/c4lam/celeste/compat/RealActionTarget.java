package dev.c4lam.celeste.compat;

import dev.c4lam.celeste.module.delivery.ModuleHooks;
import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.rotation.RotationManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

/**
 * Temporarily gives vanilla clicks a hit result rooted at the real player and
 * aimed along the latest server-directed look. The visible camera target is
 * restored immediately after the vanilla input scope.
 */
public final class RealActionTarget {
    private RealActionTarget() {
    }

    public static Scope begin(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null
            || client.interactionManager == null || client.player.isSpectator()) {
            return Scope.NONE;
        }
        RotationManager rotations = RotationManager.getInstance();
        if (!rotations.isActive() && !((boolean) ModuleHooks.invoke("freecam", "freecam.detached", false, client))) {
            return Scope.NONE;
        }
        ClientPlayerEntity player = client.player;
        Rotation look = rotations.isActive()
            ? rotations.serverRotation() : new Rotation(player.getYaw(), player.getPitch());
        HitResult previousHit = client.crosshairTarget;
        Entity previousEntity = client.targetedEntity;
        if (!Float.isFinite(look.yaw()) || !Float.isFinite(look.pitch())) {
            return Scope.NONE;
        }
        float yaw = player.getYaw();
        float pitch = player.getPitch();
        float headYaw = player.getHeadYaw();
        try {
            player.setYaw(look.yaw());
            player.setPitch(look.pitch());
            player.setHeadYaw(look.yaw());
            // Delegate reach, weapon components, blockers and entity filtering
            // to the exact 1.21.11 vanilla query. Keep this look active for the
            // actual vanilla call because interaction packets also read it.
            HitResult resolved = player.getCrosshairTarget(1.0F, player);
            if (resolved == null) {
                restoreLook(player, yaw, pitch, headYaw);
                return Scope.NONE;
            }
            client.crosshairTarget = resolved;
            client.targetedEntity = resolved instanceof EntityHitResult entityHit ? entityHit.getEntity() : null;
            return new Scope(client, player, previousHit, previousEntity, yaw, pitch, headYaw);
        } catch (RuntimeException | Error failure) {
            client.crosshairTarget = previousHit;
            client.targetedEntity = previousEntity;
            restoreLook(player, yaw, pitch, headYaw);
            throw failure;
        }
    }

    private static void restoreLook(ClientPlayerEntity player, float yaw, float pitch, float headYaw) {
        if (player != null) {
            player.setYaw(yaw);
            player.setPitch(pitch);
            player.setHeadYaw(headYaw);
        }
    }

    public static final class Scope implements AutoCloseable {
        private static final Scope NONE = new Scope(null, null, null, null, 0.0F, 0.0F, 0.0F);
        private MinecraftClient client;
        private ClientPlayerEntity player;
        private final HitResult previousHit;
        private final Entity previousEntity;
        private final float previousYaw;
        private final float previousPitch;
        private final float previousHeadYaw;

        private Scope(MinecraftClient client, ClientPlayerEntity player, HitResult previousHit, Entity previousEntity,
                      float previousYaw, float previousPitch, float previousHeadYaw) {
            this.client = client;
            this.player = player;
            this.previousHit = previousHit;
            this.previousEntity = previousEntity;
            this.previousYaw = previousYaw;
            this.previousPitch = previousPitch;
            this.previousHeadYaw = previousHeadYaw;
        }

        @Override
        public void close() {
            MinecraftClient current = client;
            ClientPlayerEntity currentPlayer = player;
            client = null;
            player = null;
            if (current != null) {
                current.crosshairTarget = previousHit;
                current.targetedEntity = previousEntity;
            }
            restoreLook(currentPlayer, previousYaw, previousPitch, previousHeadYaw);
        }
    }
}
