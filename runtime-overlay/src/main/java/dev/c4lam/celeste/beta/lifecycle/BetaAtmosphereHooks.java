package dev.c4lam.celeste.beta.lifecycle;

import dev.c4lam.celeste.compat.AtmosphereHooks;
import dev.celestebeta.host.AtmosphereHookBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.fog.FogData;
import net.minecraft.client.world.ClientWorld;
import org.joml.Vector4f;

/** Module-controlled fog/fullbright behavior; does not own or change vanilla GPU resources. */
public final class BetaAtmosphereHooks implements AtmosphereHookBridge.Handler, AutoCloseable {
    private final AtmosphereHookBridge.Registration registration;
    private BetaAtmosphereHooks() { registration = AtmosphereHookBridge.install(this); }
    public static BetaAtmosphereHooks connect(MinecraftClient client) {
        if (!client.isOnThread()) throw new IllegalStateException("Connect atmosphere on the client thread");
        AtmosphereHookBridge.assertVisibleFrom(client.getClass().getClassLoader());
        AtmosphereHookBridge.assertVisibleFrom(BetaAtmosphereHooks.class.getClassLoader());
        return new BetaAtmosphereHooks();
    }
    public boolean refreshLightmap() { return AtmosphereHooks.refreshLightmap(); }
    public float nightVision(float vanilla) { return AtmosphereHooks.nightVision(vanilla); }
    public float gamma(float vanilla) { return AtmosphereHooks.gamma(vanilla); }
    public void sharedFog(boolean enabled, Object camera, Object world, Object data, Object color) {
        AtmosphereHooks.sharedFog(enabled, (Camera) camera, (ClientWorld) world, (FogData) data, (Vector4f) color);
    }
    public void vanillaFog(boolean enabled, Object camera, Object world, Object color, float[] distances) {
        AtmosphereHooks.vanillaFog(enabled, (Camera) camera, (ClientWorld) world, (Vector4f) color, distances);
    }
    @Override public void close() { registration.close(); }
}
