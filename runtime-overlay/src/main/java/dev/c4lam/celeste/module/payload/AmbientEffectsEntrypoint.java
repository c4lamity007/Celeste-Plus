package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.visual.expansion.AmbientEffectsFeature;
import dev.c4lam.celeste.render.ModuleRenderPass;
import dev.c4lam.celeste.module.impl.visual.expansion.AmbientEffectsRenderer;
public final class AmbientEffectsEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature=new AmbientEffectsFeature();
        AmbientEffectsRenderer.initialize();
        ModuleRenderPass.attach(services,feature::extract,AmbientEffectsRenderer::draw);
        return feature;
    }
}
