package dev.c4lam.celeste.render;

/** Pure sampling rules for native-GUI rounded surfaces drawn beneath item models. */
public final class RoundedSurfacePolicy {
    public static final int RASTER_SCALE = 3;

    private RoundedSurfacePolicy() { }

    public static float scaled(float value) {
        return Float.isFinite(value) ? value * RASTER_SCALE : 0.0F;
    }

    public static float inset(float radius, int row) {
        float curve = Math.max(0.0F, radius);
        if (curve == 0.0F || row < 0) return 0.0F;
        float dy = Math.max(0.0F, curve - row - 0.5F);
        return curve - (float) Math.sqrt(Math.max(0.0F, curve * curve - dy * dy));
    }

    public static float edgeCoverage(float radius, int row) {
        float inset = inset(radius, row);
        int whole = (int) Math.ceil(inset);
        return Math.clamp(whole - inset, 0.0F, 1.0F);
    }
}
