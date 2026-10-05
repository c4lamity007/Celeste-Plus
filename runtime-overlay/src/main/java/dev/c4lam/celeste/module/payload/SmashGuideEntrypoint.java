package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.combat.SmashGuideFeature;
/** Independent payload entrypoint; no eager catalogue implementation construction. */
public final class SmashGuideEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature = new SmashGuideFeature();
        dev.c4lam.celeste.module.impl.combat.CombatOverlayRenderer.initialize(feature);
        services.scope().own(dev.c4lam.celeste.module.impl.combat.CombatOverlayRenderer::clear);
        return feature;
    }
}
