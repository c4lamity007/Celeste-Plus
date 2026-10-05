package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.expansion.sword.LungeSwapFeature;
/** Independent payload entrypoint; no eager catalogue implementation construction. */
public final class LungeSwapEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature = new LungeSwapFeature();
        return feature;
    }
}
