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

import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Whole-model chams: resolve all faces against private depth, then blend once.
 *
 * <p>Neither overlapping body/clothing faces nor hidden joint faces can accumulate
 * opacity. Every part of one Echo has exactly the same unlit RGBA. Historical
 * Echoes can still have different colors and fades. The final material adds a
 * silhouette-only neon rim and continuous aurora material without changing
 * coverage. The union is independent of terrain depth.</p>
 */
public final class UnifiedChamsRenderer {
    private static final int MAX_ECHOES = 48;
    private static final int MAX_VERTEX_BYTES = MAX_ECHOES * PlayerModelSnapshot.MAX_VERTICES
        * VertexFormats.POSITION_TEXTURE_COLOR_NORMAL.getVertexSize();

    private static final RenderPipeline SILHOUETTE = dev.c4lam.celeste.compat.BetaRenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET)
            .withLocation(id("pipeline/unified_chams_fill"))
            .withVertexShader(id("core/unified_chams_fill"))
            .withFragmentShader(id("core/unified_chams_fill"))
            .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR_NORMAL, VertexFormat.DrawMode.QUADS)
            .withoutBlend()
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withDepthWrite(true).withColorWrite(true).withCull(false).build());

    private static final RenderPipeline COMPOSITE = dev.c4lam.celeste.compat.BetaRenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(id("pipeline/unified_chams_composite"))
            .withVertexShader(id("core/unified_chams_composite"))
            .withFragmentShader(id("core/unified_chams_composite"))
            .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
            .withSampler("InSampler")
            .withSampler("EnergySampler")
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false).withColorWrite(true).withCull(false).build());

    private static BufferAllocator allocator;
    private static GpuBuffer vertices;
    private static SimpleFramebuffer silhouette;
    private static Object loadedModels;
    private static boolean initialized;

    private UnifiedChamsRenderer() { }

    /** Call once during client visual initialization; this does not allocate GPU storage. */
    public static void initialize() {
        if (initialized) return;
        initialized = true;
        BetaSubscriptions.register(ClientLifecycleEvents.CLIENT_STOPPING, "celeste:unified_chams/stopping",
            ClientLifecycleEvents.ClientStopping.class, client -> requestRelease(), ClientLifecycleEvents.CLIENT_STOPPING::register);
        BetaSubscriptions.register(ClientPlayConnectionEvents.DISCONNECT, "celeste:unified_chams/disconnect",
            ClientPlayConnectionEvents.Disconnect.class, (handler, client) -> requestRelease(), ClientPlayConnectionEvents.DISCONNECT::register);
        ResourceLoader resources = ResourceLoader.get(ResourceType.CLIENT_RESOURCES);
        BetaSubscriptions.register(resources, "celeste:unified_chams", SynchronousResourceReloader.class,
            manager -> requestRelease(), proxy -> resources.registerReloader(id("unified_chams"), proxy));
    }

    /**
     * Draws immediately in the caller's world-render phase. The supplied frame is
     * immutable and drawing never queries or changes a live player entity.
     */
    public static void draw(WorldRenderContext context, PlayerEchoRenderer.Frame frame) {
        draw(context, frame, false);
    }

    /**
     * Fake Lag may prioritize its principal projection above its faint trail.
     * This is still one final composite: only the principal gets a fresh PRIVATE
     * depth resolve so a nearer low-alpha trail cannot erase its stronger fill.
     */
    public static void draw(WorldRenderContext context, PlayerEchoRenderer.Frame frame,
                            boolean prioritizePrincipal) {
        if (context == null || frame == null || frame.isEmpty() || context.matrices() == null
            || context.worldState() == null || context.worldState().cameraRenderState == null) return;
        Vec3d camera = context.worldState().cameraRenderState.pos;
        if (!finite(camera)) return;
        RenderSystem.assertOnRenderThread();

        MinecraftClient client = MinecraftClient.getInstance();
        Framebuffer main = client.getFramebuffer();
        if (main == null) return;
        // Match vanilla RenderLayer's active-color override, but never use its
        // depth override: the only attached depth texture below is our own.
        GpuTextureView target = RenderSystem.outputColorTextureOverride != null
            ? RenderSystem.outputColorTextureOverride : main.getColorAttachmentView();
        if (target == null || target.isClosed()) return;
        int width = target.getWidth(0), height = target.getHeight(0);
        if (width < 1 || height < 1) return;
        GpuTextureView energyTexture = client.getTextureManager()
            .getTexture(id("textures/visual/plasma_flow.png")).getGlTextureView();
        if (energyTexture == null || energyTexture.isClosed()) return;

        Object currentModels = client.getLoadedEntityModels();
        if (loadedModels != currentModels) {
            releaseResources();
            loadedModels = currentModels;
        }
        if (allocator == null) allocator = new BufferAllocator(MAX_VERTEX_BYTES);
        try {
            BufferBuilder builder = new BufferBuilder(allocator, VertexFormat.DrawMode.QUADS,
                VertexFormats.POSITION_TEXTURE_COLOR_NORMAL);
            MatrixStack.Entry matrix = context.matrices().peek();
            int count = Math.min(MAX_ECHOES, frame.echoes().size());
            int principal = prioritizePrincipal ? principalIndex(frame, count) : -1;
            int trailVertices = 0;
            for (int index = 0; index < count; index++) {
                if (index == principal) continue;
                PlayerEchoRenderer.Echo echo = frame.echoes().get(index);
                if (!drawable(echo)) continue;
                // Identical endpoint colors deliberately remove any per-part
                // interpolation differences as well as the old normal-based rim.
                echo.mesh().emit(builder, matrix, camera, echo.position(), echo.yaw(),
                    echo.bottomColor(), echo.bottomColor(), echo.alpha(), echo.age());
                trailVertices += echo.mesh().vertexCount();
            }
            if (principal >= 0) {
                PlayerEchoRenderer.Echo echo = frame.echoes().get(principal);
                echo.mesh().emit(builder, matrix, camera, echo.position(), echo.yaw(),
                    echo.bottomColor(), echo.bottomColor(), echo.alpha(), echo.age());
            }
            try (BuiltBuffer built = builder.endNullable()) {
                if (built == null) return;
                ensureTarget(width, height);
                CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
                int bytes = built.getDrawParameters().vertexCount()
                    * VertexFormats.POSITION_TEXTURE_COLOR_NORMAL.getVertexSize();
                if (bytes > MAX_VERTEX_BYTES) throw new IllegalStateException("Chams geometry exceeded its fixed budget");
                encoder.writeToBuffer(vertices.slice(0, bytes), built.getBuffer());

                RenderSystem.ShapeIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.DrawMode.QUADS);
                GpuBuffer indexBuffer = indices.getIndexBuffer(built.getDrawParameters().indexCount());
                GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().write(
                    RenderSystem.getModelViewMatrix(), new Vector4f(1F, 1F, 1F, 1F),
                    new Vector3f(), new Matrix4f());
                boolean separatePrincipal = principal >= 0 && trailVertices > 0;
                int trailIndices = trailVertices / 4 * 6;

                // Clear once for the WHOLE frame, not once per ModelPart/Echo.
                // Opaque replacement plus private depth makes one covered pixel
                // store one RGBA even where many limbs and clothing layers meet.
                try (RenderPass pass = encoder.createRenderPass(
                    () -> "Celeste unified chams silhouette", silhouette.getColorAttachmentView(),
                    OptionalInt.of(0), silhouette.getDepthAttachmentView(), OptionalDouble.of(1D))) {
                    pass.setPipeline(SILHOUETTE);
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("DynamicTransforms", transforms);
                    pass.setVertexBuffer(0, vertices);
                    pass.setIndexBuffer(indexBuffer, indices.getIndexType());
                    pass.drawIndexed(0, 0, separatePrincipal ? trailIndices
                        : built.getDrawParameters().indexCount(), 1);
                }

                if (separatePrincipal) {
                    // Keep the trail color, clear only our private depth. The
                    // principal remains internally depth-resolved, but no faint
                    // trail in front of it can replace its stronger RGBA.
                    try (RenderPass pass = encoder.createRenderPass(
                        () -> "Celeste unified chams principal", silhouette.getColorAttachmentView(),
                        OptionalInt.empty(), silhouette.getDepthAttachmentView(), OptionalDouble.of(1D))) {
                        pass.setPipeline(SILHOUETTE);
                        RenderSystem.bindDefaultUniforms(pass);
                        pass.setUniform("DynamicTransforms", transforms);
                        pass.setVertexBuffer(0, vertices);
                        pass.setIndexBuffer(indexBuffer, indices.getIndexType());
                        // Shape indices start at zero; the base vertex selects
                        // the principal range appended after all trail vertices.
                        pass.drawIndexed(trailVertices, 0,
                            built.getDrawParameters().indexCount() - trailIndices, 1);
                    }
                }

                // No external depth attachment, clear, copy, write or global
                // framebuffer/projection override. Alpha is applied exactly once.
                try (RenderPass pass = encoder.createRenderPass(
                    () -> "Celeste unified chams composite", target, OptionalInt.empty())) {
                    pass.setPipeline(COMPOSITE);
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.bindTexture("InSampler", silhouette.getColorAttachmentView(),
                        RenderSystem.getSamplerCache().get(FilterMode.NEAREST));
                    pass.bindTexture("EnergySampler", energyTexture,
                        RenderSystem.getSamplerCache().get(FilterMode.LINEAR, true));
                    pass.draw(0, 3);
                }
            }
        } finally {
            allocator.clear();
        }
    }

    private static void ensureTarget(int width, int height) {
        if (silhouette == null) silhouette = new SimpleFramebuffer("Celeste unified chams", width, height, true);
        else if (silhouette.textureWidth != width || silhouette.textureHeight != height) silhouette.resize(width, height);
        if (vertices == null) vertices = RenderSystem.getDevice().createBuffer(
            () -> "Celeste unified chams vertices", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, MAX_VERTEX_BYTES);
    }

    /** Idempotent render-thread cleanup; the next nonempty draw recreates storage lazily. */
    public static void releaseResources() {
        RenderSystem.assertOnRenderThread();
        SimpleFramebuffer previousSilhouette = silhouette;
        GpuBuffer previousVertices = vertices;
        BufferAllocator previousAllocator = allocator;
        silhouette = null;
        vertices = null;
        allocator = null;
        loadedModels = null;
        try {
            if (previousSilhouette != null) previousSilhouette.delete();
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
        else MinecraftClient.getInstance().execute(UnifiedChamsRenderer::releaseResources);
    }

    private static boolean finite(Vec3d position) {
        return position != null && Double.isFinite(position.x) && Double.isFinite(position.y)
            && Double.isFinite(position.z);
    }

    private static boolean drawable(PlayerEchoRenderer.Echo echo) {
        return echo != null && echo.mesh() != null && Float.isFinite(echo.alpha())
            && echo.alpha() > .003F && finite(echo.position()) && Float.isFinite(echo.yaw());
    }

    private static int principalIndex(PlayerEchoRenderer.Frame frame, int count) {
        int principal = -1;
        float strongest = -1F;
        for (int index = 0; index < count; index++) {
            PlayerEchoRenderer.Echo echo = frame.echoes().get(index);
            if (echo.age() == 0F && drawable(echo) && echo.alpha() > strongest) {
                principal = index;
                strongest = echo.alpha();
            }
        }
        return principal;
    }

    private static Identifier id(String path) { return Identifier.of("celeste", path); }
}
