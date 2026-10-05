package dev.c4lam.celeste.render.geometry;

import net.minecraft.util.math.Box;

/** Parent-defined immutable geometry. It carries no module object or executable callback. */
public record WorldBox(Box box, int fillColor, int topFillColor, int lineColor,
                       float lineWidth, Shape shape, boolean glow) {
    public enum Shape { BOX, CORNERS, FOOTPRINT, BEACON }
    public WorldBox(Box box, int fillColor, int lineColor, float lineWidth) {
        this(box, fillColor, fillColor, lineColor, lineWidth, Shape.BOX, false);
    }
    public static int argb(float alpha, int rgb) {
        int a = Math.clamp(Math.round(alpha * 255.0F), 0, 255);
        return a << 24 | rgb & 0x00FFFFFF;
    }
}
