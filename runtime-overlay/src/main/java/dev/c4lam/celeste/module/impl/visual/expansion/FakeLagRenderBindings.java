package dev.c4lam.celeste.module.impl.visual.expansion;
import dev.c4lam.celeste.module.delivery.ModuleFrame;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.render.WorldOverlayPass;
public final class FakeLagRenderBindings {
    private FakeLagRenderBindings() { }
    public static void attach(ModuleServices services) {
        FakeLagUnifiedChamsRenderer.initialize();
        services.scope().releaseAfterCallbacks(FakeLagUnifiedChamsRenderer::releaseResources);
        ModuleFrame<FakeLagPlayerEchoRenderer.Frame> echoes=ModuleFrame.create();
        FakeLagModuleVisualRenderer.attach(services,(context,boxes,lines,glows)->
            echoes.set(context.worldState(),FakeLagVisualRenderer.extract(context,boxes,lines,glows,dev.c4lam.celeste.render.ModuleVisualBudget.remaining("fake_lag"))));
        WorldOverlayPass.register("celeste:module/fake_lag/echoes",context->{
            var frame=echoes.get(context.worldState()); if(frame!=null) FakeLagUnifiedChamsRenderer.draw(context,frame,true);
        });
    }
}
