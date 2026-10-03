package dev.c4lam.celeste.module;

import dev.c4lam.celeste.module.delivery.ModuleHookRegistry;

/** Obsolete adapter retained only as an explicit modular-profile exclusion. */
public final class LegacyModuleHooks {
    private LegacyModuleHooks() { }
    public static void install(ModuleHookRegistry registry) {
        throw new IllegalStateException("Module hooks are installed by their scoped factory");
    }
}
