package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;

/** Two presentations of the same standalone four-action anchor sequence. */
public final class SafeAnchorFeature implements ModuleFeature {
    private final boolean silent;

    public SafeAnchorFeature(boolean silent) { this.silent = silent; }

    @Override public String name() { return silent ? "Silent Safe Anchor" : "Safe Anchor"; }
    @Override public void onStartTick(ModuleContext context) {
        SafeAnchorCoordinator.INSTANCE.onStartTick(context);
    }
    @Override public void onActionTick(ModuleContext context) {
        SafeAnchorCoordinator.INSTANCE.onActionTick(context);
    }
    @Override public void onEndTick(ModuleContext context) {
        SafeAnchorCoordinator.INSTANCE.onEndTick(context);
    }
    @Override public void onRenderFrame(ModuleContext context) {
        SafeAnchorCoordinator.INSTANCE.onRenderFrame(context);
    }
    @Override public void onDisable(ModuleContext context) {
        SafeAnchorCoordinator.INSTANCE.onDisable(name(), context.client());
    }
    @Override public void onWorldChanged(ModuleContext context) {
        SafeAnchorCoordinator.INSTANCE.onWorldChanged(context.client());
    }
}
