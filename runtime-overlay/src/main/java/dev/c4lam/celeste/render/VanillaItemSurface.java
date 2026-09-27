package dev.c4lam.celeste.render;

import java.awt.Color;
import net.minecraft.client.gui.DrawContext;

/**
 * Rounded item underlays in Minecraft's own GUI queue.
 * Celeste's shader batch flushes after that queue: custom opaque surfaces must
 * not be placed over drawItem. Text and decorative foreground can stay custom.
 */
public final class VanillaItemSurface {
    private static final int MAX_CORNER_ROWS = 64;
    private VanillaItemSurface() { }

    public static void draw(DrawContext context, float x, float y, float width, float height,
                            float radius, Color color) {
        if (context == null || color == null || color.getAlpha() == 0
            || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(width)
            || !Float.isFinite(height) || !Float.isFinite(radius) || width <= 0 || height <= 0) return;
        drawRaster(context, x, y, width, height, radius, color);
    }

    /**
     * Renders at one framebuffer-pixel sampling for the fixed three-pixel HUD
     * density. This preserves native item ordering without magnifying a coarse
     * one-logical-pixel staircase around the Armor HUD corners.
     */
    public static void drawSmooth(DrawContext context, float x, float y, float width, float height,
                                  float radius, Color color) {
        if (context == null || color == null || color.getAlpha() == 0
            || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(width)
            || !Float.isFinite(height) || !Float.isFinite(radius) || width <= 0 || height <= 0) return;
        var matrices = context.getMatrices();
        matrices.pushMatrix();
        matrices.translate(x, y);
        float inverse = 1.0F / RoundedSurfacePolicy.RASTER_SCALE;
        matrices.scale(inverse, inverse);
        try {
            drawRaster(context, 0.0F, 0.0F, RoundedSurfacePolicy.scaled(width),
                RoundedSurfacePolicy.scaled(height), RoundedSurfacePolicy.scaled(radius), color);
        } finally {
            matrices.popMatrix();
        }
    }

    private static void drawRaster(DrawContext context, float x, float y, float width, float height,
                                   float radius, Color color) {
        int left = Math.round(x), top = Math.round(y), right = Math.round(x + width), bottom = Math.round(y + height);
        if (right <= left || bottom <= top) return;
        float curve = Math.min(MAX_CORNER_ROWS, Math.min(Math.max(0, radius),
            Math.min((right - left) / 2f, (bottom - top) / 2f)));
        int rows = Math.min((bottom - top) / 2, (int) Math.ceil(curve));
        int argb = color.getRGB();
        if (rows == 0) { context.fill(left, top, right, bottom, argb); return; }
        context.fill(left, top + rows, right, bottom - rows, argb);
        for (int row = 0; row < rows; row++) {
            float inset = RoundedSurfacePolicy.inset(curve, row);
            int whole = (int) Math.ceil(inset);
            span(context, left + whole, right - whole, top + row, argb);
            span(context, left + whole, right - whole, bottom - row - 1, argb);
            // Fractional boundary coverage softens the native one-pixel corner steps.
            float coverage = RoundedSurfacePolicy.edgeCoverage(curve, row);
            if (coverage > .001f && whole > 0) {
                int edge = Math.round(color.getAlpha() * coverage) << 24 | argb & 0xFFFFFF;
                span(context, left + whole - 1, left + whole, top + row, edge);
                span(context, right - whole, right - whole + 1, top + row, edge);
                span(context, left + whole - 1, left + whole, bottom - row - 1, edge);
                span(context, right - whole, right - whole + 1, bottom - row - 1, edge);
            }
        }
    }
    private static void span(DrawContext context, int left, int right, int y, int argb) {
        if (right > left && (argb >>> 24) > 0) context.fill(left, y, right, y + 1, argb);
    }
}
