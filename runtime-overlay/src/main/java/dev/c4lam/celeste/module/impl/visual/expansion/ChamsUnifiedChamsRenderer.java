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
public final class ChamsUnifiedChamsRenderer {
    private static final int MAX_ECHOES = 48;
    private static final int MAX_VERTEX_BYTES = (MAX_ECHOES * ChamsPlayerModelSnapshot.MAX_VERTICES + 12 * ChamsPlayerModelSnapshot.MAX_EQUIPPED_VERTICES)
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

    private static final RenderPipeline GLOW_MASK = dev.c4lam.celeste.compat.BetaRenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET)
            .withLocation(id("pipeline/chams_glow_mask"))
            .withVertexShader(id("core/chams_glow_mask"))
            .withFragmentShader(id("core/chams_glow_mask"))
            .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR_NORMAL, VertexFormat.DrawMode.QUADS)
            .withSampler("SkinSampler").withoutBlend()
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withDepthWrite(true).withColorWrite(true).withCull(false).build());

    private record SkinDraw(int firstVertex, int vertices, GpuTextureView texture) { }

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

    private static final RenderPipeline GLOW_BLUR = glowPipeline(false);
    private static BufferAllocator allocator;
    private static final RenderPipeline[] MATERIALS = {
        materialPipeline(0), materialPipeline(1), materialPipeline(2), glowPipeline(true)
    };
    private static GpuBuffer vertices;
    private static SimpleFramebuffer silhouette;
    private static SimpleFramebuffer glowBlur;
    private static Object loadedModels;
    private static boolean initialized;

    private ChamsUnifiedChamsRenderer() { }

    private static RenderPipeline materialPipeline(int style) {
        return dev.c4lam.celeste.compat.BetaRenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/chams_material_" + style))
                .withVertexShader(id("core/unified_chams_composite"))
                .withFragmentShader(id("core/chams_material"))
                .withShaderDefine("CHAMS_MATERIAL", style)
                .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
                .withSampler("InSampler").withBlend(BlendFunction.TRANSLUCENT)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false).withColorWrite(true).withCull(false).build());
    }

    private static RenderPipeline glowPipeline(boolean composite) {
        String shader = composite ? "chams_glow" : "chams_glow_blur";
        var builder = RenderPipeline.builder()
            .withLocation(id("pipeline/" + shader))
            .withVertexShader(id("core/unified_chams_composite"))
            .withFragmentShader(id("core/" + shader))
            .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
            .withSampler("InSampler")
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false).withColorWrite(true).withCull(false);
        if (composite) builder.withSampler("GlowSampler").withBlend(BlendFunction.TRANSLUCENT);
        else builder.withoutBlend();
        return dev.c4lam.celeste.compat.BetaRenderPipelines.register(builder.build());
    }

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
            manager -> requestRelease(), proxy -> resources.registerReloader(dev.c4lam.celeste.module.delivery.ModuleResourceNames.reloader(id("unified_chams")), proxy));
    }

    /**
     * Draws immediately in the caller's world-render phase. The supplied frame is
     * immutable and drawing never queries or changes a live player entity.
     */
    public static void draw(WorldRenderContext context, ChamsPlayerEchoRenderer.Frame frame) {
        draw(context, frame, false);
    }

    /**
     * Fake Lag may prioritize its principal projection above its faint trail.
     * This is still one final composite: only the principal gets a fresh PRIVATE
     * depth resolve so a nearer low-alpha trail cannot erase its stronger fill.
     */
    public static void draw(WorldRenderContext context, ChamsPlayerEchoRenderer.Frame frame,
                            boolean prioritizePrincipal) {
        draw(context, frame, prioritizePrincipal, -1);
    }

    public static void draw(WorldRenderContext context, ChamsPlayerEchoRenderer.Frame frame,
                            boolean prioritizePrincipal, int material) {
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
        boolean exteriorGlow = material >= MATERIALS.length - 1;
        boolean texturedMask = exteriorGlow || frame.echoes().stream().anyMatch(echo -> echo.mesh().hasEquipment());
        GpuTextureView energyTexture = material < 0 ? client.getTextureManager()
            .getTexture(id("textures/visual/plasma_flow.png")).getGlTextureView() : null;
        if (material < 0 && (energyTexture == null || energyTexture.isClosed())) return;

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
            int principal = prioritizePrincipal && !texturedMask ? principalIndex(frame, count) : -1;
            int trailVertices = 0;
            java.util.ArrayList<SkinDraw> skinDraws = texturedMask ? new java.util.ArrayList<>(count) : null;
            for (int index = 0; index < count; index++) {
                if (index == principal) continue;
                ChamsPlayerEchoRenderer.Echo echo = frame.echoes().get(index);
                if (!drawable(echo)) continue;
                // Identical endpoint colors deliberately remove any per-part
                // interpolation differences as well as the old normal-based rim.
                if (texturedMask) {
                    var ranges = echo.mesh().textureRanges();
                    if (ranges.isEmpty()) {
                        Identifier skin = echo.mesh().skinTexture();
                        if (skin == null) continue;
                        ranges = java.util.List.of(new ChamsPlayerModelSnapshot.TextureRange(0, echo.mesh().vertexCount(), skin));
                    }
                    echo.mesh().emitSkinMask(builder, matrix, camera, echo.position(), echo.yaw(),
                        echo.bottomColor(), echo.alpha());
                    for (var range : ranges) {
                        GpuTextureView texture = client.getTextureManager().getTexture(range.texture()).getGlTextureView();
                        if (texture != null && !texture.isClosed())
                            skinDraws.add(new SkinDraw(trailVertices + range.firstVertex(), range.vertices(), texture));
                    }
                } else {
                    echo.mesh().emit(builder, matrix, camera, echo.position(), echo.yaw(),
                        echo.bottomColor(), echo.bottomColor(), echo.alpha(), echo.age());
                }
                trailVertices += echo.mesh().vertexCount();
            }
            if (principal >= 0) {
                ChamsPlayerEchoRenderer.Echo echo = frame.echoes().get(principal);
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
                    pass.setPipeline(texturedMask ? GLOW_MASK : SILHOUETTE);
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("DynamicTransforms", transforms);
                    pass.setVertexBuffer(0, vertices);
                    pass.setIndexBuffer(indexBuffer, indices.getIndexType());
                    if (texturedMask) {
                        // One shared mask/depth target, bounded skin/equipment draws.
                        // Transparent clothing texels discard before depth writes.
                        for (SkinDraw draw : skinDraws) {
                            pass.bindTexture("SkinSampler", draw.texture(),
                                RenderSystem.getSamplerCache().get(FilterMode.NEAREST));
                            pass.drawIndexed(draw.firstVertex(), 0, draw.vertices() / 4 * 6, 1);
                        }
                    } else {
                        pass.drawIndexed(0, 0, separatePrincipal ? trailIndices
                            : built.getDrawParameters().indexCount(), 1);
                    }
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

                if (exteriorGlow) {
                    int blurWidth = (width + 1) / 2, blurHeight = (height + 1) / 2;
                    if (glowBlur == null) glowBlur = new SimpleFramebuffer("Celeste chams glow", blurWidth, blurHeight, false);
                    else if (glowBlur.textureWidth != blurWidth || glowBlur.textureHeight != blurHeight)
                        glowBlur.resize(blurWidth, blurHeight);
                    // Blur only the resolved posed silhouette, once for the entire frame.
                    // Half-resolution storage keeps the broad, smooth bloom bounded.
                    try (RenderPass pass = encoder.createRenderPass(
                        () -> "Celeste chams glow blur", glowBlur.getColorAttachmentView(), OptionalInt.of(0))) {
                        pass.setPipeline(GLOW_BLUR);
                        pass.bindTexture("InSampler", silhouette.getColorAttachmentView(),
                            RenderSystem.getSamplerCache().get(FilterMode.LINEAR));
                        pass.draw(0, 3);
                    }
                }

                // No external depth attachment, clear, copy, write or global
                // framebuffer/projection override. Alpha is applied exactly once.
                try (RenderPass pass = encoder.createRenderPass(
                    () -> "Celeste unified chams composite", target, OptionalInt.empty())) {
                    pass.setPipeline(material < 0 ? COMPOSITE : MATERIALS[Math.min(material, MATERIALS.length - 1)]);
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.bindTexture("InSampler", silhouette.getColorAttachmentView(),
                        RenderSystem.getSamplerCache().get(exteriorGlow ? FilterMode.LINEAR : FilterMode.NEAREST));
                    if (exteriorGlow) pass.bindTexture("GlowSampler", glowBlur.getColorAttachmentView(),
                        RenderSystem.getSamplerCache().get(FilterMode.LINEAR));
                    if (energyTexture != null) pass.bindTexture("EnergySampler", energyTexture,
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
        SimpleFramebuffer previousGlow = glowBlur;
        GpuBuffer previousVertices = vertices;
        BufferAllocator previousAllocator = allocator;
        silhouette = null;
        glowBlur = null;
        vertices = null;
        allocator = null;
        loadedModels = null;
        try {
            if (previousSilhouette != null) previousSilhouette.delete();
        } finally {
            try { if (previousGlow != null) previousGlow.delete(); }
            finally {
                try { if (previousVertices != null) previousVertices.close(); }
                finally { if (previousAllocator != null) previousAllocator.close(); }
            }
        }
    }

    private static void requestRelease() {
        if (RenderSystem.isOnRenderThread()) releaseResources();
        else dev.c4lam.celeste.module.delivery.ModuleScope.executeCurrent(MinecraftClient.getInstance()::execute, ChamsUnifiedChamsRenderer::releaseResources);
    }

    private static boolean finite(Vec3d position) {
        return position != null && Double.isFinite(position.x) && Double.isFinite(position.y)
            && Double.isFinite(position.z);
    }

    private static boolean drawable(ChamsPlayerEchoRenderer.Echo echo) {
        return echo != null && echo.mesh() != null && Float.isFinite(echo.alpha())
            && echo.alpha() > .003F && finite(echo.position()) && Float.isFinite(echo.yaw());
    }

    private static int principalIndex(ChamsPlayerEchoRenderer.Frame frame, int count) {
        int principal = -1;
        float strongest = -1F;
        for (int index = 0; index < count; index++) {
            ChamsPlayerEchoRenderer.Echo echo = frame.echoes().get(index);
            if (echo.age() == 0F && drawable(echo) && echo.alpha() > strongest) {
                principal = index;
                strongest = echo.alpha();
            }
        }
        return principal;
    }

    private static Identifier id(String path) { return dev.c4lam.celeste.module.delivery.ModuleResourceNames.asset(Identifier.of("celeste", path)); }
}
