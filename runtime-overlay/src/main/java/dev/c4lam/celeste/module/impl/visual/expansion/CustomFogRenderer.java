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
        if (color == null || !allowed(camera, world)) return null;
        CustomFogMath.Settings settings = CustomFogFeature.settings(world);
        if (settings == null) return null;
        CustomFogMath.Fog vanilla = new CustomFogMath.Fog(new CustomFogMath.Color(color.x, color.y, color.z, color.w),
            environmentalStart, environmentalEnd, renderDistanceStart, renderDistanceEnd, skyEnd, cloudEnd);
        if (settings.shader()) {
            // The GPU integrates the local haze. Only coordinate the native far-edge/sky
            // horizon color here; shortening the native distances would fog the scene twice.
            if (RenderCompatibility.isShaderPackActive() || ShaderFogMath.density(settings.density()) == 0
                || environmentalEnd <= 96 || environmentalStart < 0) return null;
            int tint = ShaderFogMath.horizon(ShaderFogMath.mode(settings.shaderPreset(), (int) dev.c4lam.celeste.module.delivery.ModuleHooks.invoke("custom_sky", "sky.fog_mode", 0)));
            double weight = ShaderFogMath.density(settings.density()) * .84;
            CustomFogMath.Color tinted = new CustomFogMath.Color(
                color.x + (((tint >> 16) & 255) / 255.0 - color.x) * weight,
                color.y + (((tint >> 8) & 255) / 255.0 - color.y) * weight,
                color.z + ((tint & 255) / 255.0 - color.z) * weight, color.w);
            return new CustomFogMath.Fog(tinted, environmentalStart, environmentalEnd,
                renderDistanceStart, renderDistanceEnd, skyEnd, cloudEnd);
        }
        CustomFogMath.Fog adjusted = CustomFogMath.apply(vanilla, settings,
            new CustomFogMath.Conditions(true, true, CustomFogMath.Medium.AIR, false, false, false, false));
        return adjusted == vanilla ? null : adjusted;
    }

    /** Same hazard/medium contract for the native adapter and the late shader pass. */
    static boolean allowed(Camera camera, ClientWorld world) {
        if (camera == null || world == null || !camera.isReady() || RenderCompatibility.isShadowPass()) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != world || client.player == null || !AuthBootstrap.authorized()) return false;
        Entity focused = camera.getFocusedEntity();
        if (focused == null || focused.getEntityWorld() != world) return false;
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
        return conditions.allowed();
    }
}
