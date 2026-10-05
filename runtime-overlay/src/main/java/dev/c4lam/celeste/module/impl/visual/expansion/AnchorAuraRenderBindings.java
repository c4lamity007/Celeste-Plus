package dev.c4lam.celeste.module.impl.visual.expansion;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.combat.AnchorAuraFeature;
import dev.c4lam.celeste.render.ModuleRenderPass;
import java.util.List;
import java.util.ArrayList;
import net.minecraft.util.Util;
public final class AnchorAuraRenderBindings {
    private static final List<AnchorAuraSolidVisualMesh.Face> SHAPE=AnchorAuraBlockHighlightMesh.surface(
        List.of(new AnchorAuraBlockHighlightMesh.Cuboid(-.002,-.002,-.002,1.002,1.002,1.002)),6);
    private AnchorAuraRenderBindings() { }
    public static void attach(ModuleServices services) {
        AnchorAuraUnifiedVolumeRenderer.initialize();
        services.scope().releaseAfterCallbacks(AnchorAuraUnifiedVolumeRenderer::releaseResources);
        ModuleRenderPass.attach(services,context->{
            var quads=new ArrayList<AnchorAuraWorldGlowRenderer.Quad>();
            var plan=AnchorAuraFeature.preview();
            long age=plan==null?-1:Util.getMeasuringTimeMs()-plan.sampledAtMillis();
            if(plan!=null && plan.world()==context.world() && age>=0 && age<=500) {
                var pos=plan.position();
                AnchorAuraSolidVisuals.faces(quads,AnchorAuraBlockHighlightMesh.materialize(SHAPE,pos.getX(),pos.getY(),pos.getZ(),0x58D5A4FF,6),6);
            }
            return List.copyOf(quads);
        },(context,frame)->AnchorAuraUnifiedVolumeRenderer.draw(context,frame,true));
    }
}
