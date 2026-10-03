package dev.c4lam.celeste.module.payload;

import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.expansion.inventory.FastPlaceFeature;

/** This factory and its implementation belong to one verified module artifact. */
public final class FastPlaceEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature = new FastPlaceFeature();
        return feature;
    }
}
