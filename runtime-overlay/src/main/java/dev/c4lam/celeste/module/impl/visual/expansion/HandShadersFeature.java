package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;

/** Cosmetic local first-person material; never changes held stacks or item-use state. */
public final class HandShadersFeature implements ModuleFeature {
    private static boolean enabled;
    private static Settings settings = new Settings(2, 0x74DFFF, .85F, .75F);

    public record Settings(int mode, int color, float opacity, float glow) { }
    @Override public String name() { return "Hand Shaders"; }
    @Override public void onEnable(ModuleContext context) { update(context); enabled = true; HandShadersRenderer.resetFailure(); }
    @Override public void onStartTick(ModuleContext context) {
        update(context);
        enabled = true;
        HandShadersRenderer.observeEnvironment();
    }
    @Override public void onRenderFrame(ModuleContext context) { update(context); }
    @Override public void onDisable(ModuleContext context) { enabled = false; HandShadersRenderer.releaseResources(); }
    @Override public void onWorldChanged(ModuleContext context) { enabled = false; HandShadersRenderer.releaseResources(); }
    static boolean enabled() { return enabled; }
    static Settings settings() { return settings; }

    private static void update(ModuleContext context) {
        // Old Particles/Outline/Glow presets map to the pre-Flame mist-style Smoke.
        // The clean outline is part of both styles, not a third mode.
        int mode = "Flame".equals(context.mode("Mode", "Smoke")) ? 3 : 2;
        settings = new Settings(mode, VisualAtmosphere.tint(context.colorPick("Color", 0x74DFFF), 0, 0),
            finite(context.slider("Opacity", .85), .85F, .05F, 1F),
            finite(context.slider("Glow strength", .75), .75F, 0F, 2F));
    }

    private static float finite(double value, float fallback, float min, float max) {
        return Double.isFinite(value) ? (float) Math.clamp(value, min, max) : fallback;
    }
}
