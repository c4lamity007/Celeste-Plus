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
    public record Particle(Vec3d position, double radius, double yaw, double pitch, float opacity, int color) { }
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
    private static final int[][] FACES = {{0,3,2},{0,4,3},{0,5,4},{0,2,5},
        {1,2,3},{1,3,4},{1,4,5},{1,5,2}};
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
        double stretch = frame.style() == AmbientField.Style.CRYSTALS ? 2.2 : frame.style() == AmbientField.Style.SNOW ? .35 : 1;
        double[] geometry = new double[Math.max(AmbientGeometry.SCRATCH_SIZE, AmbientTesseractGeometry.SCRATCH_SIZE)];
        for (Particle p : frame.particles()) {
            if (frame.style() == AmbientField.Style.TESSERACTS) {
                AmbientTesseractGeometry.corners(geometry,p.position().x,p.position().y,p.position().z,p.radius(),p.yaw(),p.pitch());
                AmbientTesseractGeometry.fill(geometry,(x,y,z,u,v,nx,ny,nz,alpha) -> vertex(mesh,matrix,x,y,z,camera,u,v,nx,ny,nz,
                    VisualExpansionRenderer.argb(p.opacity()*alpha,p.color())));
                continue;
            }
            if (frame.style() == AmbientField.Style.LEAVES) {
                AmbientGeometry.leaf(geometry, p.position().x, p.position().y, p.position().z,
                    p.radius(), p.yaw(), p.pitch());
                int color = VisualExpansionRenderer.argb(p.opacity() * .96F, p.color());
                // Two closed folded surfaces: culling shows only the facing side as a leaf tumbles.
                for (int edge = 0; edge < AmbientGeometry.LEAF_EDGE_VERTICES; edge++) {
                    int next = (edge + 1) % AmbientGeometry.LEAF_EDGE_VERTICES;
                    leafFace(mesh, matrix, geometry, 8, next, edge, camera, color);
                    leafFace(mesh, matrix, geometry, 9, edge, next, camera, color);
                }
                continue;
            }
            AmbientGeometry.core(geometry, p.position().x, p.position().y, p.position().z,
                p.radius(), stretch, p.yaw(), p.pitch());
            int color = VisualExpansionRenderer.argb(p.opacity() * .88F, p.color());
            for (int[] face : FACES) {
                AmbientGeometry.normal(geometry, face[0], face[1], face[2]);
                coreVertex(mesh, matrix, geometry, face[0], camera, 0, 0, color);
                coreVertex(mesh, matrix, geometry, face[1], camera, 1, 0, color);
                coreVertex(mesh, matrix, geometry, face[2], camera, .5F, 1, color);
            }
        }
        if (context.consumers() instanceof VertexConsumerProvider.Immediate immediate) immediate.draw(MESH);
        if (frame.style() == AmbientField.Style.SNOW || frame.style() == AmbientField.Style.LEAVES) return;
        VertexConsumer glow = context.consumers().getBuffer(GLOW);
        for (Particle p : frame.particles()) {
            if (frame.style() == AmbientField.Style.TESSERACTS) {
                AmbientTesseractGeometry.corners(geometry,p.position().x,p.position().y,p.position().z,p.radius(),p.yaw(),p.pitch());
                AmbientTesseractGeometry.beams(geometry,camera.x,camera.y,camera.z,p.radius(),(x,y,z,u,v,nx,ny,nz,alpha) ->
                    vertex(glow,matrix,x,y,z,camera,u,v,nx,ny,nz,VisualExpansionRenderer.argb(p.opacity()*alpha,p.color())));
                continue;
            }
            double radius = p.radius() * (frame.style() == AmbientField.Style.CRYSTALS ? 2.8 : 5.5);
            double ux = frame.right().x * radius, uy = frame.right().y * radius, uz = frame.right().z * radius;
            double vx = frame.up().x * radius, vy = frame.up().y * radius, vz = frame.up().z * radius;
            Vec3d center = p.position();
            int color = VisualExpansionRenderer.argb(p.opacity() * (frame.style() == AmbientField.Style.CRYSTALS ? .15F : .48F), p.color());
            vertex(glow,matrix,(center.x-ux)-vx,(center.y-uy)-vy,(center.z-uz)-vz,camera,0,0,0,0,0,color);
            vertex(glow,matrix,(center.x+ux)-vx,(center.y+uy)-vy,(center.z+uz)-vz,camera,1,0,0,0,0,color);
            vertex(glow,matrix,(center.x+ux)+vx,(center.y+uy)+vy,(center.z+uz)+vz,camera,1,1,0,0,0,color);
            vertex(glow,matrix,(center.x-ux)+vx,(center.y-uy)+vy,(center.z-uz)+vz,camera,0,1,0,0,0,color);
        }
        if (context.consumers() instanceof VertexConsumerProvider.Immediate immediate) immediate.draw(GLOW);
    }

    private static void coreVertex(VertexConsumer consumer, MatrixStack.Entry matrix, double[] geometry, int index,
                                   Vec3d camera, float u, float v, int color) {
        int offset = index * 3;
        vertex(consumer, matrix, geometry[offset], geometry[offset + 1], geometry[offset + 2], camera,
            u, v, geometry[18], geometry[19], geometry[20], color);
    }

    private static void leafFace(VertexConsumer consumer, MatrixStack.Entry matrix, double[] geometry,
                                 int a, int b, int c, Vec3d camera, int color) {
        AmbientGeometry.leafNormal(geometry, a, b, c);
        leafVertex(consumer, matrix, geometry, a, camera, color);
        leafVertex(consumer, matrix, geometry, b, camera, color);
        leafVertex(consumer, matrix, geometry, c, camera, color);
    }

    private static void leafVertex(VertexConsumer consumer, MatrixStack.Entry matrix, double[] geometry,
                                   int index, Vec3d camera, int color) {
        int offset = index * 3, normal = AmbientGeometry.LEAF_NORMAL_OFFSET;
        vertex(consumer, matrix, geometry[offset], geometry[offset + 1], geometry[offset + 2], camera,
            AmbientGeometry.leafU(index), AmbientGeometry.leafV(index),
            geometry[normal], geometry[normal + 1], geometry[normal + 2], color);
    }

    private static void vertex(VertexConsumer consumer, MatrixStack.Entry matrix, double x, double y, double z,
                               Vec3d camera, float u, float v, double nx, double ny, double nz, int color) {
        consumer.vertex(matrix, (float)(x-camera.x), (float)(y-camera.y), (float)(z-camera.z))
            .texture(u,v).color(color).normal((float)nx,(float)ny,(float)nz);
    }
}
