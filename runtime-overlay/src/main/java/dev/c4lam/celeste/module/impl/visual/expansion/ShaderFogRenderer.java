package dev.c4lam.celeste.module.impl.visual.expansion;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
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
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.SynchronousResourceReloader;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;

import java.util.OptionalInt;

/** One depth-aware atmosphere composite. No terrain/entity mixins or changes to scene depth. */
public final class ShaderFogRenderer {
    private static final Logger LOGGER = dev.c4lam.celeste.compat.RuntimeLogging.getLogger("Celeste/ShaderFog");
    private static final RenderPipeline PIPELINE = dev.c4lam.celeste.compat.BetaRenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET)
            .withLocation(id("pipeline/shader_fog"))
            .withVertexShader(id("core/shader_fog"))
            .withFragmentShader(id("core/shader_fog"))
            .withSampler("SceneDepth")
            .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
            .withBlend(BlendFunction.TRANSLUCENT).withCull(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false).withColorWrite(true).build());
    private static final dev.c4lam.celeste.module.delivery.ModuleFrame<Frame> FRAME = dev.c4lam.celeste.module.delivery.ModuleFrame.create();
    private static final CustomFogJumpCircleAnimation.Clock CLOCK = new CustomFogJumpCircleAnimation.Clock();
    private static GpuTexture depth;
    private static GpuTextureView depthView;
    private static volatile long generation;
    private static boolean initialized, warned;

    private record Frame(ClientWorld world, long generation, int mode, float density, float distance,
                         float phase, Vector3f camera, Matrix4f cameraRotation) { }

    private ShaderFogRenderer() { }

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        BetaSubscriptions.register(WorldRenderEvents.END_EXTRACTION, "celeste:shader_fog/extract",
            WorldRenderEvents.EndExtraction.class, ShaderFogRenderer::extract, WorldRenderEvents.END_EXTRACTION::register);
        // 1.21.11's END_MAIN follows terrain, entities and translucent terrain, but
        // precedes hand/HUD. It is shared by vanilla and Sodium, not a terrain mixin.
        BetaSubscriptions.register(WorldRenderEvents.END_MAIN, "celeste:shader_fog/draw",
            WorldRenderEvents.EndMain.class, ShaderFogRenderer::draw, WorldRenderEvents.END_MAIN::register);
        BetaSubscriptions.register(ClientLifecycleEvents.CLIENT_STOPPING, "celeste:shader_fog/stopping",
            ClientLifecycleEvents.ClientStopping.class, client -> reset(), ClientLifecycleEvents.CLIENT_STOPPING::register);
        BetaSubscriptions.register(ClientPlayConnectionEvents.DISCONNECT, "celeste:shader_fog/disconnect",
            ClientPlayConnectionEvents.Disconnect.class, (handler, client) -> reset(), ClientPlayConnectionEvents.DISCONNECT::register);
        ResourceLoader resources = ResourceLoader.get(ResourceType.CLIENT_RESOURCES);
        BetaSubscriptions.register(resources, "celeste:shader_fog/reload", SynchronousResourceReloader.class,
            manager -> reset(), proxy -> resources.registerReloader(dev.c4lam.celeste.module.delivery.ModuleResourceNames.reloader(id("shader_fog")), proxy));
    }

    private static void extract(WorldExtractionContext context) {
        if (context == null || context.worldState() == null) return;
        FRAME.set(context.worldState(), null);
        var world = context.world();
        CustomFogMath.Settings settings = CustomFogFeature.settings(world);
        if (settings == null || !settings.shader() || ShaderFogMath.density(settings.density()) <= 0
            || RenderCompatibility.isShaderPackActive() || !CustomFogRenderer.allowed(context.camera(), world)) return;
        var position = context.camera().getCameraPos();
        if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)
            || Math.abs(position.y) > 32768) return;
        Matrix4f rotation = new Matrix4f().rotation(context.camera().getRotation());
        if (!rotation.isFinite()) return;
        double elapsed = CLOCK.sample(System.nanoTime(), MinecraftClient.getInstance().isPaused());
        FRAME.set(context.worldState(), new Frame(world, generation,
            ShaderFogMath.mode(settings.shaderPreset(), (int) dev.c4lam.celeste.module.delivery.ModuleHooks.invoke("custom_sky", "sky.fog_mode", 0)),
            ShaderFogMath.density(settings.density()), ShaderFogMath.distance(settings.distance()),
            CustomFogSkyboxMath.animationPhase((long) (elapsed * 1_000_000_000), 0),
            new Vector3f(ShaderFogMath.worldCoordinate(position.x), (float) position.y,
                ShaderFogMath.worldCoordinate(position.z)), rotation));
    }

    private static void draw(WorldRenderContext context) {
        if (context == null || context.worldState() == null || RenderCompatibility.isShadowPass()
            || RenderCompatibility.isShaderPackActive() || !AuthBootstrap.authorized()) return;
        Frame frame = FRAME.get(context.worldState());
        MinecraftClient client = MinecraftClient.getInstance();
        if (frame == null || frame.generation != generation || client.world != frame.world
            || CustomFogFeature.settings(frame.world) == null || RenderSystem.getProjectionMatrixBuffer() == null) return;
        RenderSystem.assertOnRenderThread();
        var main = client.getFramebuffer();
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
            // A private sample texture also avoids an attachment feedback loop on
            // drivers that retain the previous main pass's depth attachment.
            encoder.copyTextureToTexture(sourceDepth.texture(), depth, 0, 0, 0, 0, 0,
                sourceDepth.getWidth(0), sourceDepth.getHeight(0));
            var transforms = RenderSystem.getDynamicUniforms().write(new Matrix4f(),
                new Vector4f(frame.mode, frame.density, frame.distance, frame.phase),
                frame.camera, frame.cameraRotation);
            try (RenderPass pass = encoder.createRenderPass(() -> "Celeste shader atmosphere", target, OptionalInt.empty())) {
                pass.setPipeline(PIPELINE);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transforms);
                pass.bindTexture("SceneDepth", depthView, RenderSystem.getSamplerCache().get(FilterMode.NEAREST));
                pass.draw(0, 3);
            }
            warned = false;
        } catch (RuntimeException failure) {
            if (!warned) {
                LOGGER.warn("Shader Fog unavailable; preserving the world render", failure);
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
        if (depth != null && !depth.isClosed() && depth.getWidth(0) == source.getWidth(0)
            && depth.getHeight(0) == source.getHeight(0) && depth.getFormat() == source.texture().getFormat()) return;
        releaseResources();
        depth = RenderSystem.getDevice().createTexture("Celeste shader fog depth",
            GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
            source.texture().getFormat(), source.getWidth(0), source.getHeight(0), 1, 1);
        depthView = RenderSystem.getDevice().createTextureView(depth);
    }

    /** Disable, disconnect, reload and payload unload all invalidate retained frames/storage. */
    public static void reset() {
        generation++;
        CLOCK.reset();
        if (RenderSystem.isOnRenderThread()) releaseResources();
        else dev.c4lam.celeste.module.delivery.ModuleScope.executeCurrent(MinecraftClient.getInstance()::execute, ShaderFogRenderer::releaseResources);
    }

    public static void releaseResources() {
        RenderSystem.assertOnRenderThread();
        GpuTextureView oldView = depthView;
        GpuTexture oldDepth = depth;
        depthView = null;
        depth = null;
        try { if (oldView != null) oldView.close(); }
        finally { if (oldDepth != null) oldDepth.close(); }
    }

    private static Identifier id(String path) { return Identifier.of("celeste", path); }
}
