package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.action.HeldUseBroker;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;

/** Activation constructs only this module's controller; retirement removes its callbacks and references. */
public final class AnchorMacroFeature implements ModuleFeature {
    private HeldUseBroker.Registration registration;
    private AnchorController controller;
    @Override public String name() { return "Anchor Macro"; }
    @Override public void onEnable(ModuleContext context) {
        if (registration != null) registration.close();
        controller = new AnchorController();
        registration = HeldUseBroker.INSTANCE.register(controller, 300);
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
