package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.delivery.ModuleFrame;
import dev.c4lam.celeste.render.ModuleBoxRenderer;
import dev.c4lam.celeste.render.WorldOverlayPass;
import dev.c4lam.celeste.module.impl.visual.EntityEspFeature;
import dev.c4lam.celeste.module.impl.visual.expansion.EntityEspPlayerEchoRenderer;
import dev.c4lam.celeste.module.impl.visual.expansion.EntityEspUnifiedChamsRenderer;
public final class EntityEspEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature=new EntityEspFeature();
        EntityEspUnifiedChamsRenderer.initialize();
        services.scope().releaseAfterCallbacks(EntityEspUnifiedChamsRenderer::releaseResources);
        ModuleFrame<EntityEspPlayerEchoRenderer.Frame> detail=ModuleFrame.create();
        ModuleBoxRenderer.attach(services,(context,boxes)->{
            var echoes=new java.util.ArrayList<EntityEspPlayerEchoRenderer.Echo>(); feature.extract(context,boxes,echoes);
            detail.set(context.worldState(),new EntityEspPlayerEchoRenderer.Frame(echoes));
        });
        WorldOverlayPass.register("celeste:module/entity_esp/chams",context->{
            var frame=detail.get(context.worldState()); if(frame!=null) EntityEspUnifiedChamsRenderer.draw(context,frame);
        });
        return feature;
    }
}
