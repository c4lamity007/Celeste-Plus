package dev.c4lam.celeste.module.payload;

import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.visual.FullbrightFeature;

/** This factory and its implementation belong to one verified module artifact. */
public final class FullbrightEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature = new FullbrightFeature();
        services.hook("light.refresh", args -> { return FullbrightFeature.isTransitioning(); });
        services.hook("light.gamma", args -> { return FullbrightFeature.adjustGammaLightmap(((Number) args[0]).floatValue()); });
        services.hook("light.night_vision", args -> { return FullbrightFeature.adjustNightVisionLightmap(((Number) args[0]).floatValue()); });
        return feature;
    }
}
