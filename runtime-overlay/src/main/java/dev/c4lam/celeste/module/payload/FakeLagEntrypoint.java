package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.combat.FakeLagFeature;
/** Independent payload entrypoint; no eager catalogue implementation construction. */
public final class FakeLagEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature = new FakeLagFeature();
        dev.c4lam.celeste.module.impl.visual.expansion.FakeLagRenderBindings.attach(services);
        return feature;
    }
}
