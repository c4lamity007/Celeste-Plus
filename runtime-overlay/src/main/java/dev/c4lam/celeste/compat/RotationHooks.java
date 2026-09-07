package dev.c4lam.celeste.compat;

import dev.c4lam.celeste.module.expansion.inventory.SilentInventoryScreen;
import dev.c4lam.celeste.rotation.MoveFix;
import dev.c4lam.celeste.rotation.MoveFixMode;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

/** Shared startup/late behavior; retains the original redirects' evaluation and guard ordering. */
public final class RotationHooks {
    private RotationHooks() { }

    public static float outgoingYaw(ClientPlayerEntity player) {
        return RotationManager.getInstance().outgoingYawOr(player.getYaw());
    }
    public static float outgoingPitch(ClientPlayerEntity player) {
        return RotationManager.getInstance().serverPitchOr(player.getPitch());
    }
    public static float itemYaw(PlayerEntity player) {
        if (MinecraftClient.getInstance().player != player) return player.getYaw();
        return RotationManager.getInstance().outgoingYawOr(player.getYaw());
    }
    public static float itemPitch(PlayerEntity player) {
        if (MinecraftClient.getInstance().player != player) return player.getPitch();
        return RotationManager.getInstance().serverPitchOr(player.getPitch());
    }
    public static float movementYaw(Entity entity) {
        MinecraftClient client = MinecraftClient.getInstance();
        RotationManager rotations = RotationManager.getInstance();
        MoveFixMode mode = rotations.moveFixMode();
        float cameraYaw = entity.getYaw();
        if (client.player != entity || !rotations.isActive() || mode == MoveFixMode.OFF) return cameraYaw;
        return MoveFix.movementYaw(cameraYaw, rotations.serverRotation().yaw(), mode);
    }
    public static Vec3d glidingVector(LivingEntity entity) {
        RotationManager rotations = RotationManager.getInstance();
        if (MinecraftClient.getInstance().player != entity || !rotations.isActive() || rotations.moveFixMode() == MoveFixMode.OFF) {
            return entity.getRotationVector();
        }
        return rotations.serverRotation().direction();
    }
    public static float glidingPitch(LivingEntity entity) {
        RotationManager rotations = RotationManager.getInstance();
        if (MinecraftClient.getInstance().player != entity || !rotations.isActive() || rotations.moveFixMode() == MoveFixMode.OFF) {
            return entity.getPitch();
        }
        return rotations.serverRotation().pitch();
    }
    public static float sprintJumpYaw(LivingEntity entity) {
        MinecraftClient client = MinecraftClient.getInstance();
        RotationManager rotations = RotationManager.getInstance();
        MoveFixMode mode = rotations.moveFixMode();
        float cameraYaw = entity.getYaw();
        if (client.player != entity || !rotations.isActive() || mode == MoveFixMode.OFF) return cameraYaw;
        return MoveFix.sprintJumpYaw(cameraYaw, rotations.serverRotation().yaw(), mode);
    }

    /** Null leaves both input fields unchanged. Inventory blocking takes priority over all movement guards. */
    public static MoveFix.Correction keyboardCorrection(Input input, Vec2f movement, PlayerInput keys) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (SilentInventoryScreen.blocksMovement(client)) return new MoveFix.Correction(Vec2f.ZERO, PlayerInput.DEFAULT);
        if (client.player == null || client.player.input != input || client.player.hasVehicle()) return null;
        RotationManager rotations = RotationManager.getInstance();
        MoveFixMode mode = rotations.moveFixMode();
        if (!rotations.isActive() || mode == MoveFixMode.OFF) return null;
        return MoveFix.correct(movement, keys, client.player.getYaw(), rotations.serverRotation().yaw(), mode);
    }

    /** Null continues vanilla; the one-element result preserves cancellation even for a null stack. */
    public static Object[] selectedStack(PlayerInventory inventory, Object replacement, boolean set) {
        int slot = SilentSlotManager.getInstance().localProjectionSlot(inventory);
        if (slot < 0) return null;
        var previous = inventory.getStack(slot);
        if (set) inventory.setStack(slot, (net.minecraft.item.ItemStack) replacement);
        return new Object[] {previous};
    }
}
