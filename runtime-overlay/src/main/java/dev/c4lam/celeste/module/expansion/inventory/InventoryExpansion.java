package dev.c4lam.celeste.module.expansion.inventory;

import dev.c4lam.celeste.module.ModuleManager;

/** Clean-room player/inventory expansion backed by the supplied module examples. */
public final class InventoryExpansion {
    private static boolean registered;

    private InventoryExpansion() {
    }

    public static synchronized void register(ModuleManager modules) {
        if (registered) {
            return;
        }
        registered = true;
        modules.register(new AutoPotFeature());
        modules.register(new ThrowPotFeature());
        modules.register(new SilentTotemFeature());
        modules.register(new AutoExtinguishFeature());
        modules.register(new AutoDoubleHandFeature());
        modules.register(new ElytraSwapFeature());
        modules.register(new ReBuffNotifierFeature());
        modules.register(new HoverTotemFeature());
        modules.register(new LegitAutoTotemFeature());
        modules.register(new AutoWebFeature());
        modules.register(new AutoDrainFeature());
        modules.register(new PotionRefillFeature());
        modules.register(new FastPlaceFeature());
        modules.register(new FastExpFeature());
    }
}
