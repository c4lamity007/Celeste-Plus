package dev.c4lam.celeste.hud;

/** Pure fixed-density conversions shared by HUD rendering and its regression checks. */
public final class HudViewportPolicy {
    public static final float PIXELS_PER_UNIT = 3.0F;

    private HudViewportPolicy() { }

    public static float logicalExtent(int framebufferPixels) {
        return Math.max(1.0F, framebufferPixels / PIXELS_PER_UNIT);
    }

    public static float renderScale(double guiScale) {
        float scale = Double.isFinite(guiScale) && guiScale > 0.0D
            ? (float) guiScale : PIXELS_PER_UNIT;
        return PIXELS_PER_UNIT / scale;
    }

    public static double screenToHud(double value, float renderScale) {
        return Double.isFinite(value) && Float.isFinite(renderScale) && renderScale > 0.0F
            ? value / renderScale : 0.0D;
    }

    public static float hudToScreen(float value, float renderScale) {
        return Float.isFinite(value) && Float.isFinite(renderScale) && renderScale > 0.0F
            ? value * renderScale : 0.0F;
    }

    /** Raw GLFW mouse position to fixed HUD space, including high-DPI windows. */
    public static double rawMouseToHud(double raw, int windowPixels, int framebufferPixels) {
        if (!Double.isFinite(raw) || windowPixels <= 0 || framebufferPixels <= 0) return 0.0D;
        return raw * framebufferPixels / (double) windowPixels / PIXELS_PER_UNIT;
    }

    public static float framebufferExtent(float hudUnits) {
        return Float.isFinite(hudUnits) ? hudUnits * PIXELS_PER_UNIT : 0.0F;
    }
}
