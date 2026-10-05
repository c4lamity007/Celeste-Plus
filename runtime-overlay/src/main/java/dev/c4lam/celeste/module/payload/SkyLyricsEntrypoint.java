package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.visual.expansion.SkyLyricsFeature;
import dev.c4lam.celeste.render.ModuleRenderPass;
import dev.c4lam.celeste.module.impl.visual.expansion.SkyLyricsRenderer;
public final class SkyLyricsEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature=new SkyLyricsFeature();
        ModuleRenderPass.attach(services,feature::extract,SkyLyricsRenderer::draw,true);
        services.scope().releaseAfterCallbacks(SkyLyricsRenderer::release);
        return feature;
    }
}
