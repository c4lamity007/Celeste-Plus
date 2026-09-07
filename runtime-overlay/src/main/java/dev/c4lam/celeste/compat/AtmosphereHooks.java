package dev.c4lam.celeste.compat;

import dev.c4lam.celeste.module.impl.visual.FullbrightFeature;
import dev.c4lam.celeste.module.impl.visual.expansion.CustomFogCompatibility;
import dev.c4lam.celeste.module.impl.visual.expansion.CustomFogMath;
import dev.c4lam.celeste.module.impl.visual.expansion.CustomFogRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.fog.FogData;
import net.minecraft.client.world.ClientWorld;
import org.joml.Vector4f;

/** Existing startup visual behavior, shared with the optional late bridge. */
public final class AtmosphereHooks {
    private AtmosphereHooks() { }
    public static boolean refreshLightmap() { return FullbrightFeature.isTransitioning(); }
    public static float nightVision(float vanilla) { return FullbrightFeature.adjustNightVisionLightmap(vanilla); }
    public static float gamma(float vanilla) { return FullbrightFeature.adjustGammaLightmap(vanilla); }

    public static void sharedFog(boolean enabled, Camera camera, ClientWorld world, FogData data, Vector4f color) {
        if (!enabled || !CustomFogCompatibility.usesSodium()) return;
        CustomFogMath.Fog adjusted = CustomFogRenderer.adjust(camera, world, color,
            data.environmentalStart, data.environmentalEnd, data.renderDistanceStart, data.renderDistanceEnd,
            data.skyEnd, data.cloudEnd);
        CustomFogCompatibility.applyShared(data, color, adjusted);
    }

    /** Mutates only the upload's environmental arguments and its original color vector. */
    public static boolean vanillaFog(boolean enabled, Camera camera, ClientWorld world, Vector4f color, float[] distances) {
        if (!enabled) return false;
        CustomFogMath.Fog fog = vanillaFog(enabled, camera, world, color,
            distances[0], distances[1], distances[2], distances[3], distances[4], distances[5]);
        if (fog == null) return false;
        distances[0] = (float) fog.environmentalStart();
        distances[1] = (float) fog.environmentalEnd();
        return true;
    }

    /** Scalar startup path avoids a six-float allocation on every world fog upload. */
    public static CustomFogMath.Fog vanillaFog(boolean enabled, Camera camera, ClientWorld world, Vector4f color,
        float start, float end, float renderStart, float renderEnd, float skyEnd, float cloudEnd) {
        if (!enabled || CustomFogCompatibility.usesSodium()) return null;
        CustomFogMath.Fog fog = CustomFogRenderer.adjust(camera, world, color,
            start, end, renderStart, renderEnd, skyEnd, cloudEnd);
        if (fog != null) {
            color.set((float) fog.color().r(), (float) fog.color().g(), (float) fog.color().b(), color.w);
        }
        return fog;
    }
}
