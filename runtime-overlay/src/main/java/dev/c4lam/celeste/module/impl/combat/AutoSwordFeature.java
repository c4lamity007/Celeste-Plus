package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.module.ModuleContext;

/** Enables the shared sword/axe selection policy used by attacking modules. */
public final class AutoSwordFeature extends AutoSwordCombatFeature {
    @Override
    public String name() {
        return "Auto Sword";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        // Intentionally passive: a selection is only sent atomically around a real action.
    }
}
