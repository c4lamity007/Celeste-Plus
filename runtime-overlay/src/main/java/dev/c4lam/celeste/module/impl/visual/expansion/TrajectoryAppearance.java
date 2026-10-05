package dev.c4lam.celeste.module.impl.visual.expansion;

/** Small presentation policy; none of these values change projectile simulation or its hit point. */
public final class TrajectoryAppearance {
    private TrajectoryAppearance() { }
    public record Budget(int pathSegments, int glowSprites, int markerQuads, int absoluteLimit) { }
    public record Sample(double width, double alpha) { }

    public static boolean enoughDetail(int pointCount, int segments) {
        // Under extreme contention keep the exact line fallback, never replace an arc with one chord.
        return pointCount >= 2 && segments >= Math.min(24, pointCount - 1);
    }

    public static Budget budget(int existing, int ceiling, boolean marker, double glow) {
        int start = Math.max(0, existing);
        int room = Math.max(0, Math.max(0, Math.min(768, ceiling)) - start);
        int markers = marker ? Math.min(room, glow > .01 ? 2 : 1) : 0;
        int available = room - markers;
        // Keep at least 24 core segments before sharing space with the image halo.
        int sprites = glow > .01 ? Math.min(64, Math.max(0, (available - 24) * 2 / 3)) : 0;
        int path = Math.min(128, available - sprites);
        return new Budget(path, sprites, markers, start + path + sprites + markers);
    }

    public static Sample sample(double cameraDistance, double progress, double opacity, boolean fade) {
        if (!Double.isFinite(cameraDistance) || !Double.isFinite(progress) || !Double.isFinite(opacity))
            return new Sample(0, 0);
        double near = smooth((cameraDistance - .22) / .78);
        double tail = fade ? 1 - .45 * smooth(progress) : 1;
        // A narrow luminous core surrounded by a broader, soft shader silhouette.
        double width = clamp(.075 + Math.max(0, cameraDistance) * .006, .075, .65);
        return new Sample(width, clamp(opacity, 0, 1) * near * tail);
    }

    public static double markerAlpha(double cameraDistance, double opacity) {
        if (!Double.isFinite(cameraDistance) || !Double.isFinite(opacity)) return 0;
        return clamp(opacity, 0, 1) * smooth((cameraDistance - .18) / .42);
    }

    private static double smooth(double value) {
        double t = clamp(value, 0, 1);
        return t * t * (3 - 2 * t);
    }
    private static double clamp(double value, double low, double high) { return Math.max(low, Math.min(high, value)); }
}
