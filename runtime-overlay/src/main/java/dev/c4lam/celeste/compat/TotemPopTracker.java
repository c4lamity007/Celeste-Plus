package dev.c4lam.celeste.compat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;

/** Counts only the local player's actual totem-use status packets. Client thread only. */
public final class TotemPopTracker {
    private static final int TOTEM_USED_STATUS = 35;
    private static ClientWorld world;
    private static ClientPlayerEntity player;
    private static long count;

    private TotemPopTracker() { }

    public static long counter(MinecraftClient client) {
        refresh(client);
        return count;
    }

    public static void observe(EntityStatusS2CPacket packet) {
        MinecraftClient client = MinecraftClient.getInstance();
        refresh(client);
        if (client.world == null) return;
        if (isLocalTotemPop(packet.getStatus(), packet.getEntity(client.world), client.player)) count++;
    }

    static boolean isLocalTotemPop(byte status, Object observed, Object localPlayer) {
        return status == TOTEM_USED_STATUS && localPlayer != null && observed == localPlayer;
    }

    private static void refresh(MinecraftClient client) {
        if (world == client.world && player == client.player) return;
        world = client.world;
        player = client.player;
        count = 0L;
    }
}
