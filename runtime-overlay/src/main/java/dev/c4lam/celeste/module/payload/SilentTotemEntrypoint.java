package dev.c4lam.celeste.module.payload;

import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.expansion.inventory.SilentTotemFeature;

/** This factory and its implementation belong to one verified module artifact. */
public final class SilentTotemEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature = new SilentTotemFeature();
        return feature;
    }
}
