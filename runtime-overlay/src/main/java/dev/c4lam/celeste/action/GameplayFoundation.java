package dev.c4lam.celeste.action;

import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;

import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;

public final class GameplayFoundation {
    private static boolean initialized;

    private GameplayFoundation() {
    }

    public static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        BetaSubscriptions.register(ClientPlayConnectionEvents.DISCONNECT, "celeste:gameplay/disconnect",
            ClientPlayConnectionEvents.Disconnect.class, (handler, client) -> clear(), ClientPlayConnectionEvents.DISCONNECT::register);
    }

    /** Called before module START hooks. */
    public static void beginTick(MinecraftClient client) {
        RotationManager.getInstance().prepare(client);
        ActionScheduler.getInstance().beginTick(client);
        SilentSlotManager.getInstance().beginTick(client);
    }

    /** Called after module START hooks, before vanilla constructs movement packets. */
    public static void resolveRotations(MinecraftClient client) {
        RotationManager.getInstance().tick(client);
    }

    /** Runs the one winning vanilla interaction during START_CLIENT_TICK. */
    public static void executeActions(MinecraftClient client) {
        ActionScheduler.getInstance().flush(client);
    }

    /** Called after the vanilla client tick for lease observation/cleanup. */
    public static void afterTick(MinecraftClient client) {
        SilentSlotManager.getInstance().endTick(client);
    }

    public static void releaseOwner(Object owner) {
        ActionScheduler.getInstance().cancel(owner);
        SilentSlotManager.getInstance().release(owner);
        RotationManager.getInstance().release(owner);
    }

    public static void clear() {
        ActionScheduler.getInstance().clear();
        UseInputLease.getInstance().clear(MinecraftClient.getInstance());
        SilentSlotManager.getInstance().clear();
        RotationManager.getInstance().clear();
    }

    public static void close(MinecraftClient client) {
        if (client.isOnThread()) {
            clear();
        }
    }
}
