package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.ModuleContext;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

/** Silent placement/charge/detonation with a shared CPVP turn and read-only preview. */
public final class AnchorAuraFeature implements ModuleFeature {
    private final AnchorAuraRuntime runtime = new AnchorAuraRuntime(this);
    private static volatile Preview currentPreview;
    public record Preview(ClientWorld world, BlockPos position, double targetDamage, double selfDamage,
                          boolean existing, long sampledAtMillis) { }
    public static Preview preview() { return currentPreview; }
    @Override
    public String name() {
        return "Anchor Aura";
    }
    @Override public void onEnable(ModuleContext context) {
        var scope = dev.c4lam.celeste.module.delivery.ModuleScope.current();
        if (scope != null) scope.own(runtime::clear);
        runtime.clear(); currentPreview = null; }
    @Override public void onStartTick(ModuleContext context) { runtime.start(context); currentPreview = runtime.preview(); }
    @Override public void onActionTick(ModuleContext context) { runtime.action(context); }
    @Override public void onEndTick(ModuleContext context) { runtime.end(); }
    @Override public void onDisable(ModuleContext context) { runtime.clear(); currentPreview = null; }
    @Override public void onWorldChanged(ModuleContext context) { runtime.clear(); currentPreview = null; }
}
