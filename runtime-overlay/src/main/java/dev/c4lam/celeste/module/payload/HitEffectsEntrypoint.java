package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.visual.expansion.HitEffectsFeature;
import dev.c4lam.celeste.render.ModuleRenderPass;
import dev.c4lam.celeste.module.impl.visual.expansion.HitEffectsRenderer;
import dev.c4lam.celeste.module.impl.visual.expansion.HitDisplacementRenderer;
public final class HitEffectsEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature=new HitEffectsFeature();
        HitEffectsRenderer.initialize(feature);
        HitDisplacementRenderer.initialize();
        services.hook("attack.hit",args->{ HitEffectsFeature.recordAttack((net.minecraft.client.MinecraftClient)args[0],
            (net.minecraft.entity.LivingEntity)args[1]); return null; });
        services.scope().releaseAfterCallbacks(HitDisplacementRenderer::releaseResources);
        return feature;
    }
}
