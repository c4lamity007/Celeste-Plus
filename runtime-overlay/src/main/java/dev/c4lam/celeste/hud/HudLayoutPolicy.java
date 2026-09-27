package dev.c4lam.celeste.hud;

/** Pure layout rules shared by the live HUD and focused regression checks. */
public final class HudLayoutPolicy {
    private static final float MIN_ISLAND_WIDTH = 108.0F;
    private static final float MAX_ISLAND_WIDTH = 264.0F;

    private HudLayoutPolicy() {
    }

    public static String coordinates(int x, int y, int z) {
        return x + ", " + y + ", " + z;
    }

    public static String performance(int fps, double speed, String tps) {
        double safeSpeed = Double.isFinite(speed) ? Math.max(0, speed) : 0;
        return Math.max(0, fps) + " FPS · " + String.format(java.util.Locale.ROOT, "%.2f BPS", safeSpeed)
            + " · " + tps + " TPS";
    }

    public static float importedUniformScale(float legacy, float horizontal, float vertical) {
        float fallback = validScale(legacy) ? legacy : 1.0F;
        float x = validScale(horizontal) ? horizontal : fallback;
        float y = validScale(vertical) ? vertical : fallback;
        return clampScale(Math.min(x, y));
    }

    public static float fittingUniformScale(float current, float intrinsicWidth, float intrinsicHeight,
                                            float viewportWidth, float viewportHeight) {
        float safeCurrent = clampScale(current);
        float fitX = positiveRatio(viewportWidth, intrinsicWidth);
        float fitY = positiveRatio(viewportHeight, intrinsicHeight);
        return clampScale(Math.min(safeCurrent, Math.min(fitX, fitY)));
    }

    public static boolean alignsRight(float x, float width, float viewportWidth) {
        if (!Float.isFinite(x) || !Float.isFinite(width) || !Float.isFinite(viewportWidth)
            || viewportWidth <= 0.0F) {
            return false;
        }
        return x + width * 0.5F >= viewportWidth * 0.5F;
    }

    public static float anchoredX(float x, float oldWidth, float newWidth, boolean rightAligned) {
        if (!Float.isFinite(x) || !Float.isFinite(oldWidth) || !Float.isFinite(newWidth)) {
            return 0.0F;
        }
        return rightAligned ? x + oldWidth - newWidth : x;
    }

    public static float rowX(float widgetX, float widgetWidth, float rowWidth, boolean rightAligned) {
        return rightAligned ? widgetX + widgetWidth - rowWidth : widgetX;
    }

    public static float centeredX(float x, float oldWidth, float newWidth) {
        if (!Float.isFinite(x) || !Float.isFinite(oldWidth) || !Float.isFinite(newWidth)) {
            return 0.0F;
        }
        return x + (oldWidth - newWidth) * 0.5F;
    }

    public static float boundedCenterX(float center, float width, float viewportWidth) {
        return Math.max(0.0f, Math.min(Math.max(0.0f, viewportWidth - width), center - width * .5f));
    }

    public static float glintPhase(long nowNanos, int row) {
        long cycle = 3_100_000_000L;
        return Math.floorMod(nowNanos - row * 52_000_000L, cycle) / (float) cycle * 1.5f - .25f;
    }

    public static float dynamicIslandWidth(float primaryTextWidth, float secondaryTextWidth,
                                           boolean artwork, float viewportWidth) {
        float textWidth = Math.max(finiteNonNegative(primaryTextWidth), finiteNonNegative(secondaryTextWidth));
        float chrome = artwork ? 44.0F : 25.0F;
        float maximum = Math.min(MAX_ISLAND_WIDTH, Math.max(1.0f,
            finiteNonNegative(viewportWidth) - 8.0F));
        return Math.max(Math.min(MIN_ISLAND_WIDTH, maximum), Math.min(maximum, textWidth + chrome));
    }

    public static float dynamicIslandHeight(boolean lyricLine) {
        return lyricLine ? 38.0F : 26.0F;
    }

    private static float positiveRatio(float numerator, float denominator) {
        if (!Float.isFinite(numerator) || !Float.isFinite(denominator)
            || numerator <= 0.0F || denominator <= 0.0F) {
            return HudElementState.MIN_SCALE;
        }
        return numerator / denominator;
    }

    private static boolean validScale(float value) {
        return Float.isFinite(value) && value > 0.0F;
    }

    private static float finiteNonNegative(float value) {
        return Float.isFinite(value) ? Math.max(0.0F, value) : 0.0F;
    }

    private static float clampScale(float value) {
        return Math.max(HudElementState.MIN_SCALE, Math.min(HudElementState.MAX_SCALE,
            validScale(value) ? value : 1.0F));
    }
}
