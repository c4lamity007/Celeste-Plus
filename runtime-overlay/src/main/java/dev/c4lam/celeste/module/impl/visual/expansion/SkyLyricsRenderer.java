package dev.c4lam.celeste.module.impl.visual.expansion;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import dev.c4lam.celeste.CelesteClient;
import dev.c4lam.celeste.compat.RenderCompatibility;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/** Vanilla font atlas, depth-tested geometry, no texture uploads or postprocessing per frame. */
public final class SkyLyricsRenderer {
    public record Frame(String current, String next, String artist, float progress, float opacity,
                        float size, Vec3d position, Quaternionf rotation) {
        public static final Frame EMPTY = new Frame("", "", "", 0, 0, 1, Vec3d.ZERO, new Quaternionf());
    }
    private static final RenderLayer BAR = RenderLayer.of("celeste_sky_lyric_line", RenderSetup.builder(dev.c4lam.celeste.compat.BetaRenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
            .withLocation(Identifier.of(CelesteClient.MOD_ID, "pipeline/sky_lyric_line"))
            .withBlend(BlendFunction.TRANSLUCENT).withCull(false)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST).withDepthWrite(false).build()
    )).translucent().expectedBufferSize(512).build());
    private static BufferAllocator allocator;
    private static VertexConsumerProvider.Immediate buffers;
    private static String previousCurrent = "", previousNext = "", previousArtist = "";
    private static String displayCurrent = "", displayNext = "", displayArtist = "";
    private static int currentWidth, nextWidth, artistWidth;
    private static Object fontIdentity;
    private SkyLyricsRenderer() { }
    static void release() {
        fontIdentity = null;
        buffers = null;
        if (allocator != null) allocator.close();
        allocator = null;
    }
    static void draw(WorldRenderContext context, Frame frame) {
        if (frame == null || frame == Frame.EMPTY || frame.opacity() <= 0 || context.matrices() == null
            || RenderCompatibility.isShadowPass()) return;
        var client = MinecraftClient.getInstance();
        var font = client.textRenderer;
        // Layout only when text changes. Resource reloads replace the font renderer.
        if (fontIdentity != font || !previousCurrent.equals(frame.current()) || !previousNext.equals(frame.next())
            || !previousArtist.equals(frame.artist())) {
            previousCurrent = frame.current(); previousNext = frame.next(); previousArtist = frame.artist(); fontIdentity = font;
            displayCurrent = font.trimToWidth(frame.current(), 420);
            displayNext = font.trimToWidth(frame.next(), 380);
            displayArtist = font.trimToWidth(frame.artist(), 260);
            currentWidth = font.getWidth(displayCurrent); nextWidth = font.getWidth(displayNext); artistWidth = font.getWidth(displayArtist);
        }
        if (buffers == null) { allocator = new BufferAllocator(32_768); buffers = VertexConsumerProvider.immediate(allocator); }
        Matrix4f matrix = new Matrix4f(context.matrices().peek().getPositionMatrix())
            .translate((float)frame.position().x, (float)frame.position().y, (float)frame.position().z)
            .rotate(frame.rotation()).scale(.07f * frame.size(), -.07f * frame.size(), .07f * frame.size());
        int white = VisualExpansionRenderer.argb(frame.opacity(), 0xF4F0FF);
        int muted = VisualExpansionRenderer.argb(frame.opacity() * .55f, 0xB6C4ED);
        font.draw(displayCurrent, -currentWidth / 2f, 0, white, true, matrix, buffers, TextRenderer.TextLayerType.NORMAL, 0, 0xF000F0);
        font.draw(displayNext, -nextWidth / 2f, 17, muted, true, matrix, buffers, TextRenderer.TextLayerType.NORMAL, 0, 0xF000F0);
        Matrix4f small = new Matrix4f(matrix).translate(0, -15, 0).scale(.65f);
        font.draw(displayArtist, -artistWidth / 2f, 0, muted, false, small, buffers, TextRenderer.TextLayerType.NORMAL, 0, 0xF000F0);
        buffers.draw(); // Dedicated provider: never flush another mod's buffered geometry.
        VertexConsumer bar = buffers.getBuffer(BAR);
        quad(bar, matrix, -45, 12, 45, 12.6f, VisualExpansionRenderer.argb(frame.opacity() * .18f, 0xAEBDE5));
        if (frame.progress() > 0) quad(bar, matrix, -45, 12, -45 + 90 * frame.progress(), 12.6f,
            VisualExpansionRenderer.argb(frame.opacity() * .9f, 0x9CE5F2));
        buffers.draw(BAR);
    }
    private static void quad(VertexConsumer consumer, Matrix4f matrix, float left, float top, float right, float bottom, int color) {
        consumer.vertex(matrix, left, top, 0).color(color); consumer.vertex(matrix, right, top, 0).color(color);
        consumer.vertex(matrix, right, bottom, 0).color(color); consumer.vertex(matrix, left, bottom, 0).color(color);
    }
}