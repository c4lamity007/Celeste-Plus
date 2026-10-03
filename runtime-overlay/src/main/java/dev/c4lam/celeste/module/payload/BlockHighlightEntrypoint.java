package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.visual.expansion.BlockHighlightFeature;
import dev.c4lam.celeste.render.ModuleRenderPass;
import dev.c4lam.celeste.module.impl.visual.expansion.BlockHighlightUnifiedVolumeRenderer;
import dev.c4lam.celeste.module.impl.visual.expansion.BlockHighlightWorldGlowRenderer;
public final class BlockHighlightEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature=new BlockHighlightFeature();
        BlockHighlightUnifiedVolumeRenderer.initialize();
        ModuleRenderPass.attach(services,context->{
            var quads=new java.util.ArrayList<BlockHighlightWorldGlowRenderer.Quad>(); feature.extract(context,quads,192); return java.util.List.copyOf(quads);
        },(context,frame)->BlockHighlightUnifiedVolumeRenderer.draw(context,frame,true));
        services.scope().releaseAfterCallbacks(BlockHighlightUnifiedVolumeRenderer::releaseResources);
        return feature;
    }
}
