package dev.c4lam.celeste.beta.lifecycle;

import dev.c4lam.celeste.compat.GameplayCallbacks;
import dev.celestebeta.host.GameplayCallbackBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.util.Hand;

public final class BetaGameplayCallbacks {
    private BetaGameplayCallbacks() { }
    public static GameplayCallbackBridge.Registration connect(MinecraftClient client) {
        if (!client.isOnThread()) throw new IllegalStateException("Gameplay callbacks require the client thread");
        GameplayCallbackBridge.assertVisibleFrom(client.getClass().getClassLoader());
        GameplayCallbackBridge.assertVisibleFrom(BetaGameplayCallbacks.class.getClassLoader());
        GameplayCallbackBridge.preflight(MinecraftClient.class);
        return GameplayCallbackBridge.install(new GameplayCallbackBridge.Handler() {
            public void serverUpdate(int kind, Object packet) {
                switch (kind) {
                    case 0 -> GameplayCallbacks.time((WorldTimeUpdateS2CPacket) packet);
                    case 1 -> GameplayCallbacks.block((BlockUpdateS2CPacket) packet);
                    case 2 -> GameplayCallbacks.blocks((ChunkDeltaUpdateS2CPacket) packet);
                    case 3 -> GameplayCallbacks.spawn((EntitySpawnS2CPacket) packet);
                    case 4 -> GameplayCallbacks.destroy((EntitiesDestroyS2CPacket) packet);
                    default -> throw new IllegalArgumentException("Unknown server update callback");
                }
            }
            public boolean attack(Object value) { return GameplayCallbacks.attack((MinecraftClient) value); }
            public Object mainHand(Object vanilla, Object hand) { return GameplayCallbacks.mainHand((ItemStack) vanilla, (Hand) hand); }
            public void entityAttack(Object target) { GameplayCallbacks.entityAttack((Entity) target); }
        });
    }
}
