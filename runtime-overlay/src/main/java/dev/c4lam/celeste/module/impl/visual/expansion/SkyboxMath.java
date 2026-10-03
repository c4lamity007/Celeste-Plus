package dev.c4lam.celeste.module.impl.visual.expansion;

/** Small deterministic configuration/time policy shared by the procedural sky adapter. */
public final class SkyboxMath {
    public static final long LOOP_NANOS = 240_000_000_000L;
    private SkyboxMath() { }

    public static int mode(String name) {
        if ("Nebula".equalsIgnoreCase(name)) return 1;
        if ("Sunset".equalsIgnoreCase(name)) return 2;
        if ("Abyss".equalsIgnoreCase(name)) return 3;
        if ("Black Hole".equalsIgnoreCase(name)) return 4;
        return 0;
    }

    public static float finiteClamp(double value, double fallback, double minimum, double maximum) {
        return (float) Math.max(minimum, Math.min(maximum, Double.isFinite(value) ? value : fallback));
    }

    /** Bounded, sub-tick phase; all shader motion is periodic in this phase. */
    public static float animationPhase(long nowNanos, long originNanos) {
        long elapsed = nowNanos - originNanos;
        if (elapsed < 0) return 0;
        return (float) ((elapsed % LOOP_NANOS) / (double) LOOP_NANOS * (Math.PI * 2));
    }

    public static boolean shouldRender(boolean enabled, boolean overworld, boolean clearMedium,
                                       boolean normalVision, boolean aboveVoid) {
        return enabled && overworld && clearMedium && normalVision && aboveVoid;
    }
}
