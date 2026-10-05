package dev.c4lam.celeste.module.impl.visual.expansion;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.c4lam.celeste.auth.AuthBootstrap;
import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;
import dev.c4lam.celeste.compat.RenderCompatibility;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.block.enums.CameraSubmersionType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.SynchronousResourceReloader;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;

import java.util.OptionalInt;

/** Applies ambient color to every visible terrain face before entities, preserving texture and lighting. */
public final class GroundShadersRenderer {
    private static final Logger LOGGER = dev.c4lam.celeste.compat.RuntimeLogging.getLogger("Celeste/GroundShaders");
    private static final RenderPipeline PIPELINE = dev.c4lam.celeste.compat.BetaRenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.TRANSFORMS_PROJECTION_FOG_SNIPPET)
            .withLocation(id("pipeline/ground_shaders"))
            .withVertexShader(id("core/ground_shaders"))
            .withFragmentShader(id("core/ground_shaders"))
            .withSampler("SceneDepth")
            .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
            .withBlend(new BlendFunction(SourceFactor.DST_COLOR, DestFactor.ZERO, SourceFactor.ZERO, DestFactor.ONE))
            .withCull(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false).withColorWrite(true, false).build());
    private static final RenderStateDataKey<Frame> FRAME = RenderStateDataKey.create(() -> "celeste_ground_shaders");
    private static GpuTexture depth;
    private static GpuTextureView depthView;
    private static volatile long generation;
    private static boolean initialized, warned;

    private record Frame(Object worldToken, long generation, GroundShadersMath.Settings options,
                         Matrix4f inverseView) { }

    private GroundShadersRenderer() { }

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        BetaSubscriptions.register(WorldRenderEvents.END_EXTRACTION, "celeste:ground_shaders/extract",
            WorldRenderEvents.EndExtraction.class, GroundShadersRenderer::extract, WorldRenderEvents.END_EXTRACTION::register);
        // At this hook depth contains solid/cutout terrain only. Entities, block
        // entities, translucent water/glass, particles and hands are rendered later.
        BetaSubscriptions.register(WorldRenderEvents.BEFORE_ENTITIES, "celeste:ground_shaders/draw",
            WorldRenderEvents.BeforeEntities.class, GroundShadersRenderer::draw, WorldRenderEvents.BEFORE_ENTITIES::register);
        BetaSubscriptions.register(ClientLifecycleEvents.CLIENT_STOPPING, "celeste:ground_shaders/stopping",
            ClientLifecycleEvents.ClientStopping.class, client -> reset(), ClientLifecycleEvents.CLIENT_STOPPING::register);
        BetaSubscriptions.register(ClientPlayConnectionEvents.DISCONNECT, "celeste:ground_shaders/disconnect",
            ClientPlayConnectionEvents.Disconnect.class, (handler, client) -> reset(), ClientPlayConnectionEvents.DISCONNECT::register);
        ResourceLoader resources = ResourceLoader.get(ResourceType.CLIENT_RESOURCES);
        BetaSubscriptions.register(resources, "celeste:ground_shaders/reload", SynchronousResourceReloader.class,
            manager -> reset(), proxy -> resources.registerReloader(id("ground_shaders"), proxy));
    }

    private static void extract(WorldExtractionContext context) {
        if (context == null || context.worldState() == null) return;
        context.worldState().setData(FRAME, null);
        GroundShadersFeature.Options active = GroundShadersFeature.options(context.world());
        if (active == null || active.settings().intensity() <= 0 || RenderCompatibility.isShaderPackActive()
            || !allowed(context.camera(), context.world()) || context.viewMatrix() == null) return;
        var position = context.camera().getCameraPos();
        if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)
            || Math.abs(position.y) > 32768) return;
        // Native terrain fog uses world-relative distances. Retain view-bob/hurt
        // transforms so camera motion cannot shift that fog boundary.
        Matrix4f inverseView = new Matrix4f(context.viewMatrix());
        float determinant = inverseView.determinant();
        if (!inverseView.isFinite() || !Float.isFinite(determinant) || Math.abs(determinant) < 1.0E-8F) return;
        inverseView.invert();
        if (!inverseView.isFinite()) return;
        GroundShadersMath.Settings options = active.settings();
        context.worldState().setData(FRAME, new Frame(active.worldToken(), generation, options, inverseView));
    }

    private static boolean isCurrent(Frame frame) {
        MinecraftClient client = MinecraftClient.getInstance();
        GroundShadersFeature.Options active = GroundShadersFeature.options(client.world);
        return frame != null && client.player != null && active != null
            && GroundShadersMath.currentFrame(frame.generation, generation, frame.worldToken,
                active.worldToken(), active.settings());
    }

    /** Respect impaired vision and submerged cameras in every dimension. */
    private static boolean allowed(Camera camera, ClientWorld world) {
        if (camera == null || world == null || !camera.isReady() || RenderCompatibility.isShadowPass()) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != world || client.player == null || !AuthBootstrap.authorized()) return false;
        Entity focused = camera.getFocusedEntity();
        if (focused == null || focused.getEntityWorld() != world) return false;
        CameraSubmersionType medium = camera.getSubmersionType();
        if (medium != CameraSubmersionType.NONE && medium != CameraSubmersionType.ATMOSPHERIC) return false;
        if (focused instanceof LivingEntity living
            && (living.hasStatusEffect(StatusEffects.BLINDNESS) || living.hasStatusEffect(StatusEffects.DARKNESS))) return false;
        if (client.inGameHud.getBossBarHud().shouldThickenFog()) return false;
        float voidRange = world.getLevelProperties().getVoidDarknessRange();
        return voidRange <= 0 || camera.getCameraPos().y >= world.getBottomY() + voidRange;
    }

    private static void draw(WorldRenderContext context) {
        if (context == null || context.worldState() == null || RenderCompatibility.isShadowPass()
            || RenderCompatibility.isShaderPackActive() || !AuthBootstrap.authorized()) return;
        Frame frame = context.worldState().getData(FRAME);
        if (!isCurrent(frame) || RenderSystem.getProjectionMatrixBuffer() == null) return;
        RenderSystem.assertOnRenderThread();
        var main = MinecraftClient.getInstance().getFramebuffer();
        if (main == null) return;
        GpuTextureView target = RenderSystem.outputColorTextureOverride != null
            ? RenderSystem.outputColorTextureOverride : main.getColorAttachmentView();
        GpuTextureView sourceDepth = RenderSystem.outputDepthTextureOverride != null
            ? RenderSystem.outputDepthTextureOverride : main.getDepthAttachmentView();
        if (!usable(target) || !usable(sourceDepth) || sourceDepth.baseMipLevel() != 0
            || sourceDepth.texture().getDepthOrLayers() != 1
            || (sourceDepth.texture().usage() & GpuTexture.USAGE_COPY_SRC) == 0
            || target.getWidth(0) != sourceDepth.getWidth(0) || target.getHeight(0) != sourceDepth.getHeight(0)) return;
        try {
            if (!RenderSystem.getDevice().precompilePipeline(PIPELINE).isValid()) return;
            ensureDepth(sourceDepth);
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            // Never sample a texture still bound as a scene depth attachment.
            encoder.copyTextureToTexture(sourceDepth.texture(), depth, 0, 0, 0, 0, 0,
                sourceDepth.getWidth(0), sourceDepth.getHeight(0));
            GroundShadersMath.Settings options = frame.options;
            int rgb = options.color();
            // The shader outputs a color multiplier, retaining each terrain texel's
            // existing lighting. TextureMat converts view space for native fog.
            var transforms = RenderSystem.getDynamicUniforms().write(new Matrix4f(),
                new Vector4f((rgb >> 16 & 255) / 255F, (rgb >> 8 & 255) / 255F, (rgb & 255) / 255F, options.intensity()),
                new Vector3f(), frame.inverseView);
            // This color-only pass leaves both scene depth and destination alpha intact.
            try (RenderPass pass = encoder.createRenderPass(() -> "Celeste ground material", target, OptionalInt.empty())) {
                pass.setPipeline(PIPELINE);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transforms);
                pass.bindTexture("SceneDepth", depthView, RenderSystem.getSamplerCache().get(FilterMode.NEAREST));
                pass.draw(0, 3);
            }
            warned = false;
        } catch (RuntimeException failure) {
            if (!warned) {
                LOGGER.warn("Ground Shaders unavailable; preserving the world render", failure);
                warned = true;
            }
            releaseResources();
        }
    }

    private static boolean usable(GpuTextureView view) {
        return view != null && !view.isClosed() && !view.texture().isClosed()
            && view.getWidth(0) > 0 && view.getHeight(0) > 0;
    }

    private static void ensureDepth(GpuTextureView source) {
        if (depth != null && !depth.isClosed() && depthView != null && !depthView.isClosed()
            && depth.getWidth(0) == source.getWidth(0) && depth.getHeight(0) == source.getHeight(0)
            && depth.getFormat() == source.texture().getFormat()) return;
        releaseResources();
        depth = RenderSystem.getDevice().createTexture("Celeste ground terrain depth",
            GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
            source.texture().getFormat(), source.getWidth(0), source.getHeight(0), 1, 1);
        depthView = RenderSystem.getDevice().createTextureView(depth);
    }

    /** Disable, world changes, reload and unload invalidate every extracted frame. */
    public static void reset() {
        generation++;
        if (RenderSystem.isOnRenderThread()) releaseResources();
        else MinecraftClient.getInstance().execute(GroundShadersRenderer::releaseResources);
    }

    public static void releaseResources() {
        RenderSystem.assertOnRenderThread();
        GpuTextureView oldView = depthView;
        GpuTexture oldDepth = depth;
        depthView = null;
        depth = null;
        try { if (oldView != null && !oldView.isClosed()) oldView.close(); }
        finally { if (oldDepth != null && !oldDepth.isClosed()) oldDepth.close(); }
    }

    private static Identifier id(String path) { return Identifier.of("celeste", path); }
}
