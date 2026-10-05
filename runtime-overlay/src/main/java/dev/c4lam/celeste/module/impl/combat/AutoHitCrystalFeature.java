package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.action.HeldUseBroker;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;

/** Activation constructs only this module's controller; retirement removes its callbacks and references. */
public final class AutoHitCrystalFeature implements ModuleFeature {
    private HeldUseBroker.Registration registration;
    private AutoHitCrystalController controller;
    @Override public String name() { return "Auto Hit Crystal"; }
    @Override public void onEnable(ModuleContext context) {
        if (registration != null) registration.close();
        controller = new AutoHitCrystalController();
        registration = HeldUseBroker.INSTANCE.register(controller, 200);
    }
    @Override public void onStartTick(ModuleContext context) { HeldUseBroker.INSTANCE.onStartTick(context); }
    @Override public void onActionTick(ModuleContext context) { HeldUseBroker.INSTANCE.onActionTick(context); }
    @Override public void onDisable(ModuleContext context) {
        if (registration != null) registration.close();
        registration = null; controller = null;
    }
    @Override public void onWorldChanged(ModuleContext context) {
        if (controller != null) controller.stop(context.client());
    }
}
