package dev.c4lam.celeste.compat;

import dev.c4lam.celeste.module.delivery.ModuleHooks;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.fog.FogData;
import net.minecraft.client.world.ClientWorld;
import org.joml.Vector4f;

/** Permanent fog/lightmap API. Only primitive result data crosses the module boundary. */
public final class AtmosphereHooks {
    private AtmosphereHooks() { }
    public record FogDistances(float environmentalStart, float environmentalEnd) { }
    public static boolean refreshLightmap() { return (boolean) ModuleHooks.invoke("fullbright", "light.refresh", false); }
    public static float nightVision(float vanilla) { return (float) ModuleHooks.invoke("fullbright", "light.night_vision", vanilla, vanilla); }
    public static float gamma(float vanilla) { return (float) ModuleHooks.invoke("fullbright", "light.gamma", vanilla, vanilla); }
    public static void sharedFog(boolean enabled, Camera camera, ClientWorld world, FogData data, Vector4f color) {
        if (enabled) ModuleHooks.invoke("custom_fog", "fog.shared", null, enabled, camera, world, data, color);
    }
    public static boolean vanillaFog(boolean enabled, Camera camera, ClientWorld world, Vector4f color, float[] distances) {
        if (!enabled) return false;
        FogDistances value = vanillaFog(enabled, camera, world, color, distances[0], distances[1],
            distances[2], distances[3], distances[4], distances[5]);
        if (value == null) return false;
        distances[0] = value.environmentalStart(); distances[1] = value.environmentalEnd();
        return true;
    }
    public static FogDistances vanillaFog(boolean enabled, Camera camera, ClientWorld world, Vector4f color,
            float start, float end, float renderStart, float renderEnd, float skyEnd, float cloudEnd) {
        return enabled ? (FogDistances) ModuleHooks.invoke("custom_fog", "fog.vanilla", null,
            enabled, camera, world, color, start, end, renderStart, renderEnd, skyEnd, cloudEnd) : null;
    }
}
