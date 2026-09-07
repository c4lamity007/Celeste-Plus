package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.auth.AuthBootstrap;
import dev.c4lam.celeste.compat.RenderCompatibility;
import net.minecraft.block.enums.CameraSubmersionType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.world.World;
import org.joml.Vector4f;

/** Adapts only this world's native atmospheric UBO upload; owns no GPU objects or overrides. */
public final class CustomFogRenderer {
    private CustomFogRenderer() { }

    public static CustomFogMath.Fog adjust(Camera camera, ClientWorld world, Vector4f color,
                                          float environmentalStart, float environmentalEnd,
                                          float renderDistanceStart, float renderDistanceEnd,
                                          float skyEnd, float cloudEnd) {
        if (camera == null || world == null || color == null || RenderCompatibility.isShadowPass()) return null;
        CustomFogMath.Settings settings = CustomFogFeature.settings(world);
        MinecraftClient client = MinecraftClient.getInstance();
        if (settings == null || client.world != world || client.player == null || !AuthBootstrap.authorized()) return null;
        Entity focused = camera.getFocusedEntity();
        if (focused == null || focused.getEntityWorld() != world) return null;
        LivingEntity living = focused instanceof LivingEntity value ? value : null;
        CameraSubmersionType submersion = camera.getSubmersionType();
        CustomFogMath.Medium medium = switch (submersion) {
            case NONE, ATMOSPHERIC -> CustomFogMath.Medium.AIR;
            case WATER -> CustomFogMath.Medium.WATER;
            case LAVA -> CustomFogMath.Medium.LAVA;
            case POWDER_SNOW -> CustomFogMath.Medium.POWDER_SNOW;
        };
        float voidRange = world.getLevelProperties().getVoidDarknessRange();
        CustomFogMath.Conditions conditions = new CustomFogMath.Conditions(true,
            world.getRegistryKey().equals(World.OVERWORLD), medium,
            living != null && living.hasStatusEffect(StatusEffects.BLINDNESS),
            living != null && living.hasStatusEffect(StatusEffects.DARKNESS),
            client.inGameHud.getBossBarHud().shouldThickenFog(),
            voidRange > 0 && camera.getCameraPos().y < world.getBottomY() + voidRange);
        if (!conditions.allowed()) return null;
        CustomFogMath.Fog vanilla = new CustomFogMath.Fog(new CustomFogMath.Color(color.x, color.y, color.z, color.w),
            environmentalStart, environmentalEnd, renderDistanceStart, renderDistanceEnd, skyEnd, cloudEnd);
        CustomFogMath.Fog adjusted = CustomFogMath.apply(vanilla, settings, conditions);
        return adjusted == vanilla ? null : adjusted;
    }
}
