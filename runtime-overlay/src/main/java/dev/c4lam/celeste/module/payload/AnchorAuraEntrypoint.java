package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.combat.AnchorAuraFeature;
/** Independent payload entrypoint; no eager catalogue implementation construction. */
public final class AnchorAuraEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature = new AnchorAuraFeature();
        dev.c4lam.celeste.module.impl.visual.expansion.AnchorAuraRenderBindings.attach(services);
        return feature;
    }
}
