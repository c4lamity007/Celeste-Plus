package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.visual.expansion.AtmosphereFeature;
import dev.c4lam.celeste.module.impl.visual.expansion.AtmosphereVisualAtmosphere;
public final class AtmosphereEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature=new AtmosphereFeature();
        services.hook("atmosphere.palette",args->AtmosphereVisualAtmosphere.activePresetName());
        return feature;
    }
}
