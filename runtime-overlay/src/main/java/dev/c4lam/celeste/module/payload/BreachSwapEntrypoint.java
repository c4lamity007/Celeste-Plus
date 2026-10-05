package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.expansion.sword.BreachSwapFeature;
/** Independent payload entrypoint; no eager catalogue implementation construction. */
public final class BreachSwapEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature = new BreachSwapFeature();
        return feature;
    }
}
