package dev.c4lam.celeste.render.font;

import dev.c4lam.celeste.render.GuiPipelines;
import dev.c4lam.celeste.render.GuiRenderer;
import dev.c4lam.celeste.render.GuiTextures;
import dev.c4lam.celeste.render.GuiUniform;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.TextureSetup;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;

import java.awt.Color;
import java.util.Map;

/** Lightweight batched MSDF font renderer for Celeste's GUI. */
public final class Font {
    private static final float DEFAULT_SMOOTHNESS = 0.5f;

    private final String name;
    private final AbstractTexture texture;
    private final FontData.AtlasData atlas;
    private final FontData.MetricsData metrics;
    private final Map<Integer, MsdfGlyph> glyphs;
    private final MsdfGlyph replacement;
    private final Map<Integer, Map<Integer, Float>> kernings;

    Font(
            String name,
            AbstractTexture texture,
            FontData.AtlasData atlas,
            FontData.MetricsData metrics,
            Map<Integer, MsdfGlyph> glyphs,
            Map<Integer, Map<Integer, Float>> kernings
    ) {
        this.name = name;
        this.texture = texture;
        this.atlas = atlas;
        this.metrics = metrics;
        this.glyphs = Map.copyOf(glyphs);
        this.replacement = glyphs.getOrDefault(0xFFFD,
            glyphs.getOrDefault(0x25A1, glyphs.get((int) '?')));
        this.kernings = kernings;
    }

    public String name() {
        return name;
    }

    public FontData.AtlasData atlas() {
        return atlas;
    }

    public FontData.MetricsData metrics() {
        return metrics;
    }

    public void draw(MatrixStack matrices, String text, float x, float y, float size, Color color) {
        draw(matrices, text, x, y, size, color.getRGB(), 0.0f, DEFAULT_SMOOTHNESS, 0.0f, 0, 0.0f);
    }

    public void drawText(MatrixStack matrices, String text, float x, float y, float size, Color color) {
        draw(matrices, text, x, y, size, color);
    }

    /** Draws a fixed-size horizontal gradient with one soft diagonal glint sweep. */
    public void drawGradient(MatrixStack matrices, String text, float x, float y, float size,
                             Color start, Color end, Color glint, float glintPhase, float glintStrength) {
        if (text == null || text.isEmpty() || size <= 0.0F
            || (start.getAlpha() == 0 && end.getAlpha() == 0)) {
            return;
        }

        GuiRenderer renderer = GuiRenderer.getInstance();
        BufferBuilder builder = renderer.begin(GuiPipelines.TEXT);
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        float measuredWidth = Math.max(1.0F, width(text, size));
        float measuredHeight = Math.max(1.0F, height(size));
        // Vertices already contain the MatrixStack transform. Its inverse 2D
        // basis recovers whole-line coordinates at each fragment, including
        // glyph overhangs, without approximating the glint at glyph corners.
        float determinant = matrix.m00() * matrix.m11() - matrix.m10() * matrix.m01();
        boolean gradient = Float.isFinite(determinant) && Math.abs(determinant) > 0.000001F;
        float inverse = gradient ? 1.0F / determinant : 0.0F;
        float originX = matrix.m00() * x + matrix.m10() * y + matrix.m30();
        float originY = matrix.m01() * x + matrix.m11() * y + matrix.m31();
        float[] startColor = normalize(start.getRGB());
        float[] endColor = normalize(end.getRGB());
        float[] glintColor = normalize(glint.getRGB());
        float cursorX = x;
        float baseline = y + metrics.baselineHeight() * size;
        int previous = -1;

        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '\n') {
                cursorX = x;
                baseline += metrics.lineHeight() * size;
                previous = -1;
                continue;
            }
            MsdfGlyph glyph = glyph(codePoint);
            if (glyph == null) {
                continue;
            }

