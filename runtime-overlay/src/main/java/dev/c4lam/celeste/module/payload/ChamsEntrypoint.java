package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.visual.expansion.ChamsFeature;
import dev.c4lam.celeste.render.ModuleRenderPass;
import dev.c4lam.celeste.module.impl.visual.expansion.ChamsUnifiedChamsRenderer;
public final class ChamsEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature=new ChamsFeature();
        ChamsUnifiedChamsRenderer.initialize();
        ModuleRenderPass.attach(services,feature::extract,(context,frame)->ChamsUnifiedChamsRenderer.draw(context,frame,false,feature.material()));
        services.hook("chams.active",args->ChamsFeature.isActive());
        services.scope().releaseAfterCallbacks(ChamsUnifiedChamsRenderer::releaseResources);
        return feature;
    }
}
