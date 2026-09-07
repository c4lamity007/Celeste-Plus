package dev.c4lam.celeste.compat;

import dev.c4lam.celeste.hud.ServerTickRateTracker;
import dev.c4lam.celeste.module.expansion.sword.SwordAttackCoordinator;
import dev.c4lam.celeste.module.impl.combat.AuraServerState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.util.Hand;

/** Original startup callback logic shared with the late method hooks. */
public final class GameplayCallbacks {
    private GameplayCallbacks() { }

    public static void time(WorldTimeUpdateS2CPacket packet) {
        ServerTickRateTracker.getInstance().onTimeUpdate(MinecraftClient.getInstance().world, packet.time());
    }
    public static void block(BlockUpdateS2CPacket packet) {
        AuraServerState.observeBlock(MinecraftClient.getInstance().world, packet.getPos(), packet.getState());
    }
    public static void blocks(ChunkDeltaUpdateS2CPacket packet) {
        packet.visitUpdates((pos, state) -> AuraServerState.observeBlock(MinecraftClient.getInstance().world, pos, state));
    }
    public static void spawn(EntitySpawnS2CPacket packet) {
        AuraServerState.observeEntity(MinecraftClient.getInstance().world, packet.getEntityId(), true);
    }
    public static void destroy(EntitiesDestroyS2CPacket packet) {
        for (int id : packet.getEntityIds()) AuraServerState.observeEntity(MinecraftClient.getInstance().world, id, false);
    }
    public static boolean attack(MinecraftClient client) {
        SwordAttackCoordinator.AttackScope scope = SwordAttackCoordinator.beginVanillaAttack(client);
        if (!scope.proceed()) return false;
        try {
            SwordAttackCoordinator.prepareVanillaClick(client, scope);
            if (scope.replaceVanillaClick()) return SwordAttackCoordinator.performSwappedMelee(client, scope);
            return MinecraftAccess.attack(client);
        } finally { SwordAttackCoordinator.finishVanillaAttack(client, scope); }
    }
    public static ItemStack mainHand(ItemStack vanilla, Hand hand) {
        return hand != Hand.MAIN_HAND ? vanilla : SwordAttackCoordinator.mainHandForVanillaClick(vanilla);
    }
    public static void entityAttack(Entity target) {
        SwordAttackCoordinator.onVanillaEntityAttack(MinecraftClient.getInstance(), target);
    }
}
