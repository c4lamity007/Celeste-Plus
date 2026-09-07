package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.SynchronousResourceReloader;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Resolve solid visual surfaces against PRIVATE depth, then apply alpha once.
 * Hidden/back faces cannot accumulate opacity through a translucent model.
 * Scene depth is never written: depth-aware markers can copy it into private
 * storage. Global framebuffer overrides are never modified; the active color
 * target receives one composite.
 */
public final class UnifiedVolumeRenderer {
    private static final int MAX_QUADS = 768;
    private static final int MAX_VERTICES = MAX_QUADS * 4;
    private static final int MAX_VERTEX_BYTES = MAX_VERTICES * VertexFormats.POSITION_TEXTURE_COLOR_NORMAL.getVertexSize();

    private static final RenderPipeline SURFACE = dev.c4lam.celeste.compat.BetaRenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(id("pipeline/unified_volume_fill"))
            .withVertexShader(id("core/world_volume"))
            .withFragmentShader(id("core/world_volume"))
            .withSampler("Sampler0")
            .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR_NORMAL, VertexFormat.DrawMode.QUADS)
            .withoutBlend()
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withDepthWrite(true).withColorWrite(true).withCull(false).build());

    private static final RenderPipeline COMPOSITE = dev.c4lam.celeste.compat.BetaRenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(id("pipeline/unified_volume_composite"))
            .withVertexShader(id("core/unified_volume_composite"))
            .withFragmentShader(id("core/unified_volume_composite"))
            .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
            .withSampler("InSampler")
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false).withColorWrite(true).withCull(false).build());

    private static BufferAllocator allocator;
    private static GpuBuffer vertices;
    private static SimpleFramebuffer surface;
    private static boolean initialized;

    private UnifiedVolumeRenderer() { }

    /** Register lifecycle hooks once; GPU storage remains lazy until a nonempty frame. */
    public static void initialize() {
        if (initialized) return;
        initialized = true;
        BetaSubscriptions.register(ClientLifecycleEvents.CLIENT_STOPPING, "celeste:unified_volume/stopping",
            ClientLifecycleEvents.ClientStopping.class, client -> requestRelease(), ClientLifecycleEvents.CLIENT_STOPPING::register);
        BetaSubscriptions.register(ClientPlayConnectionEvents.DISCONNECT, "celeste:unified_volume/disconnect",
            ClientPlayConnectionEvents.Disconnect.class, (handler, client) -> requestRelease(), ClientPlayConnectionEvents.DISCONNECT::register);
        ResourceLoader resources = ResourceLoader.get(ResourceType.CLIENT_RESOURCES);
        BetaSubscriptions.register(resources, "celeste:unified_volume", SynchronousResourceReloader.class,
            manager -> requestRelease(), proxy -> resources.registerReloader(id("unified_volume"), proxy));
    }

    public static void draw(WorldRenderContext context, List<WorldGlowRenderer.Quad> quads) {
        draw(context, quads, false);
    }

    /** Optional terrain occlusion uses a private depth copy; never writes to scene depth. */
    public static void draw(WorldRenderContext context, List<WorldGlowRenderer.Quad> quads, boolean sceneOcclusion) {
        if (context == null || quads == null || quads.isEmpty() || context.matrices() == null
            || context.worldState() == null || context.worldState().cameraRenderState == null) return;
        Vec3d camera = context.worldState().cameraRenderState.pos;
        if (!finite(camera)) return;
        RenderSystem.assertOnRenderThread();

        // Limit inspection as well as allocation: this is the same first-768
        // batch window as WorldGlowRenderer, not 768 volumes after an unbounded scan.
        int count = Math.min(MAX_QUADS, quads.size());
        List<WorldGlowRenderer.Quad> selected = new ArrayList<>(count);
        List<VolumeSurfaceMesh.Face> faces = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            WorldGlowRenderer.Quad quad = quads.get(i);
            if (!drawable(quad, camera)) continue;
            selected.add(quad);
            faces.add(new VolumeSurfaceMesh.Face(point(quad.a()), point(quad.b()), point(quad.c()), point(quad.d())));
        }
        // Never composite a previous frame's private texture on an empty frame.
        if (selected.isEmpty()) return;
        List<VolumeSurfaceMesh.Vertex> smoothed = VolumeSurfaceMesh.smooth(faces);
        if (!validVertices(smoothed, selected.size() * 4, camera)) return;

        MinecraftClient client = MinecraftClient.getInstance();
        Framebuffer main = client.getFramebuffer();
        if (main == null) return;
        GpuTextureView target = RenderSystem.outputColorTextureOverride != null
            ? RenderSystem.outputColorTextureOverride : main.getColorAttachmentView();
        if (target == null || target.isClosed()) return;
        int width = target.getWidth(0), height = target.getHeight(0);
        if (width < 1 || height < 1) return;
        GpuTextureView sceneDepth = sceneOcclusion
            ? (RenderSystem.outputDepthTextureOverride != null
                ? RenderSystem.outputDepthTextureOverride : main.getDepthAttachmentView()) : null;
        if (sceneOcclusion && (sceneDepth == null || sceneDepth.isClosed()
            || sceneDepth.texture().isClosed() || sceneDepth.texture().getDepthOrLayers() != 1
            || sceneDepth.getWidth(0) != width || sceneDepth.getHeight(0) != height
            || sceneDepth.baseMipLevel() != 0
            || (sceneDepth.texture().usage() & GpuTexture.USAGE_COPY_SRC) == 0)) return;
        GpuTextureView material = client.getTextureManager()
            .getTexture(id("textures/visual/plasma_flow.png")).getGlTextureView();
        if (material == null || material.isClosed()) return;

        if (allocator == null) allocator = new BufferAllocator(MAX_VERTEX_BYTES);
        try {
            BufferBuilder builder = new BufferBuilder(allocator, VertexFormat.DrawMode.QUADS,
                VertexFormats.POSITION_TEXTURE_COLOR_NORMAL);
            MatrixStack.Entry matrix = context.matrices().peek();
            for (int i = 0; i < selected.size(); i++) {
                WorldGlowRenderer.Quad quad = selected.get(i);
                emit(builder, matrix, camera, smoothed.get(i * 4), quad.uStart(), 0F, quad.color());
                emit(builder, matrix, camera, smoothed.get(i * 4 + 1), quad.uEnd(), 0F, quad.endColor());
                emit(builder, matrix, camera, smoothed.get(i * 4 + 2), quad.uEnd(), 1F, quad.endColor());
                emit(builder, matrix, camera, smoothed.get(i * 4 + 3), quad.uStart(), 1F, quad.color());
            }
            try (BuiltBuffer built = builder.endNullable()) {
                if (built == null) return;
                int vertexCount = built.getDrawParameters().vertexCount();
                if (vertexCount > MAX_VERTICES) throw new IllegalStateException("Volume geometry exceeded its fixed budget");
                int bytes = vertexCount * VertexFormats.POSITION_TEXTURE_COLOR_NORMAL.getVertexSize();
                ensureTarget(width, height);
                CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
                if (sceneOcclusion) {
                    GpuTexture privateDepth = surface.getDepthAttachment();
                    if (privateDepth == null || privateDepth.getFormat() != sceneDepth.texture().getFormat()
                        || (privateDepth.usage() & GpuTexture.USAGE_COPY_DST) == 0) return;
                    encoder.copyTextureToTexture(sceneDepth.texture(), privateDepth,
                        0, 0, 0, 0, 0, width, height);
                }
                encoder.writeToBuffer(vertices.slice(0, bytes), built.getBuffer());
                RenderSystem.ShapeIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.DrawMode.QUADS);
                GpuBuffer indexBuffer = indices.getIndexBuffer(built.getDrawParameters().indexCount());
                GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().write(
                    RenderSystem.getModelViewMatrix(), new Vector4f(1F, 1F, 1F, 1F),
                    new Vector3f(), new Matrix4f());

                // One replacement/depth resolve for the whole volume batch.
                // Terrain-aware markers retain the copied depth; ordinary ESP clears private depth.
                try (RenderPass pass = encoder.createRenderPass(
                    () -> "Celeste unified volume surface", surface.getColorAttachmentView(),
                    OptionalInt.of(0), surface.getDepthAttachmentView(),
                    sceneOcclusion ? OptionalDouble.empty() : OptionalDouble.of(1D))) {
                    pass.setPipeline(SURFACE);
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("DynamicTransforms", transforms);
                    pass.bindTexture("Sampler0", material, RenderSystem.getSamplerCache().get(FilterMode.LINEAR, true));
                    pass.setVertexBuffer(0, vertices);
                    pass.setIndexBuffer(indexBuffer, indices.getIndexType());
                    pass.drawIndexed(0, 0, built.getDrawParameters().indexCount(), 1);
                }

                // Exactly one source-alpha blend. No external depth attachment,
                // clear/write or global color/depth/projection override.
                try (RenderPass pass = encoder.createRenderPass(
                    () -> "Celeste unified volume composite", target, OptionalInt.empty())) {
                    pass.setPipeline(COMPOSITE);
                    pass.bindTexture("InSampler", surface.getColorAttachmentView(),
                        RenderSystem.getSamplerCache().get(FilterMode.NEAREST));
                    pass.draw(0, 3);
                }
            }
        } finally {
            allocator.clear();
        }
    }

    private static void emit(BufferBuilder builder, MatrixStack.Entry matrix, Vec3d camera,
                             VolumeSurfaceMesh.Vertex vertex, float u, float v, int color) {
        VolumeSurfaceMesh.Point position = vertex.position();
        VolumeSurfaceMesh.Point normal = vertex.normal();
        builder.vertex(matrix, (float) (position.x() - camera.x), (float) (position.y() - camera.y),
                (float) (position.z() - camera.z)).texture(u, v).color(color)
            .normal(matrix, (float) normal.x(), (float) normal.y(), (float) normal.z());
    }

    private static void ensureTarget(int width, int height) {
        if (surface == null) surface = new SimpleFramebuffer("Celeste unified volume", width, height, true);
        else if (surface.textureWidth != width || surface.textureHeight != height) surface.resize(width, height);
        if (vertices == null) vertices = RenderSystem.getDevice().createBuffer(
            () -> "Celeste unified volume vertices", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, MAX_VERTEX_BYTES);
    }

    /** Idempotent render-thread cleanup; the next useful frame recreates storage. */
    public static void releaseResources() {
        RenderSystem.assertOnRenderThread();
        SimpleFramebuffer previousSurface = surface;
        GpuBuffer previousVertices = vertices;
        BufferAllocator previousAllocator = allocator;
        surface = null;
        vertices = null;
        allocator = null;
        try {
            if (previousSurface != null) previousSurface.delete();
        } finally {
            try {
                if (previousVertices != null) previousVertices.close();
            } finally {
                if (previousAllocator != null) previousAllocator.close();
            }
        }
    }

    private static void requestRelease() {
        if (RenderSystem.isOnRenderThread()) releaseResources();
        else MinecraftClient.getInstance().execute(UnifiedVolumeRenderer::releaseResources);
    }

    private static boolean drawable(WorldGlowRenderer.Quad quad, Vec3d camera) {
        return quad != null && quad.texture() == WorldGlowRenderer.Texture.VOLUME
            && ((quad.color() | quad.endColor()) >>> 24) != 0
            && Float.isFinite(quad.uStart()) && Float.isFinite(quad.uEnd())
            && relativeFinite(quad.a(), camera) && relativeFinite(quad.b(), camera)
            && relativeFinite(quad.c(), camera) && relativeFinite(quad.d(), camera);
    }

    private static boolean validVertices(List<VolumeSurfaceMesh.Vertex> vertices, int expected, Vec3d camera) {
        if (vertices == null || vertices.size() != expected || expected > MAX_VERTICES) return false;
        for (VolumeSurfaceMesh.Vertex vertex : vertices) {
            if (vertex == null || !finite(vertex.position()) || !finite(vertex.normal())) return false;
            VolumeSurfaceMesh.Point position = vertex.position();
            VolumeSurfaceMesh.Point normal = vertex.normal();
            if (!Float.isFinite((float) (position.x() - camera.x)) || !Float.isFinite((float) (position.y() - camera.y))
                || !Float.isFinite((float) (position.z() - camera.z)) || !Float.isFinite((float) normal.x())
                || !Float.isFinite((float) normal.y()) || !Float.isFinite((float) normal.z())) return false;
        }
        return true;
    }

    private static boolean relativeFinite(Vec3d position, Vec3d camera) {
        return finite(position) && Float.isFinite((float) (position.x - camera.x))
            && Float.isFinite((float) (position.y - camera.y)) && Float.isFinite((float) (position.z - camera.z));
    }

    private static boolean finite(Vec3d point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }

    private static boolean finite(VolumeSurfaceMesh.Point point) {
        return point != null && Double.isFinite(point.x()) && Double.isFinite(point.y()) && Double.isFinite(point.z());
    }

    private static VolumeSurfaceMesh.Point point(Vec3d point) { return new VolumeSurfaceMesh.Point(point.x, point.y, point.z); }
    private static Identifier id(String path) { return Identifier.of("celeste", path); }
}
