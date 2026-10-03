package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;

/** Two presentations of the same standalone four-action anchor sequence. */
public final class SafeAnchorFeature implements ModuleFeature {
    private final boolean silent;
    private final SafeAnchorCoordinator coordinator;

    public SafeAnchorFeature(boolean silent) { this.silent = silent; this.coordinator = new SafeAnchorCoordinator(name()); }

    @Override public String name() { return silent ? "Silent Safe Anchor" : "Safe Anchor"; }
    @Override public void onEnable(ModuleContext context) {
        var scope = dev.c4lam.celeste.module.delivery.ModuleScope.current();
        if (scope != null) scope.own(() -> coordinator.onWorldChanged(net.minecraft.client.MinecraftClient.getInstance()));
    }
    @Override public void onStartTick(ModuleContext context) {
        coordinator.onStartTick(context);
    }
    @Override public void onActionTick(ModuleContext context) {
        coordinator.onActionTick(context);
    }
    @Override public void onEndTick(ModuleContext context) {
        coordinator.onEndTick(context);
    }
    @Override public void onRenderFrame(ModuleContext context) {
        coordinator.onRenderFrame(context);
    }
    @Override public void onDisable(ModuleContext context) {
        coordinator.onDisable(name(), context.client());
    }
    @Override public void onWorldChanged(ModuleContext context) {
        coordinator.onWorldChanged(context.client());
    }
}
