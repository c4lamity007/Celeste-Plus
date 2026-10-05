package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.visual.expansion.CustomFogFeature;
import dev.c4lam.celeste.render.ModuleRenderPass;
import dev.c4lam.celeste.module.impl.visual.expansion.CustomFogModuleHooks;
import dev.c4lam.celeste.module.impl.visual.expansion.ShaderFogRenderer;
public final class CustomFogEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature=new CustomFogFeature();
        CustomFogModuleHooks.bind(services);
        ShaderFogRenderer.initialize();
        services.scope().releaseAfterCallbacks(ShaderFogRenderer::releaseResources);
        return feature;
    }
}
