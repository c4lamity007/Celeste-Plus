package dev.c4lam.celeste.module.impl.expansion;

import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.rotation.RotationManager;
import net.minecraft.client.MinecraftClient;

import java.util.function.Supplier;

/**
 * Micro-scoped local rotation used only while vanilla predicts an item raycast.
 * The camera values are restored synchronously before rendering; the packet and
 * the local item implementation consequently evaluate the same look direction.
 */
public final class LocalRotationProjection {
    private LocalRotationProjection() {
    }

    public static <T> T run(MinecraftClient client, Object owner, Supplier<T> action) {
        RotationManager manager = RotationManager.getInstance();
        if (client.player == null || !manager.isOwnedBy(owner)) {
            return null;
        }

        float cameraYaw = client.player.getYaw();
        float cameraPitch = client.player.getPitch();
        Rotation server = manager.serverRotation();
        client.player.setYaw(server.yaw());
        client.player.setPitch(server.pitch());
        try {
            return action.get();
        } finally {
            client.player.setYaw(cameraYaw);
            client.player.setPitch(cameraPitch);
        }
    }
}
