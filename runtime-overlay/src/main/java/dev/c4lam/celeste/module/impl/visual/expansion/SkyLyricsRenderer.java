package dev.c4lam.celeste.module.impl.visual.expansion;

import com.google.gson.Gson;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.c4lam.celeste.compat.RenderCompatibility;
import dev.c4lam.celeste.media.TimedLyrics;
import dev.c4lam.celeste.render.font.FontData;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/** Cached custom SF Pro MSDF text and word evaporation on a fixed, depth-tested world plane. */
public final class SkyLyricsRenderer {
    public record Frame(TimedLyrics.WordCue cue, long playbackMs, float opacity, float size,
                        Vec3d position, Quaternionf rotation, int color, int endColor, int gradient) {
        public static final Frame EMPTY = new Frame(TimedLyrics.WordCue.EMPTY, 0, 0, 1, Vec3d.ZERO,
            new Quaternionf(), 0xF4F0FF, 0x9CE5F2, 0);
    }
    private static final org.slf4j.Logger LOGGER = dev.c4lam.celeste.compat.RuntimeLogging.getLogger("Celeste/SkyLyrics");
    private static final RenderLayer TEXT = layer("text"), VAPOR = layer("vapor");
    private static BufferAllocator allocator;
    private static VertexConsumerProvider.Immediate buffers;
    private static SkyLyricFont font;
    private static SkyLyricFont.Layout layout;
    private static String previousText;
    private static boolean failed;
    private SkyLyricsRenderer() { }
    private static RenderLayer layer(String name) {
        RenderPipeline pipeline = dev.c4lam.celeste.compat.BetaRenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET)
                .withLocation(Identifier.of("celeste", "pipeline/sky_lyrics_" + name))
                .withVertexShader(Identifier.of("celeste", "core/sky_lyrics"))
                .withFragmentShader(Identifier.of("celeste", "core/sky_lyrics"))
                .withSampler("Sampler0")
                .withVertexFormat(VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, VertexFormat.DrawMode.QUADS)
                .withBlend(BlendFunction.TRANSLUCENT).withCull(false)
                .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST).withDepthWrite(false).build());
        return RenderLayer.of("celeste_sky_lyrics_" + name, RenderSetup.builder(pipeline)
            .texture("Sampler0", Identifier.of("celeste:fonts/sf_pro/sf_semibold.png"),
                () -> RenderSystem.getSamplerCache().get(FilterMode.LINEAR, true))
            .translucent().expectedBufferSize(SkyLyricFont.MAX_VERTICES * 36).build());
    }
    public static void release() {
        if (!RenderSystem.isOnRenderThread()) { dev.c4lam.celeste.module.delivery.ModuleScope.executeCurrent(MinecraftClient.getInstance()::execute, SkyLyricsRenderer::release); return; }
        clearStorage(); failed = false;
    }
    private static void clearStorage() {
        previousText = null; layout = null; font = null; buffers = null;
        var old = allocator; allocator = null;
        if (old != null) old.close();
    }
    public static void draw(WorldRenderContext context, Frame frame) {
        if (frame == null || frame == Frame.EMPTY || frame.cue() == null || frame.cue().text().isBlank()
            || !Float.isFinite(frame.opacity()) || frame.opacity() <= 0 || !Float.isFinite(frame.size()) || frame.size() <= 0
            || context == null || context.matrices() == null || context.worldState() == null
            || context.worldState().cameraRenderState == null || !context.worldState().cameraRenderState.initialized
            || RenderCompatibility.isShadowPass() || failed) return;
        Vec3d camera = context.worldState().cameraRenderState.pos;
        if (camera == null || frame.position() == null || frame.rotation() == null) return;
        Vec3d relative = frame.position().subtract(camera);
        if (!Double.isFinite(relative.x) || !Double.isFinite(relative.y) || !Double.isFinite(relative.z)
            || relative.lengthSquared() > 512 * 512 || !frame.rotation().isFinite()) return;
        try {
            if (font == null) {
                var resource = MinecraftClient.getInstance().getResourceManager()
                    .getResource(Identifier.of("celeste:fonts/sf_pro/sf_semibold.json")).orElseThrow();
                try (var reader = resource.getReader()) { font = new SkyLyricFont(new Gson().fromJson(reader, FontData.class)); }
            }
            if (layout == null || !frame.cue().text().equals(previousText)) {
                layout = font.layout(frame.cue().text()); previousText = frame.cue().text();
            }
            if (layout.glyphs().isEmpty()) return;
            if (buffers == null) { allocator = new BufferAllocator(SkyLyricFont.MAX_VERTICES * 36); buffers = VertexConsumerProvider.immediate(allocator); }
            float scale = SkyLyricFont.EM_SCALE * Math.clamp(frame.size(), .5f, 2);
            Matrix4f matrix = new Matrix4f(context.matrices().peek().getPositionMatrix())
                .translate((float)relative.x, (float)relative.y, (float)relative.z)
                .rotate(frame.rotation()).scale(scale, -scale, scale);
            // Vapor first, crisp text second. Only this dedicated provider is ever flushed.
            for (int pass = 1; pass >= 0; pass--) {
                var layer = pass == 0 ? TEXT : VAPOR;
                var consumer = buffers.getBuffer(layer);
                SkyLyricFont.emit(layout, frame.cue(), frame.playbackMs(), frame.opacity(), frame.color(), frame.endColor(), frame.gradient(), pass,
                    (x,y,u,v,color,localX,localY,phase,seed,vapor) -> consumer.vertex(matrix,x,y,0).color(color)
                        .texture(u,v).overlay(localX,localY).light(phase,seed).normal(vapor,0,0));
                buffers.draw(layer);
            }
        } catch (Exception failure) {
            failed = true;
            try { clearStorage(); } catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            LOGGER.warn("Sky Lyrics unavailable until a resource reload or re-enable; world rendering continues", failure);
        }
    }
}
