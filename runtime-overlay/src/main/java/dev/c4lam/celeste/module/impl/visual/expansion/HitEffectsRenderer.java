package dev.c4lam.celeste.module.impl.visual.expansion;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.c4lam.celeste.CelesteClient;
import dev.c4lam.celeste.auth.AuthBootstrap;
import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;
import dev.c4lam.celeste.render.WorldOverlayPass;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

/** A private bounded batch; actual effect depth drives fog, and terrain occludes each sprite. */
public final class HitEffectsRenderer {
    public record Frame(Object token,Object world,HitEffectsState.Frame effects,Vec3d right,Vec3d up) {
        public static final Frame EMPTY=new Frame(null,null,HitEffectsState.Frame.EMPTY,Vec3d.ZERO,Vec3d.ZERO);
    }
    private static final RenderStateDataKey<Frame> FRAME_KEY=RenderStateDataKey.create(() -> "celeste_hit_effects");
    private static final RenderLayer LAYER=RenderLayer.of("celeste_hit_effects",
        RenderSetup.builder(dev.c4lam.celeste.compat.BetaRenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.TRANSFORMS_PROJECTION_FOG_SNIPPET)
                .withLocation(Identifier.of(CelesteClient.MOD_ID,"pipeline/hit_effects"))
                .withVertexShader(Identifier.of(CelesteClient.MOD_ID,"core/hit_effects"))
                .withFragmentShader(Identifier.of(CelesteClient.MOD_ID,"core/hit_effects"))
                .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR,VertexFormat.DrawMode.QUADS)
                .withBlend(BlendFunction.LIGHTNING).withCull(false)
                .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST).withDepthWrite(false)
                .withColorWrite(true,false).build())).translucent().expectedBufferSize(131072).build());
    private static boolean initialized;
    private HitEffectsRenderer() { }

    public static synchronized void initialize(HitEffectsFeature feature) {
        if(initialized) return;
        initialized=true;
        BetaSubscriptions.register(WorldRenderEvents.END_EXTRACTION,"celeste:hit_effects/extract",
            WorldRenderEvents.EndExtraction.class,context -> {
                if(context.worldState()==null) return;
                Frame frame=AuthBootstrap.authorized() && !dev.c4lam.celeste.compat.RenderCompatibility.isShadowPass()
                    ? feature.extract(context) : Frame.EMPTY;
                context.worldState().setData(FRAME_KEY,frame);
            },WorldRenderEvents.END_EXTRACTION::register);
        WorldOverlayPass.register("celeste:hit_effects/draw",context -> draw(context,feature));
    }

    private static void draw(WorldRenderContext context,HitEffectsFeature feature) {
        if(!AuthBootstrap.authorized() || dev.c4lam.celeste.compat.RenderCompatibility.isShadowPass()
            || context.worldState()==null || context.worldState().cameraRenderState==null
            || context.matrices()==null || context.consumers()==null) return;
        Frame frame=context.worldState().getData(FRAME_KEY);
        if(frame==null || frame==Frame.EMPTY || frame.effects.isEmpty() || !feature.current(frame)) return;
        Vec3d camera=context.worldState().cameraRenderState.pos;
        if(camera==null) return;
        MatrixStack.Entry matrix=context.matrices().peek();
        VertexConsumer buffer=context.consumers().getBuffer(LAYER);
        for(HitEffectsState.Beam line:frame.effects.ground()) {
            Vec3d a=vec(line.from()),b=vec(line.to());
            if(a.squaredDistanceTo(camera)>64*64) continue;
            Vec3d delta=b.subtract(a);
            Vec3d side=new Vec3d(-delta.z,0,delta.x);
            if(side.lengthSquared()<1e-10) side=delta.crossProduct(camera.subtract(a));
            if(side.lengthSquared()<1e-10) continue;
            side=side.normalize().multiply(line.width());
            beam(buffer,matrix,camera,a,b,side,line.color(),line.opacity(),line.opacity());
        }
        for(HitEffectsState.Sprite sprite:frame.effects.particles()) {
            Vec3d center=vec(sprite.position());
            if(center.squaredDistanceTo(camera)>64*64) continue;
            Vec3d tail=vec(sprite.tail()),motion=center.subtract(tail);
            if(sprite.style()!=HitEffectsState.Style.DOTS && motion.lengthSquared()>.00005) {
                Vec3d side=motion.crossProduct(camera.subtract(center));
                if(side.lengthSquared()>1e-10) beam(buffer,matrix,camera,tail,center,
                    side.normalize().multiply(sprite.radius()*.46),sprite.color(),0,sprite.opacity()*.5);
            }
            double c=Math.cos(sprite.rotation()),s=Math.sin(sprite.rotation());
            double stretch=sprite.style()==HitEffectsState.Style.SPARKS ? 1.8 : 1;
            Vec3d u=frame.right.multiply(c).add(frame.up.multiply(s)).multiply(sprite.radius()*stretch);
            Vec3d v=frame.up.multiply(c).subtract(frame.right.multiply(s)).multiply(sprite.radius());
            float offset=switch(sprite.style()) { case DOTS -> 0; case STARS -> 2; case SPARKS -> 4; };
            int color=argb(sprite.opacity(),sprite.color());
            vertex(buffer,matrix,camera,center.subtract(u).subtract(v),offset,0,color);
            vertex(buffer,matrix,camera,center.add(u).subtract(v),offset+1,0,color);
            vertex(buffer,matrix,camera,center.add(u).add(v),offset+1,1,color);
            vertex(buffer,matrix,camera,center.subtract(u).add(v),offset,1,color);
        }
        if(context.consumers() instanceof VertexConsumerProvider.Immediate immediate) immediate.draw(LAYER);
    }
    private static void beam(VertexConsumer buffer,MatrixStack.Entry matrix,Vec3d camera,
                             Vec3d a,Vec3d b,Vec3d side,int rgb,double startAlpha,double endAlpha) {
        int start=argb(startAlpha,rgb),end=argb(endAlpha,rgb);
        vertex(buffer,matrix,camera,a.subtract(side),6,0,start);
        vertex(buffer,matrix,camera,a.add(side),7,0,start);
        vertex(buffer,matrix,camera,b.add(side),7,1,end);
        vertex(buffer,matrix,camera,b.subtract(side),6,1,end);
    }
    private static void vertex(VertexConsumer buffer,MatrixStack.Entry matrix,Vec3d camera,Vec3d point,float u,float v,int color) {
        buffer.vertex(matrix,(float)(point.x-camera.x),(float)(point.y-camera.y),(float)(point.z-camera.z))
            .texture(u,v).color(color);
    }
    private static int argb(double alpha,int color) { return ((int)Math.round(Math.max(0,Math.min(1,alpha))*255)<<24)|(color&0xFFFFFF); }
    private static Vec3d vec(HitEffectsState.Point point) { return new Vec3d(point.x(),point.y(),point.z()); }
}
