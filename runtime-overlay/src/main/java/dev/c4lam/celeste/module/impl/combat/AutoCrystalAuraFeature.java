package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.ModuleContext;

/** Silent sword/crystal sequence; pairs with Anchor Aura through a shared CPVP turn. */
public final class AutoCrystalAuraFeature implements ModuleFeature {
    private final CrystalAuraRuntime runtime = new CrystalAuraRuntime(this);
    @Override
    public String name() {
        return "Auto Crystal Aura";
    }
    @Override public void onEnable(ModuleContext context) {
        var scope = dev.c4lam.celeste.module.delivery.ModuleScope.current();
        if (scope != null) scope.own(runtime::clear);
        runtime.clear(); }
    @Override public void onStartTick(ModuleContext context) { runtime.start(context); }
    @Override public void onActionTick(ModuleContext context) { runtime.action(context); }
    @Override public void onEndTick(ModuleContext context) { runtime.end(); }
    @Override public void onDisable(ModuleContext context) { runtime.clear(); }
    @Override public void onWorldChanged(ModuleContext context) { runtime.clear(); }
}
