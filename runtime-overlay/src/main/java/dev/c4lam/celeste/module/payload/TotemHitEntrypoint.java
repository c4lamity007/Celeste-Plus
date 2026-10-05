package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.expansion.sword.TotemHitFeature;
/** Independent payload entrypoint; no eager catalogue implementation construction. */
public final class TotemHitEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature = new TotemHitFeature();
        return feature;
    }
}
