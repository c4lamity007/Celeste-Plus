package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.combat.DoubleAnchorMacroFeature;
/** Independent payload entrypoint; no eager catalogue implementation construction. */
public final class DoubleAnchorMacroEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature = new DoubleAnchorMacroFeature();
        return feature;
    }
}
