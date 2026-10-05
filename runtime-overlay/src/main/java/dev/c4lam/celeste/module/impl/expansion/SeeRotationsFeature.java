package dev.c4lam.celeste.module.impl.expansion;

import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.rotation.RotationManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;

public final class SeeRotationsFeature implements ModuleFeature {
    private static volatile boolean auraEnabled;
    private static volatile boolean seeRotations;

    @Override
    public String name() {
        return "See Rotations";
    }

    public static void syncFromAura(boolean enabled, boolean show) {
        auraEnabled = enabled;
        seeRotations = show;
    }

    public static boolean shouldApply(MinecraftClient client) {
        return auraEnabled
                && seeRotations
                && client.player != null
                && RotationManager.getInstance().isActive()
                && client.options.getPerspective().ordinal() != 0;
    }

    public static float getRenderYaw(LivingEntity entity) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (entity == client.player && shouldApply(client)) {
            return RotationManager.getInstance().serverYawOr(entity.getYaw());
        }
        return entity.getYaw();
    }

    public static float getRenderPitch(LivingEntity entity) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (entity == client.player && shouldApply(client)) {
            return RotationManager.getInstance().serverPitchOr(entity.getPitch());
        }
        return entity.getPitch();
    }

    public static boolean shouldQuantizeSensitivity() {
        MinecraftClient client = MinecraftClient.getInstance();
        return !shouldApply(client);
    }
}
