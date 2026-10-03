package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;

/** One palette for enabled visual effects, with the individual settings retained underneath. */
public final class AtmosphereFeature implements ModuleFeature {
    @Override public String name() { return "Atmosphere"; }
    @Override public void onEnable(ModuleContext context) { update(context); }
    @Override public void onStartTick(ModuleContext context) { update(context); }
    @Override public void onRenderFrame(ModuleContext context) { update(context); }
    @Override public void onDisable(ModuleContext context) { AtmosphereVisualAtmosphere.clear(); }
    @Override public void onWorldChanged(ModuleContext context) { AtmosphereVisualAtmosphere.clear(); }
    private static void update(ModuleContext context) {
        AtmosphereVisualAtmosphere.configure(context.module().enabled() && context.client().world != null && context.client().player != null,
            context.mode("Preset", "Abyss"), context.toggle("Advanced", false));
    }
}
