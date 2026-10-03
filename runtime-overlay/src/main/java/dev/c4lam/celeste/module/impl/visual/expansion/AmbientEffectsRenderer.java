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

import java.util.ArrayList;
import java.util.List;

/** Cached depth-tested batches for ambient meshes, line glow and smooth luminous strands. */
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
    public record LightStrand(List<Vec3d> points, double size, float opacity, int color) { }
    public record TronSegment(AmbientTronField.Segment line, int color) { }
    public record Frame(List<Particle> particles, AmbientField.Style style, Vec3d right, Vec3d up,
                        SkyLinesRenderer.Frame skyLines, List<TronSegment> trails, List<LightStrand> strands) {
        public Frame(List<Particle> particles, AmbientField.Style style, Vec3d right, Vec3d up,
                     SkyLinesRenderer.Frame skyLines, List<TronSegment> trails) {
            this(particles,style,right,up,skyLines,trails,List.of());
        }
        public Frame(List<Particle> particles, AmbientField.Style style, Vec3d right, Vec3d up,
                     SkyLinesRenderer.Frame skyLines) { this(particles, style, right, up, skyLines, List.of()); }
        public static final Frame EMPTY = new Frame(List.of(), AmbientField.Style.FIREFLIES,
            Vec3d.ZERO, Vec3d.ZERO, SkyLinesRenderer.Frame.EMPTY);
        public boolean isEmpty() { return particles.isEmpty() && skyLines.isEmpty() && trails.isEmpty() && strands.isEmpty(); }
    }
    private static final RenderLayer MESH = layer(false);
    private static final RenderLayer GLOW = layer(true);
    private static final RenderLayer STRANDS = strandLayer();
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

    // Same material and stamp geometry as Trails, with terrain depth retained for ambient motes.
    private static RenderLayer strandLayer() {
        RenderPipeline pipeline = dev.c4lam.celeste.compat.BetaRenderPipelines.register(RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET)
            .withLocation(Identifier.of(CelesteClient.MOD_ID, "pipeline/ambient_strands"))
            .withVertexShader(dev.c4lam.celeste.module.delivery.ModuleResourceNames.asset(Identifier.of(CelesteClient.MOD_ID, "core/world_vfx")))
            .withFragmentShader(dev.c4lam.celeste.module.delivery.ModuleResourceNames.asset(Identifier.of(CelesteClient.MOD_ID, "core/world_bloom")))
            .withShaderDefine("ECHO_MESH", 0)
            .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS)
            .withBlend(BlendFunction.LIGHTNING).withCull(false)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST).withDepthWrite(false).build());
        return RenderLayer.of("celeste_ambient_strands", RenderSetup.builder(pipeline).translucent().expectedBufferSize(98304).build());
    }

    private static void drawStrands(WorldRenderContext context, Frame frame, Vec3d camera) {
        if (frame.strands().isEmpty()) return;
        List<AmbientEffectsWorldGlowRenderer.Quad> quads = new ArrayList<>(AmbientLightStrands.MAX_QUADS);
        for (LightStrand strand : frame.strands()) {
            if (quads.size() + AmbientLightStrands.SPRITES_PER_STRAND > AmbientLightStrands.MAX_QUADS) break;
            AmbientEffectsGlowTrail.append(quads, strand.points(), camera, AmbientLightStrands.SPRITES_PER_STRAND - 1,
                1, strand.opacity() * .75, false, progress -> strand.color(),
                progress -> Math.pow(AmbientField.smooth(progress), .75), .52 * strand.size());
            Vec3d head = strand.points().getLast();
            double radius = .18 * strand.size();
            Vec3d right = frame.right().multiply(radius), up = frame.up().multiply(radius);
            int color = dev.c4lam.celeste.render.geometry.WorldBox.argb(strand.opacity() * .55F, strand.color());
            quads.add(new AmbientEffectsWorldGlowRenderer.Quad(AmbientEffectsWorldGlowRenderer.Texture.BLOOM,
                head.subtract(right).subtract(up),head.add(right).subtract(up),
                head.add(right).add(up),head.subtract(right).add(up),color));
        }
        if (quads.isEmpty()) return;
        MatrixStack.Entry matrix = context.matrices().peek();
        VertexConsumer consumer = context.consumers().getBuffer(STRANDS);
        for (AmbientEffectsWorldGlowRenderer.Quad quad : quads) {
            strandVertex(consumer,matrix,quad.a(),camera,0,0,quad.color());
            strandVertex(consumer,matrix,quad.b(),camera,1,0,quad.color());
            strandVertex(consumer,matrix,quad.c(),camera,1,1,quad.color());
            strandVertex(consumer,matrix,quad.d(),camera,0,1,quad.color());
        }
        if (context.consumers() instanceof VertexConsumerProvider.Immediate immediate) immediate.draw(STRANDS);
    }

    private static void strandVertex(VertexConsumer out,MatrixStack.Entry matrix,Vec3d point,Vec3d camera,float u,float v,int color) {
        out.vertex(matrix,(float)(point.x-camera.x),(float)(point.y-camera.y),(float)(point.z-camera.z)).texture(u,v).color(color);
    }

    public static void draw(WorldRenderContext context, Frame frame) {
        if (frame == null || frame.isEmpty() || context.matrices() == null || context.consumers() == null
            || context.worldState() == null || context.worldState().cameraRenderState == null) return;
        Vec3d camera = context.worldState().cameraRenderState.pos;
        if (camera == null) return;
        SkyLinesRenderer.draw(context, frame.skyLines());
        drawStrands(context,frame,camera);
        if (!frame.trails().isEmpty()) {
            MatrixStack.Entry matrix = context.matrices().peek();
            VertexConsumer glow = context.consumers().getBuffer(GLOW);
            for (TronSegment trail : frame.trails()) {
                AmbientTronGeometry.beam(trail.line(), camera.x, camera.y, camera.z, (x,y,z,u,v,alpha) ->
                    vertex(glow, matrix, x,y,z,camera,u,v,0,0,0,dev.c4lam.celeste.render.geometry.WorldBox.argb(alpha,trail.color())));
            }
            if (context.consumers() instanceof VertexConsumerProvider.Immediate immediate) immediate.draw(GLOW);
        }
        if (frame.particles().isEmpty()) return;
        MatrixStack.Entry matrix = context.matrices().peek();
        VertexConsumer mesh = context.consumers().getBuffer(MESH);
        double[] geometry = new double[AmbientEffectsGeometry.SCRATCH_SIZE];
        AmbientEffectsGeometry.Sink meshSink = (x,y,z,u,v,nx,ny,nz,alpha,color) ->
            vertex(mesh,matrix,x,y,z,camera,u,v,nx,ny,nz,dev.c4lam.celeste.render.geometry.WorldBox.argb(alpha,color));
        for (Particle p : frame.particles()) {
            AmbientEffectsGeometry.mesh(p,p.style()==null?frame.style():p.style(),geometry,meshSink);
        }
        if (context.consumers() instanceof VertexConsumerProvider.Immediate immediate) immediate.draw(MESH);
        VertexConsumer glow = context.consumers().getBuffer(GLOW);
        AmbientEffectsGeometry.Sink glowSink = (x,y,z,u,v,nx,ny,nz,alpha,color) ->
            vertex(glow,matrix,x,y,z,camera,u,v,nx,ny,nz,dev.c4lam.celeste.render.geometry.WorldBox.argb(alpha,color));
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
