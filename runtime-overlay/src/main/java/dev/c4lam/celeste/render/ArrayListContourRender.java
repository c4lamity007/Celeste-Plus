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

/** One antialiased stepped contour, evaluated in non-overlapping row slabs. */
public final class ArrayListContourRender {
    private static final RenderPipeline PIPELINE = BetaRenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET)
            .withLocation(Identifier.of("celeste", "pipeline/arraylist_contour"))
            .withVertexShader(Identifier.of("celeste", "core/rect/arraylist_contour"))
            .withFragmentShader(Identifier.of("celeste", "core/rect/arraylist_contour"))
            .withUniform(GuiPipelines.UBO, UniformType.UNIFORM_BUFFER)
            .withBlend(BlendFunction.TRANSLUCENT).withCull(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false)
            .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS).build());

    /** x/y are the full-list top-left; the screen-facing wall stays straight. */
    public void draw(MatrixStack matrices, float x, float y, float[] widths, float rowHeight,
                     boolean rightAligned, Color color) {
        if (matrices == null || color == null || color.getAlpha() == 0
                || !Float.isFinite(x) || !Float.isFinite(y)) return;
        var rows = ArrayListContour.rows(widths, rowHeight);
        if (rows.isEmpty() || !Float.isFinite(x + widths[0] + ArrayListContour.PADDING)
                || !Float.isFinite(y + rows.size() * rowHeight + ArrayListContour.PADDING)) return;
        float wall = rightAligned ? x + widths[0] : x;
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        GuiRenderer renderer = GuiRenderer.getInstance();
        for (var row : rows) {
            float left = -ArrayListContour.PADDING, right = row.slabWidth();
            float top = row.previous() < 0 ? -ArrayListContour.PADDING : 0;
            float bottom = row.next() < 0 ? rowHeight + ArrayListContour.PADDING : rowHeight;
            BufferBuilder builder = renderer.begin(PIPELINE);
            vertex(builder, matrix, wall, y + row.slabTop(), left, top, rightAligned, color.getRGB());
            vertex(builder, matrix, wall, y + row.slabBottom(), left, bottom, rightAligned, color.getRGB());
            vertex(builder, matrix, wall, y + row.slabBottom(), right, bottom, rightAligned, color.getRGB());
            vertex(builder, matrix, wall, y + row.slabTop(), right, top, rightAligned, color.getRGB());
            renderer.submit(builder, PIPELINE, GuiUniform.of(row.previous(), row.width(), row.next(), rowHeight,
                row.topRadius(), row.bottomRadius()));
        }
    }

    private static void vertex(BufferBuilder builder, Matrix4f matrix, float wall, float screenY,
                               float localX, float localY, boolean rightAligned, int argb) {
        builder.vertex(matrix, wall + (rightAligned ? -localX : localX), screenY, 0)
            .texture(localX, localY).color(argb);
    }
}
