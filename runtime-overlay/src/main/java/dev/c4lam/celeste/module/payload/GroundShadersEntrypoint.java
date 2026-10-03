package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.visual.expansion.GroundShadersFeature;
import dev.c4lam.celeste.render.ModuleRenderPass;
import dev.c4lam.celeste.module.impl.visual.expansion.GroundShadersRenderer;
public final class GroundShadersEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature=new GroundShadersFeature();
        GroundShadersRenderer.initialize();
        services.scope().releaseAfterCallbacks(GroundShadersRenderer::releaseResources);
        return feature;
    }
}
