package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.delivery.ModuleFrame;
import dev.c4lam.celeste.render.ModuleBoxRenderer;
import dev.c4lam.celeste.render.WorldOverlayPass;
import dev.c4lam.celeste.module.impl.visual.HoleEspFeature;
import dev.c4lam.celeste.module.impl.visual.expansion.HoleEspWorldGlowRenderer;
import dev.c4lam.celeste.module.impl.visual.expansion.HoleEspUnifiedVolumeRenderer;
public final class HoleEspEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature=new HoleEspFeature();
        HoleEspWorldGlowRenderer.initialize();
        services.scope().releaseAfterCallbacks(HoleEspUnifiedVolumeRenderer::releaseResources);
        ModuleFrame<java.util.List<HoleEspWorldGlowRenderer.Quad>> detail=ModuleFrame.create();
        ModuleBoxRenderer.attach(services,(context,boxes)->{
            var glows=new java.util.ArrayList<HoleEspWorldGlowRenderer.Quad>(); feature.extract(context,boxes,glows);
            detail.set(context.worldState(),java.util.List.copyOf(glows));
        });
        WorldOverlayPass.register("celeste:module/hole_esp/glow",context->{
            var frame=detail.get(context.worldState()); if(frame!=null) HoleEspWorldGlowRenderer.draw(context,frame);
        });
        return feature;
    }
}
