package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.rotation.RotationManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Comparator;

final class Targeting {
    private static final double DEFAULT_HALF_FOV = 60.0D;

    private Targeting() {
    }

    static PlayerEntity selectPlayer(MinecraftClient client, FriendManager friends, double range, String mode) {
        return selectPlayer(client, friends, range, DEFAULT_HALF_FOV, mode);
    }

    static PlayerEntity selectPlayer(MinecraftClient client, FriendManager friends, double range,
                                     double halfFovDegrees, String mode) {
        if (client.player == null || client.world == null) {
            return null;
        }
        Comparator<PlayerEntity> comparator = comparator(client.player, mode);
        return client.world.getPlayers().stream()
            .map(PlayerEntity.class::cast)
            .filter(player -> validPlayerTarget(client, player, friends, range, true))
            .filter(player -> inFov(client.player, player, halfFovDegrees))
            .min(comparator)
            .orElse(null);
    }

    static boolean validPlayerTarget(MinecraftClient client, PlayerEntity target, FriendManager friends,
                                     double range, boolean lineOfSight) {
        if (target == null || client.player == null || client.world == null
            || client.currentScreen != null || !client.player.isAlive()
            || client.player.hasVehicle() || client.player.isUsingItem()
            || client.interactionManager == null || client.interactionManager.isBreakingBlock()
            || target == client.player || target.getEntityWorld() != client.world
            || target.isRemoved() || !target.isAlive() || target.isSpectator()
            || friends.isFriend(target)) {
            return false;
        }
        return target.getBoundingBox().squaredMagnitude(client.player.getEyePos()) <= range * range
            && (!lineOfSight || hasLineOfSight(client, target));
    }

    static boolean validLivingTarget(MinecraftClient client, LivingEntity target, FriendManager friends,
                                     double range, boolean lineOfSight) {
        if (target == null || client.player == null || client.world == null
            || client.currentScreen != null || !client.player.isAlive()
            || client.player.hasVehicle() || client.player.isUsingItem()
            || client.interactionManager == null || client.interactionManager.isBreakingBlock()
            || target == client.player || target.getEntityWorld() != client.world
            || target.isRemoved() || !target.isAlive()) {
            return false;
        }
        if (target instanceof PlayerEntity player
            && (player.isSpectator() || friends.isFriend(player))) {
            return false;
        }
        return target.getBoundingBox().squaredMagnitude(client.player.getEyePos()) <= range * range
            && (!lineOfSight || hasLineOfSight(client, target));
    }

    static boolean attackReady(MinecraftClient client, double threshold) {
        return client.player != null
            && client.player.getAttackCooldownProgress(0.0F) + 1.0E-4F
            >= MathHelper.clamp((float) threshold, 0.0F, 1.0F);
    }

    static boolean inCriticalWindow(ClientPlayerEntity player) {
        return player != null
            && player.fallDistance > 0.0F
            && !player.isOnGround()
            && !player.isClimbing()
            && !player.isTouchingWater()
            && !player.hasVehicle()
            && !player.isSprinting()
            && !player.isGliding()
            && !player.hasStatusEffect(StatusEffects.BLINDNESS);
    }

    static boolean inMaceSmashWindow(ClientPlayerEntity player) {
        return player != null
            && player.fallDistance > 1.5F
            && !player.isGliding();
    }

    static boolean inFov(ClientPlayerEntity observer, Entity target, double halfFovDegrees) {
        Rotation camera = new Rotation(observer.getYaw(), observer.getPitch());
        Rotation desired = Rotation.lookingAt(observer, aimPoint(target));
        return camera.angularDistanceTo(desired) <= halfFovDegrees;
    }

    static boolean hasLineOfSight(MinecraftClient client, Entity target) {
        if (client.player == null || client.world == null) {
            return false;
        }
        Vec3d origin = client.player.getEyePos();
        Vec3d destination = aimPoint(target);
        HitResult result = client.world.raycast(new RaycastContext(
            origin,
            destination,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            client.player
        ));
        return result.getType() == HitResult.Type.MISS
            || result.getPos().squaredDistanceTo(destination) < 0.04D;
    }

    static boolean hasLineOfSight(MinecraftClient client, Vec3d origin, Vec3d destination) {
        if (client.player == null || client.world == null) {
            return false;
        }
        HitResult result = client.world.raycast(new RaycastContext(
            origin,
            destination,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            client.player
        ));
        return result.getType() == HitResult.Type.MISS
            || result.getPos().squaredDistanceTo(destination) < 0.04D;
    }

    static Vec3d aimPoint(Entity entity) {
        return entity.getBoundingBox().getCenter();
    }

    static boolean validBlockInteraction(MinecraftClient client, BlockHitResult expected,
                                         double configuredLimit) {
        if (client.player == null || client.world == null || expected == null
            || configuredLimit <= 0.0D
            || !client.player.canInteractWithBlockAt(expected.getBlockPos(), 0.0D)) {
            return false;
        }
        double cap = Math.min(configuredLimit, client.player.getBlockInteractionRange());
        Vec3d eyes = client.player.getEyePos();
        if (eyes.squaredDistanceTo(expected.getPos()) > cap * cap + 1.0E-7D) {
            return false;
        }
        Vec3d end = eyes.add(RotationManager.getInstance().serverRotation().direction().multiply(cap));
        HitResult result = client.world.raycast(new RaycastContext(
            eyes, end, RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE, client.player
        ));
        return result instanceof BlockHitResult hit
            && hit.getType() == HitResult.Type.BLOCK
            && hit.getBlockPos().equals(expected.getBlockPos())
            && hit.getSide() == expected.getSide()
            && hit.getPos().squaredDistanceTo(expected.getPos()) <= 0.04D;
    }

    private static Comparator<PlayerEntity> comparator(ClientPlayerEntity observer, String mode) {
        if ("Health".equalsIgnoreCase(mode)) {
            return Comparator.comparingDouble(player -> player.getHealth() + player.getAbsorptionAmount());
        }
        if ("Angle".equalsIgnoreCase(mode)) {
            Rotation camera = new Rotation(observer.getYaw(), observer.getPitch());
            return Comparator.comparingDouble(player -> camera.angularDistanceTo(
                Rotation.lookingAt(observer, aimPoint(player))));
        }
        return Comparator.comparingDouble(observer::squaredDistanceTo);
    }
}
