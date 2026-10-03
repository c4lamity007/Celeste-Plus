package dev.c4lam.celeste.compat;

import dev.c4lam.celeste.hud.ServerTickRateTracker;
import dev.c4lam.celeste.action.SwordAttackCoordinator;
import dev.c4lam.celeste.module.delivery.ModuleHooks;
import dev.c4lam.celeste.action.ServerWorldFacts;
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
        ServerWorldFacts.observeBlock(MinecraftClient.getInstance().world, packet.getPos(), packet.getState());
        changed(packet.getPos());
    }
    public static void blocks(ChunkDeltaUpdateS2CPacket packet) {
        packet.visitUpdates((pos, state) -> {
            ServerWorldFacts.observeBlock(MinecraftClient.getInstance().world, pos, state);
            changed(pos);
        });
    }
    private static void changed(net.minecraft.util.math.BlockPos pos) {
        var world = MinecraftClient.getInstance().world;
        ModuleHooks.invoke("block_esp", "world.block_changed", null, world, pos);
        ModuleHooks.invoke("storage_esp", "world.block_changed", null, world, pos);
        ModuleHooks.invoke("suspicious_chunk_finder", "world.block_changed", null, world, pos);
    }
    public static void spawn(EntitySpawnS2CPacket packet) {
        ServerWorldFacts.observeEntity(MinecraftClient.getInstance().world, packet.getEntityId(), true);
    }
    public static void destroy(EntitiesDestroyS2CPacket packet) {
        for (int id : packet.getEntityIds()) ServerWorldFacts.observeEntity(MinecraftClient.getInstance().world, id, false);
    }
    public static void entityStatus(EntityStatusS2CPacket packet) {
        TotemPopTracker.observe(packet);
    }
    public static void useItem(MinecraftClient client) {
        MinecraftAccess.useItem(client);
    }
    public static void blockBreaking(MinecraftClient client, boolean breaking) {
        MinecraftAccess.handleBlockBreaking(client, breaking);
    }
    public static boolean attack(MinecraftClient client) {
        try (RealActionTarget.Scope target = RealActionTarget.begin(client)) {
            SwordAttackCoordinator.AttackScope scope = SwordAttackCoordinator.beginVanillaAttack(client);
            if (!scope.proceed()) return false;
            try {
                SwordAttackCoordinator.prepareVanillaClick(client, scope);
                if (scope.replaceVanillaClick()) return SwordAttackCoordinator.performSwappedMelee(client, scope);
                return MinecraftAccess.attack(client);
            } finally { SwordAttackCoordinator.finishVanillaAttack(client, scope); }
        }
    }
    public static ItemStack mainHand(ItemStack vanilla, Hand hand) {
        return hand != Hand.MAIN_HAND ? vanilla : SwordAttackCoordinator.mainHandForVanillaClick(vanilla);
    }
    public static void entityAttack(Entity target) {
        SwordAttackCoordinator.onVanillaEntityAttack(MinecraftClient.getInstance(), target);
        ModuleHooks.invoke("player_models", "model.attack", null, MinecraftClient.getInstance(), target);
    }
}
