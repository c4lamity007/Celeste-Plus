package dev.c4lam.celeste.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.c4lam.celeste.compat.BetaRenderPipelines;
import java.awt.Color;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;

/** Analytic circular outline, erased clockwise from twelve o'clock as remaining falls from one to zero. */
public final class CircularCountdownRender {
    private static final float PADDING = 2.0f;
    private static final RenderPipeline PIPELINE = BetaRenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET)
            .withLocation(Identifier.of("celeste", "pipeline/circular_countdown"))
            .withVertexShader(Identifier.of("celeste", "core/rect/selection_outline"))
            .withFragmentShader(Identifier.of("celeste", "core/rect/circular_countdown"))
            .withUniform(GuiPipelines.UBO, UniformType.UNIFORM_BUFFER)
            .withBlend(BlendFunction.TRANSLUCENT).withCull(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false)
            .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS).build()
    );

    /** Radius is the outer GUI-space radius; thickness is inset, with a transparent center. */
    public void draw(MatrixStack matrices, float centerX, float centerY, float radius,
                     float thickness, float remaining, Color color) {
        draw(matrices, centerX, centerY, radius, thickness, remaining, 0.0F, color);
    }

    /**
     * Draws the countdown and an analytic status mark in one antialiased pass.
     * Positive mark modes draw a check; negative modes draw an X.
     */
    public void drawMarked(MatrixStack matrices, float centerX, float centerY, float radius,
                           float thickness, float remaining, boolean enabled, Color color) {
        draw(matrices, centerX, centerY, radius, thickness, remaining, enabled ? 1.0F : -1.0F, color);
    }

    private void draw(MatrixStack matrices, float centerX, float centerY, float radius,
                      float thickness, float remaining, float markMode, Color color) {
        if (matrices == null || color == null || color.getAlpha() == 0
            || !Float.isFinite(centerX) || !Float.isFinite(centerY) || !Float.isFinite(radius)
            || !Float.isFinite(thickness) || !Float.isFinite(remaining)
            || !Float.isFinite(markMode) || radius <= 0.0f || thickness <= 0.0f
            || (remaining <= 0.0f && markMode == 0.0F)) return;
        float extent = radius + PADDING;
        if (!Float.isFinite(centerX - extent) || !Float.isFinite(centerX + extent)
            || !Float.isFinite(centerY - extent) || !Float.isFinite(centerY + extent)) return;
        float stroke = Math.min(thickness, radius);
        float fraction = Math.min(1.0f, remaining);
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        GuiRenderer renderer = GuiRenderer.getInstance();
        BufferBuilder builder = renderer.begin(PIPELINE);
        int argb = color.getRGB();
        builder.vertex(matrix, centerX - extent, centerY - extent, 0.0f).color(argb);
        builder.vertex(matrix, centerX - extent, centerY + extent, 0.0f).color(argb);
        builder.vertex(matrix, centerX + extent, centerY + extent, 0.0f).color(argb);
        builder.vertex(matrix, centerX + extent, centerY - extent, 0.0f).color(argb);
        renderer.submit(builder, PIPELINE, GuiUniform.of(radius, stroke, fraction, extent, markMode));
    }
}
