package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.client.world.ClientWorld;

/** Local surface color only; never edits terrain, entities, depth, or saved palette settings. */
public final class GroundShadersFeature implements ModuleFeature {
    record Options(Object worldToken, GroundShadersMath.Settings settings) { }
    private record Snapshot(ClientWorld world, Options options) { }
    private static volatile Snapshot active;

    @Override public String name() { return "Ground Shaders"; }
    @Override public void onEnable(ModuleContext context) { update(context); }
    @Override public void onStartTick(ModuleContext context) { update(context); }
    @Override public void onRenderFrame(ModuleContext context) {
        Snapshot snapshot = active;
        if (snapshot != null && snapshot.world != context.client().world) clear();
        update(context);
    }
    @Override public void onDisable(ModuleContext context) { clear(); }
    @Override public void onWorldChanged(ModuleContext context) { clear(); }

    private void clear() {
        active = null;
        GroundShadersRenderer.reset();
    }

    private void update(ModuleContext context) {
        ClientWorld world = context.client().world;
        Snapshot previous = active;
        if (!context.module().enabled() || world == null || context.client().player == null) {
            if (previous != null) clear();
            return;
        }
        Object token;
        if (previous == null || previous.world != world) {
            GroundShadersRenderer.reset();
            token = new Object();
        } else token = previous.options.worldToken;
        GroundShadersMath.Settings settings = GroundShadersMath.settings(context.slider("Intensity", .35),
            GroundShadersVisualAtmosphere.tint(context.colorPick("Color", 0x2EAFD6), 0, 0));
        active = new Snapshot(world, new Options(token, settings));
    }

    static Options options(ClientWorld world) {
        Snapshot snapshot = active;
        return snapshot != null && snapshot.world == world ? snapshot.options : null;
    }

    static GroundShadersMath.Settings settings(ClientWorld world) {
        Options options = options(world);
        return options == null ? null : options.settings;
    }
}
