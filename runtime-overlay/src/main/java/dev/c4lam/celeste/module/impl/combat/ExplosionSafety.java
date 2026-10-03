package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.friend.FriendManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

final class ExplosionSafety {
    private static final double HARD_DIFFICULTY_MULTIPLIER = 1.5D;
    private static final double SELF_DAMAGE_MARGIN = 2.0D;

    private ExplosionSafety() {
    }

    static PlayerEntity bestEnemy(MinecraftClient client, FriendManager friends, Vec3d explosion,
                                  double radius) {
        if (client.player == null || client.world == null) {
            return null;
        }
        return client.world.getPlayers().stream()
            .map(PlayerEntity.class::cast)
            .filter(player -> player != client.player && player.isAlive() && !player.isRemoved())
            .filter(player -> !player.isSpectator() && !player.isCreative() && !friends.isFriend(player))
            .filter(player -> player.squaredDistanceTo(explosion) <= radius * radius)
            .filter(player -> Targeting.hasLineOfSight(client, explosion, Targeting.aimPoint(player)))
            .min(java.util.Comparator.comparingDouble(player -> player.squaredDistanceTo(explosion)))
            .orElse(null);
    }

    /** Approximate target-damage ranking; the server remains authoritative. */
    static double damage(PlayerEntity player, Vec3d explosion, double power, boolean applyArmor) {
        double radius = power * 2.0D;
        double distance = Math.sqrt(player.squaredDistanceTo(explosion));
        if (distance >= radius) {
            return 0.0D;
        }
        double impact = 1.0D - distance / radius;
        double raw = ((impact * impact + impact) * 0.5D * 7.0D * radius) + 1.0D;
        if (!applyArmor) {
            return raw;
        }
        double armorReduction = Math.min(20.0D, Math.max(0.0D, player.getArmor())) / 25.0D;
        return raw * (1.0D - armorReduction);
    }

    static boolean wouldSurvive(PlayerEntity player, Vec3d explosion, double power) {
        // Anti-suicide is intentionally much stricter than target-damage ranking:
        // assume full exposure, hard-difficulty scaling, and no mitigation at all.
        // This avoids granting unsafe credit for armor, toughness, enchantments,
        // resistance, or client/server differences in exposure raycasts.
        double health = player.getHealth() + player.getAbsorptionAmount();
        double worstCase = damage(player, explosion, power, false) * HARD_DIFFICULTY_MULTIPLIER;
        return worstCase + SELF_DAMAGE_MARGIN < health;
    }
}
