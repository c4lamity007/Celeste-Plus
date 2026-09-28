package dev.c4lam.celeste.module.impl.visual.expansion;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;
import dev.c4lam.celeste.compat.BetaRenderPipelines;
import dev.celestebeta.host.VisualHookBridge.HandPass;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.OptionalInt;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.SynchronousResourceReloader;
import net.minecraft.util.Identifier;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One transparent, private-depth capture for the WHOLE deferred first-person item pass. */
public final class HandShadersRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Celeste/HandShaders");
    private static final long CLOCK_ORIGIN = System.nanoTime();
    private static final RenderPipeline BLUR = BetaRenderPipelines.register(base("hand_shaders_blur")
        .withSampler("InSampler").withUniform("BlurData", UniformType.UNIFORM_BUFFER).withoutBlend().build());
    private static final RenderPipeline COMPOSITE = BetaRenderPipelines.register(base("hand_shaders_composite")
        .withSampler("InSampler").withSampler("GlowSampler")
        .withUniform("HandShaderData", UniformType.UNIFORM_BUFFER).withBlend(BlendFunction.TRANSLUCENT).build());
    private static final RenderPipeline HISTORY = BetaRenderPipelines.register(base("hand_shaders_history")
        .withSampler("InSampler").withSampler("HistorySampler")
        .withUniform("HistoryData", UniformType.UNIFORM_BUFFER).withoutBlend().build());
    // Built-in vanilla shaders remain available when custom GLSL is replaced or invalid.
    // Captured transparent materials are already premultiplied, so RGB uses ONE.
    private static final RenderPipeline FALLBACK = BetaRenderPipelines.register(RenderPipeline.builder()
        .withLocation(id("pipeline/hand_shaders_fallback"))
        .withVertexShader("core/screenquad").withFragmentShader("core/blit_screen").withSampler("InSampler")
        .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
        .withBlend(new BlendFunction(SourceFactor.ONE, DestFactor.ONE_MINUS_SRC_ALPHA, SourceFactor.ZERO, DestFactor.ONE))
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false).withColorWrite(true, false).withCull(false).build());
    private static SimpleFramebuffer captured, blurHorizontal, blurVertical;
    private static SimpleFramebuffer fogHorizontal, fogVertical, historyRead, historyWrite;
    private static GpuBuffer parameters, horizontalParameters, verticalParameters, fogHorizontalParameters, fogVerticalParameters, historyParameters;
    private static Capture active;
    private static boolean initialized, validated, failed;
    private static boolean historyValid, historyNeedsClear = true, environmentSeen, observedEligible;
    private static long lastCaptureNanos;
    private static Object observedWorld, observedPlayer, observedScreen, observedMainItem, observedOffItem, observedArm;
    private static GpuTextureView observedTarget;
    private static HandShadersFeature.Settings observedSettings;

    private HandShadersRenderer() { }

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        BetaSubscriptions.register(ClientLifecycleEvents.CLIENT_STOPPING, "celeste:hand_shaders/stopping",
            ClientLifecycleEvents.ClientStopping.class, client -> requestRelease(), ClientLifecycleEvents.CLIENT_STOPPING::register);
        BetaSubscriptions.register(ClientPlayConnectionEvents.DISCONNECT, "celeste:hand_shaders/disconnect",
            ClientPlayConnectionEvents.Disconnect.class, (handler, client) -> requestRelease(), ClientPlayConnectionEvents.DISCONNECT::register);
        ResourceLoader resources = ResourceLoader.get(ResourceType.CLIENT_RESOURCES);
        BetaSubscriptions.register(resources, "celeste:hand_shaders", SynchronousResourceReloader.class,
            manager -> requestRelease(), proxy -> resources.registerReloader(id("hand_shaders"), proxy));
    }

    /** Called before queue submission; the lease must cover its later dispatcher and buffer flushes. */
    public static HandPass begin() {
        MinecraftClient client = MinecraftClient.getInstance();
        observeEnvironment();
        if (!observedEligible || failed || active != null) return null;
        RenderSystem.assertOnRenderThread();
        Framebuffer main = client.getFramebuffer();
        if (main == null) { invalidateHistory(); return null; }
        GpuTextureView target = RenderSystem.outputColorTextureOverride != null
            ? RenderSystem.outputColorTextureOverride : main.getColorAttachmentView();
        if (target == null || target.isClosed() || target.getWidth(0) < 1 || target.getHeight(0) < 1) {
            invalidateHistory();
            return null;
        }
        try {
            // Reject absent/broken resource-pack shaders BEFORE diverting a single vanilla draw.
            if (!validated) {
                var device = RenderSystem.getDevice();
                if (!device.precompilePipeline(BLUR).isValid() || !device.precompilePipeline(COMPOSITE).isValid()
                    || !device.precompilePipeline(HISTORY).isValid() || !device.precompilePipeline(FALLBACK).isValid()) {
                    throw new IllegalStateException("Hand shader pipeline unavailable");
                }
                validated = true;
            }
            ensureTargets(target.getWidth(0), target.getHeight(0));
            if (observedTarget != target) { invalidateHistory(); observedTarget = target; }
            long now = System.nanoTime();
            HandShadersFeature.Settings settings = HandShadersFeature.settings();
            double elapsed = (now - lastCaptureNanos) * 1.0e-9;
            // Flame stays attached to this frame. Smoke keeps its existing temporal flow.
            float retention = historyValid && settings.mode() != 3 ? HandFogTiming.retention(elapsed, false) : 0;
            if (retention == 0) invalidateHistory();
            if (historyNeedsClear) {
                var encoder = RenderSystem.getDevice().createCommandEncoder();
                encoder.clearColorTexture(historyRead.getColorAttachment(), 0);
                encoder.clearColorTexture(historyWrite.getColorAttachment(), 0);
                historyNeedsClear = false;
            }
            updateParameters(settings, target.getHeight(0), retention, elapsed);
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                captured.getColorAttachment(), 0, captured.getDepthAttachment(), 1.0);
            Capture scope = new Capture(RenderSystem.outputColorTextureOverride, RenderSystem.outputDepthTextureOverride, target,
                settings.mode() >= 2, now);
            active = scope;
            RenderSystem.outputColorTextureOverride = captured.getColorAttachmentView();
            RenderSystem.outputDepthTextureOverride = captured.getDepthAttachmentView();
            return scope;
        } catch (RuntimeException | LinkageError failure) {
            fail(failure);
            return null; // Original pass still targets vanilla; no invisible item fallback.
        }
    }

    private static final class Capture implements HandPass {
        private final GpuTextureView previousColor, previousDepth, target;
        private final boolean fog;
        private final long capturedAt;
        private boolean finished;
        private Capture(GpuTextureView color, GpuTextureView depth, GpuTextureView target, boolean fog, long capturedAt) {
            previousColor = color; previousDepth = depth; this.target = target;
            this.fog = fog; this.capturedAt = capturedAt;
        }
        private void restore() {
            if (finished) return;
            finished = true;
            RenderSystem.outputColorTextureOverride = previousColor;
            RenderSystem.outputDepthTextureOverride = previousDepth;
            if (active == this) active = null;
        }
        @Override public void finish(boolean success) {
            if (finished) return;
            // Restoration precedes all processing and also runs on thrown vanilla render calls.
            restore();
            if (!success || captured == null || target.isClosed()) { invalidateHistory(); return; }
            try {
                blur(captured.getColorAttachmentView(), blurHorizontal, horizontalParameters);
                blur(blurHorizontal.getColorAttachmentView(), blurVertical, verticalParameters);
                GpuTextureView glow = blurVertical.getColorAttachmentView();
                if (fog) {
                    blur(glow, fogHorizontal, fogHorizontalParameters);
                    blur(fogHorizontal.getColorAttachmentView(), fogVertical, fogVerticalParameters);
                    accumulateFog();
                    glow = historyRead.getColorAttachmentView();
                    historyValid = true;
                    lastCaptureNanos = capturedAt;
                }
                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "Celeste hand shader composite", target, OptionalInt.empty())) {
                    pass.setPipeline(COMPOSITE);
                    pass.setUniform("HandShaderData", parameters);
                    pass.bindTexture("InSampler", captured.getColorAttachmentView(), RenderSystem.getSamplerCache().get(FilterMode.NEAREST));
                    pass.bindTexture("GlowSampler", glow, RenderSystem.getSamplerCache().get(FilterMode.LINEAR));
                    pass.draw(0, 3);
                }
            } catch (RuntimeException | LinkageError failure) {
                fail(failure);
                // Vanilla blit shaders with premultiplied blending preserve translucent
                // item colors, while empty pixels never replace the world with black.
                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "Celeste original hand material fallback", target, OptionalInt.empty())) {
                    pass.setPipeline(FALLBACK);
                    pass.bindTexture("InSampler", captured.getColorAttachmentView(), RenderSystem.getSamplerCache().get(FilterMode.NEAREST));
                    pass.draw(0, 3);
                }
            }
        }
    }

    private static void accumulateFog() {
        // Never read and render into the same attachment. Only the spatially
        // blurred scalar silhouette enters history, not item colors or outline.
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            () -> "Celeste hand fog history", historyWrite.getColorAttachmentView(), OptionalInt.empty())) {
            pass.setPipeline(HISTORY);
            pass.setUniform("HistoryData", historyParameters);
            pass.bindTexture("InSampler", fogVertical.getColorAttachmentView(), RenderSystem.getSamplerCache().get(FilterMode.LINEAR));
            pass.bindTexture("HistorySampler", historyRead.getColorAttachmentView(), RenderSystem.getSamplerCache().get(FilterMode.LINEAR));
            pass.draw(0, 3);
        }
        SimpleFramebuffer swap = historyRead;
        historyRead = historyWrite;
        historyWrite = swap;
    }

    private static void blur(GpuTextureView source, SimpleFramebuffer destination, GpuBuffer data) {
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            () -> "Celeste hand silhouette blur", destination.getColorAttachmentView(), OptionalInt.empty())) {
            pass.setPipeline(BLUR);
            pass.setUniform("BlurData", data);
            pass.bindTexture("InSampler", source, RenderSystem.getSamplerCache().get(FilterMode.LINEAR));
            pass.draw(0, 3);
        }
    }

    private static void ensureTargets(int width, int height) {
        int smallWidth = Math.max(1, (width + 1) / 2), smallHeight = Math.max(1, (height + 1) / 2);
        if (captured != null && captured.textureWidth == width && captured.textureHeight == height) return;
        deleteTargets();
        captured = new SimpleFramebuffer("Celeste hand capture", width, height, true);
        blurHorizontal = new SimpleFramebuffer("Celeste hand blur H", smallWidth, smallHeight, false);
        blurVertical = new SimpleFramebuffer("Celeste hand blur V", smallWidth, smallHeight, false);
        int fogWidth = Math.max(1, (width + 3) / 4), fogHeight = Math.max(1, (height + 3) / 4);
        fogHorizontal = new SimpleFramebuffer("Celeste hand fog H", fogWidth, fogHeight, false);
        fogVertical = new SimpleFramebuffer("Celeste hand fog V", fogWidth, fogHeight, false);
        historyRead = new SimpleFramebuffer("Celeste hand fog history A", fogWidth, fogHeight, false);
        historyWrite = new SimpleFramebuffer("Celeste hand fog history B", fogWidth, fogHeight, false);
        float radiusScale = Math.clamp(height / 540F, 1F, 3F);
        horizontalParameters = buffer("Celeste hand blur H parameters", 3F * radiusScale / width, 0, 1, 0);
        verticalParameters = buffer("Celeste hand blur V parameters", 0, 3F * radiusScale / height, 0, 0);
        fogHorizontalParameters = buffer("Celeste hand fog H parameters", 1F / fogWidth, 0, 0, 3F * radiusScale);
        fogVerticalParameters = buffer("Celeste hand fog V parameters", 0, 1F / fogHeight, 0, 3F * radiusScale);
        historyParameters = buffer("Celeste hand history parameters", 0, 0, 0, 0);
        parameters = RenderSystem.getDevice().createBuffer(() -> "Celeste hand material", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 32);
    }

    private static void updateParameters(HandShadersFeature.Settings settings, int height, float retention, double elapsed) {
        ByteBuffer bytes = MemoryUtil.memAlloc(32).order(ByteOrder.nativeOrder());
        try {
            bytes.putFloat((settings.color() >> 16 & 255) / 255F).putFloat((settings.color() >> 8 & 255) / 255F)
                .putFloat((settings.color() & 255) / 255F).putFloat(settings.opacity());
            float seconds = (float) (((System.nanoTime() - CLOCK_ORIGIN) * 1.0e-9) % 3600.0);
            bytes.putFloat(settings.mode()).putFloat(settings.glow()).putFloat(Math.clamp(height / 540F, 1F, 3F)).putFloat(seconds).flip();
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            encoder.writeToBuffer(parameters.slice(), bytes);
            // Fog first removes high-frequency edges before the dense broad
            // quarter-resolution filter. Both exposed materials use this path.
            float blurStep = 1F;
            bytes.clear();
            bytes.putFloat(blurStep / captured.textureWidth).putFloat(0).putFloat(1).putFloat(0).flip();
            encoder.writeToBuffer(horizontalParameters.slice(), bytes);
            bytes.clear();
            bytes.putFloat(0).putFloat(blurStep / height).putFloat(0).putFloat(0).flip();
            encoder.writeToBuffer(verticalParameters.slice(), bytes);
            bytes.clear();
            float rise = settings.mode() == 3 && retention > 0 ? HandFogTiming.flameRise(elapsed, height) : 0;
            bytes.putFloat(retention).putFloat(rise).putFloat(0).putFloat(0).flip();
            encoder.writeToBuffer(historyParameters.slice(), bytes);
        } finally { MemoryUtil.memFree(bytes); }
    }

    private static GpuBuffer buffer(String name, float x, float y, float z, float w) {
        ByteBuffer bytes = MemoryUtil.memAlloc(16).order(ByteOrder.nativeOrder());
        try { bytes.putFloat(x).putFloat(y).putFloat(z).putFloat(w).flip();
            return RenderSystem.getDevice().createBuffer(() -> name, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, bytes);
        } finally { MemoryUtil.memFree(bytes); }
    }

    /** Tick observation also catches transitions on frames that skip the hand pass entirely. */
    public static void observeEnvironment() {
        MinecraftClient client = MinecraftClient.getInstance();
        Object mainItem = client.player == null ? null : client.player.getMainHandStack().getItem();
        Object offItem = client.player == null ? null : client.player.getOffHandStack().getItem();
        Object arm = client.player == null ? null : client.player.getMainArm();
        boolean eligible = HandShadersFeature.enabled() && client.player != null && client.world != null
            && client.options.getPerspective().isFirstPerson() && !client.player.isSpectator()
            && !client.player.isDead() && !client.options.hudHidden;
        HandShadersFeature.Settings settings = HandShadersFeature.settings();
        if (!environmentSeen || !eligible || eligible != observedEligible || observedWorld != client.world
            || observedPlayer != client.player || observedScreen != client.currentScreen || observedMainItem != mainItem
            || observedOffItem != offItem || observedArm != arm || !settings.equals(observedSettings)) invalidateHistory();
        environmentSeen = true;
        observedEligible = eligible;
        observedWorld = client.world; observedPlayer = client.player; observedScreen = client.currentScreen;
        observedMainItem = mainItem; observedOffItem = offItem; observedArm = arm; observedSettings = settings;
    }

    private static void invalidateHistory() {
        historyValid = false;
        historyNeedsClear = true;
        lastCaptureNanos = 0;
    }

    public static void resetFailure() { failed = false; validated = false; invalidateHistory(); }
    public static void releaseResources() {
        RenderSystem.assertOnRenderThread();
        if (active != null) active.restore();
        deleteTargets();
        environmentSeen = false;
        observedWorld = observedPlayer = observedScreen = observedMainItem = observedOffItem = observedArm = null;
        observedSettings = null;
        resetFailure();
    }
    private static void deleteTargets() {
        invalidateHistory();
        observedTarget = null;
        if (captured != null) { captured.delete(); captured = null; }
        if (blurHorizontal != null) { blurHorizontal.delete(); blurHorizontal = null; }
        if (blurVertical != null) { blurVertical.delete(); blurVertical = null; }
        if (fogHorizontal != null) { fogHorizontal.delete(); fogHorizontal = null; }
        if (fogVertical != null) { fogVertical.delete(); fogVertical = null; }
        if (historyRead != null) { historyRead.delete(); historyRead = null; }
        if (historyWrite != null) { historyWrite.delete(); historyWrite = null; }
        if (parameters != null) { parameters.close(); parameters = null; }
        if (horizontalParameters != null) { horizontalParameters.close(); horizontalParameters = null; }
        if (verticalParameters != null) { verticalParameters.close(); verticalParameters = null; }
        if (fogHorizontalParameters != null) { fogHorizontalParameters.close(); fogHorizontalParameters = null; }
        if (fogVerticalParameters != null) { fogVerticalParameters.close(); fogVerticalParameters = null; }
        if (historyParameters != null) { historyParameters.close(); historyParameters = null; }
    }
    private static void requestRelease() {
        if (RenderSystem.isOnRenderThread()) releaseResources();
        else MinecraftClient.getInstance().execute(HandShadersRenderer::releaseResources);
    }
    private static void fail(Throwable failure) {
        invalidateHistory();
        if (!failed) LOGGER.warn("Hand Shaders unavailable; preserving vanilla held-item rendering", failure);
        failed = true;
    }
    private static RenderPipeline.Builder base(String name) {
        return RenderPipeline.builder().withLocation(id("pipeline/" + name))
            .withVertexShader(id("core/hand_shaders")).withFragmentShader(id("core/" + name))
            .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false).withColorWrite(true).withCull(false);
    }
    private static Identifier id(String path) { return Identifier.of("celeste", path); }
}
