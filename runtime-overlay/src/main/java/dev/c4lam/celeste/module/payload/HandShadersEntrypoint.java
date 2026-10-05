package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.visual.expansion.HandShadersFeature;
import dev.c4lam.celeste.render.ModuleRenderPass;
import dev.c4lam.celeste.module.impl.visual.expansion.HandShadersRenderer;
public final class HandShadersEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature=new HandShadersFeature();
        HandShadersRenderer.initialize();
        services.hook("hand.begin",args->dev.c4lam.celeste.module.delivery.ScopedHandPass.wrap(HandShadersRenderer.begin()));
        services.scope().releaseAfterCallbacks(HandShadersRenderer::releaseResources);
        return feature;
    }
}
