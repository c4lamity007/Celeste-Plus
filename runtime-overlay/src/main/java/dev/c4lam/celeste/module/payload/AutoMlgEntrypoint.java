package dev.c4lam.celeste.module.payload;

import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.expansion.AutoMlgFeature;

/** This factory and its implementation belong to one verified module artifact. */
public final class AutoMlgEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature = new AutoMlgFeature();
        return feature;
    }
}
