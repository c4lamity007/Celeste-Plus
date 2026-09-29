package dev.c4lam.celeste.module.impl.visual.expansion;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.c4lam.celeste.CelesteClient;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/** Two cached depth-tested batches: closed 3D cores/blades, then optional supporting bloom. */
public final class AmbientEffectsRenderer {
    public record Particle(Vec3d position, double radius, double yaw, double pitch, float opacity, int color,
                           AmbientField.Style style) implements AmbientEffectsGeometry.ParticleView {
        public Particle(Vec3d position,double radius,double yaw,double pitch,float opacity,int color) {
            this(position,radius,yaw,pitch,opacity,color,null);
        }
        @Override public double x() { return position.x; }
        @Override public double y() { return position.y; }
        @Override public double z() { return position.z; }
    }
    public record TronSegment(AmbientTronField.Segment line, int color) { }
    public record Frame(List<Particle> particles, AmbientField.Style style, Vec3d right, Vec3d up,
                        SkyLinesRenderer.Frame skyLines, List<TronSegment> trails) {
        public Frame(List<Particle> particles, AmbientField.Style style, Vec3d right, Vec3d up,
                     SkyLinesRenderer.Frame skyLines) { this(particles, style, right, up, skyLines, List.of()); }
        public static final Frame EMPTY = new Frame(List.of(), AmbientField.Style.FIREFLIES,
            Vec3d.ZERO, Vec3d.ZERO, SkyLinesRenderer.Frame.EMPTY);
        public boolean isEmpty() { return particles.isEmpty() && skyLines.isEmpty() && trails.isEmpty(); }
    }
    private static final RenderLayer MESH = layer(false);
    private static final RenderLayer GLOW = layer(true);
    private AmbientEffectsRenderer() { }
    public static void initialize() { SkyLinesRenderer.initialize(); }

    private static RenderLayer layer(boolean glow) {
        String name = glow ? "ambient_glow" : "ambient_mesh";
        RenderPipeline pipeline = dev.c4lam.celeste.compat.BetaRenderPipelines.register(RenderPipeline.builder(RenderPipelines.TRANSFORMS_PROJECTION_FOG_SNIPPET)
            .withLocation(Identifier.of(CelesteClient.MOD_ID, "pipeline/" + name))
            .withVertexShader(Identifier.of(CelesteClient.MOD_ID, "core/ambient_mesh"))
            .withFragmentShader(Identifier.of(CelesteClient.MOD_ID, "core/" + name))
            .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR_NORMAL,
                glow ? VertexFormat.DrawMode.QUADS : VertexFormat.DrawMode.TRIANGLES)
            .withBlend(glow ? BlendFunction.LIGHTNING : BlendFunction.TRANSLUCENT)
            .withCull(!glow).withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST).withDepthWrite(false).build());
        return RenderLayer.of("celeste_" + name, RenderSetup.builder(pipeline).translucent().expectedBufferSize(131072).build());
    }

    public static void draw(WorldRenderContext context, Frame frame) {
        if (frame == null || frame.isEmpty() || context.matrices() == null || context.consumers() == null
            || context.worldState() == null || context.worldState().cameraRenderState == null) return;
        Vec3d camera = context.worldState().cameraRenderState.pos;
        if (camera == null) return;
        SkyLinesRenderer.draw(context, frame.skyLines());
        if (!frame.trails().isEmpty()) {
            MatrixStack.Entry matrix = context.matrices().peek();
            VertexConsumer glow = context.consumers().getBuffer(GLOW);
            for (TronSegment trail : frame.trails()) {
                AmbientTronGeometry.beam(trail.line(), camera.x, camera.y, camera.z, (x,y,z,u,v,alpha) ->
                    vertex(glow, matrix, x,y,z,camera,u,v,0,0,0,VisualExpansionRenderer.argb(alpha,trail.color())));
            }
            if (context.consumers() instanceof VertexConsumerProvider.Immediate immediate) immediate.draw(GLOW);
        }
        if (frame.particles().isEmpty()) return;
        MatrixStack.Entry matrix = context.matrices().peek();
        VertexConsumer mesh = context.consumers().getBuffer(MESH);
        double[] geometry = new double[AmbientEffectsGeometry.SCRATCH_SIZE];
        AmbientEffectsGeometry.Sink meshSink = (x,y,z,u,v,nx,ny,nz,alpha,color) ->
            vertex(mesh,matrix,x,y,z,camera,u,v,nx,ny,nz,VisualExpansionRenderer.argb(alpha,color));
        for (Particle p : frame.particles()) {
            AmbientEffectsGeometry.mesh(p,p.style()==null?frame.style():p.style(),geometry,meshSink);
        }
        if (context.consumers() instanceof VertexConsumerProvider.Immediate immediate) immediate.draw(MESH);
        VertexConsumer glow = context.consumers().getBuffer(GLOW);
        AmbientEffectsGeometry.Sink glowSink = (x,y,z,u,v,nx,ny,nz,alpha,color) ->
            vertex(glow,matrix,x,y,z,camera,u,v,nx,ny,nz,VisualExpansionRenderer.argb(alpha,color));
        for (Particle p : frame.particles()) {
            AmbientEffectsGeometry.glow(p,p.style()==null?frame.style():p.style(),geometry,camera.x,camera.y,camera.z,
                frame.right().x,frame.right().y,frame.right().z,frame.up().x,frame.up().y,frame.up().z,glowSink);
        }
        if (context.consumers() instanceof VertexConsumerProvider.Immediate immediate) immediate.draw(GLOW);
    }

    private static void vertex(VertexConsumer consumer, MatrixStack.Entry matrix, double x, double y, double z,
                               Vec3d camera, float u, float v, double nx, double ny, double nz, int color) {
        consumer.vertex(matrix, (float)(x-camera.x), (float)(y-camera.y), (float)(z-camera.z))
            .texture(u,v).color(color).normal((float)nx,(float)ny,(float)nz);
    }
}
