package dev.c4lam.celeste.module.expansion.sword;

import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.rotation.Rotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Comparator;

/** Validation shared by the clean-room sword expansion features. */
final class SwordExpansionTargets {
    private static final FriendManager FRIENDS = FriendManager.getInstance();

    private SwordExpansionTargets() {
    }

    static LivingEntity crosshairLiving(MinecraftClient client, double range) {
        if (!(client.crosshairTarget instanceof EntityHitResult hit)
            || !(hit.getEntity() instanceof LivingEntity living)
            || !validLiving(client, living, range, true)) {
            return null;
        }
        return living;
    }

    static PlayerEntity nearestPlayer(MinecraftClient client, double range, double fullFovDegrees) {
        if (client.player == null || client.world == null) {
            return null;
        }
        Rotation camera = camera(client.player);
        double halfFov = Math.max(1.0D, fullFovDegrees * 0.5D);
        return client.world.getPlayers().stream()
            .filter(player -> validPlayer(client, player, range, true))
            .filter(player -> camera.angularDistanceTo(rotationTo(client.player, player)) <= halfFov)
            .min(Comparator
                .comparingDouble((PlayerEntity player) -> camera.angularDistanceTo(rotationTo(client.player, player)))
                .thenComparingDouble(client.player::squaredDistanceTo))
            .orElse(null);
    }

    static boolean validLiving(MinecraftClient client, LivingEntity target, double range, boolean lineOfSight) {
        if (client.player == null || client.world == null || client.currentScreen != null
            || !client.player.isAlive() || target == null || target == client.player
            || target.getEntityWorld() != client.world || target.isRemoved() || !target.isAlive()) {
            return false;
        }
        if (target instanceof PlayerEntity player
            && (player.isSpectator() || player.isCreative() || FRIENDS.isFriend(player))) {
            return false;
        }
        return target.getBoundingBox().squaredMagnitude(client.player.getEyePos()) <= range * range
            && (!lineOfSight || hasLineOfSight(client, target));
    }

    static boolean validPlayer(MinecraftClient client, PlayerEntity target, double range, boolean lineOfSight) {
        return target != null && !target.isSpectator() && !target.isCreative()
            && validLiving(client, target, range, lineOfSight);
    }

    static boolean inFov(ClientPlayerEntity observer, Entity target, double fullFovDegrees) {
        return observer != null && target != null
            && camera(observer).angularDistanceTo(rotationTo(observer, target))
            <= Math.max(1.0D, fullFovDegrees * 0.5D);
    }

    static Vec3d aimPoint(Entity entity) {
        return entity.getBoundingBox().getCenter();
    }

    static Rotation rotationTo(ClientPlayerEntity observer, Entity target) {
        return Rotation.lookingAt(observer, aimPoint(target));
    }

    private static Rotation camera(ClientPlayerEntity player) {
        return new Rotation(player.getYaw(), player.getPitch());
    }

    private static boolean hasLineOfSight(MinecraftClient client, Entity target) {
        Vec3d destination = aimPoint(target);
        HitResult result = client.world.raycast(new RaycastContext(
            client.player.getEyePos(),
            destination,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            client.player
        ));
        return result.getType() == HitResult.Type.MISS
            || result.getPos().squaredDistanceTo(destination) < 0.04D;
    }
}