            cursorX += kerning(previous, glyph.codePoint()) * size;
            cursorX += glyph.emit(matrix, builder, size, cursorX, baseline, 0.0F, start.getRGB());
            previous = glyph.codePoint();
        }

        renderer.submit(
            builder,
            GuiPipelines.TEXT,
            TextureSetup.of(texture.getGlTextureView(), GuiTextures.linear()),
            GuiUniform.of(
                matrix.m11() * inverse / measuredWidth, -matrix.m10() * inverse / measuredWidth,
                -matrix.m01() * inverse / measuredHeight, matrix.m00() * inverse / measuredHeight,
                originX, originY, DEFAULT_SMOOTHNESS, gradient ? 1.0F : 0.0F,
                startColor[0], startColor[1], startColor[2], startColor[3],
                endColor[0], endColor[1], endColor[2], endColor[3],
                glintColor[0], glintColor[1], glintColor[2], glintColor[3],
                glintPhase, Math.max(0.0F, Math.min(1.0F, glintStrength)), 0.105F, 0.14F,
                atlas.range(), 0.0F, 0.0F, 0.0F,
                0.0F, 0.0F, 0.0F, 0.0F
            )
        );
    }

    public void draw(
            MatrixStack matrices,
            String text,
            float x,
            float y,
            float size,
            int argb,
            float thickness,
            float smoothness,
            float letterSpacing,
            int outlineArgb,
            float outlineThickness
    ) {
        if (text == null || text.isEmpty() || size <= 0.0f || (argb >>> 24) == 0) {
            return;
        }

        GuiRenderer renderer = GuiRenderer.getInstance();
        BufferBuilder builder = renderer.begin(GuiPipelines.TEXT);
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        float cursorX = x;
        float baseline = y + metrics.baselineHeight() * size;
        int previous = -1;

        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if (codePoint == '\n') {
                cursorX = x;
                baseline += metrics.lineHeight() * size;
                previous = -1;
                continue;
            }

            MsdfGlyph glyph = glyph(codePoint);
            if (glyph == null) {
                continue;
            }

            cursorX += kerning(previous, glyph.codePoint()) * size;
            cursorX += glyph.emit(matrix, builder, size, cursorX, baseline, 0.0f, argb) + letterSpacing;
            previous = glyph.codePoint();
        }

        boolean outline = outlineThickness > 0.0f && (outlineArgb >>> 24) != 0;
        float[] outlineColor = normalize(outlineArgb);
        renderer.submit(
                builder,
                GuiPipelines.TEXT,
                TextureSetup.of(texture.getGlTextureView(), GuiTextures.linear()),
                GuiUniform.of(
                        0.0f, 0.0f, 0.0f, 0.0f,
                        0.0f, 0.0f, smoothness, 0.0f,
                        0.0f, 0.0f, 0.0f, 0.0f,
                        0.0f, 0.0f, 0.0f, 0.0f,
                        0.0f, 0.0f, 0.0f, 0.0f,
                        0.0f, 0.0f, 0.0f, 0.0f,
                        atlas.range(), thickness, outline ? 1.0f : 0.0f, outlineThickness,
                        outlineColor[0], outlineColor[1], outlineColor[2], outlineColor[3]
                )
        );
    }

    public void drawCentered(MatrixStack matrices, String text, float centerX, float y, float size, Color color) {
        draw(matrices, text, centerX - width(text, size) * 0.5f, y, size, color);
    }

    public void drawCenteredText(MatrixStack matrices, String text, float centerX, float y, float size, Color color) {
        drawCentered(matrices, text, centerX, y, size, color);
    }

    public float width(String text, float size) {
        if (text == null || text.isEmpty() || size <= 0.0f) {
            return 0.0f;
        }

        float lineWidth = 0.0f;
        float maxWidth = 0.0f;
        int previous = -1;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if (codePoint == '\n') {
                maxWidth = Math.max(maxWidth, lineWidth);
                lineWidth = 0.0f;
                previous = -1;
                continue;
            }

            MsdfGlyph glyph = glyph(codePoint);
            if (glyph == null) {
                continue;
            }
            lineWidth += kerning(previous, glyph.codePoint()) * size + glyph.advance(size);
            previous = glyph.codePoint();
        }
        return Math.max(maxWidth, lineWidth);
    }

    public float getWidth(String text, float size) {
        return width(text, size);
    }

    public float height(float size) {
        return metrics.lineHeight() * size;
    }

    public float getHeight(float size) {
        return height(size);
    }

    public static FontBuilder builder() {
        return new FontBuilder();
    }

    private float kerning(int left, int right) {
        Map<Integer, Float> rightCharacters = kernings.get(left);
        return rightCharacters == null ? 0.0f : rightCharacters.getOrDefault(right, 0.0f);
    }

    /** Keep rendering and measurement identical even outside the atlas coverage. */
    private MsdfGlyph glyph(int codePoint) {
        MsdfGlyph exact = glyphs.get(codePoint);
        if (exact != null) return exact;
        // Joiners, variation selectors and controls do not have visible ink.
        if (Character.getType(codePoint) == Character.FORMAT || Character.isISOControl(codePoint)
            || codePoint >= 0xFE00 && codePoint <= 0xFE0F
            || codePoint >= 0xE0100 && codePoint <= 0xE01EF) return null;
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
            return glyphs.get((int) ' ');
        }
        // Expose unsupported characters instead of silently changing the name.
        return replacement;
    }

    private static float[] normalize(int argb) {
        return new float[]{
                ((argb >>> 16) & 0xFF) / 255.0f,
                ((argb >>> 8) & 0xFF) / 255.0f,
                (argb & 0xFF) / 255.0f,
                ((argb >>> 24) & 0xFF) / 255.0f
        };
    }
}
