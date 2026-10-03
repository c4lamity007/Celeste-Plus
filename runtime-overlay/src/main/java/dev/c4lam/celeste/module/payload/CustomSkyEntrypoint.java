package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.visual.expansion.SkyboxFeature;
import dev.c4lam.celeste.render.ModuleRenderPass;
import dev.c4lam.celeste.module.impl.visual.expansion.SkyboxRenderer;
import dev.c4lam.celeste.module.impl.visual.expansion.SkyboxModuleHooks;
public final class CustomSkyEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature=new SkyboxFeature();
        SkyboxRenderer.initialize();
        SkyboxModuleHooks.bind(services);
        services.hook("sky.fog_mode",args->SkyboxFeature.fogMode());
        services.scope().own(SkyboxModuleHooks::clear);
        return feature;
    }
}
