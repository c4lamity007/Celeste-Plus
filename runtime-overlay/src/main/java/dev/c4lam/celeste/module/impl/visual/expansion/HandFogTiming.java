package dev.c4lam.celeste.module.impl.visual.expansion;

/** Frame-rate independent visual persistence; unrelated to gameplay tick timing. */
public final class HandFogTiming {
    public static final double TIME_CONSTANT_SECONDS = .085;
    public static final double FLAME_TIME_CONSTANT_SECONDS = .070;
    public static final double MAX_FRAME_GAP_SECONDS = .25;
    public static final double FLAME_RISE_PIXELS_PER_SECOND = 72.0;

    private HandFogTiming() { }

    /** Zero discards history on first frame, invalid clocks, or a stalled renderer. */
    public static float retention(double deltaSeconds) {
        return retention(deltaSeconds, false);
    }

    /** Flame responds 17.65% sooner; Smoke retains its shipped 85 ms persistence. */
    public static float retention(double deltaSeconds, boolean flame) {
        if (!Double.isFinite(deltaSeconds) || deltaSeconds <= 0
                || deltaSeconds > MAX_FRAME_GAP_SECONDS) return 0;
        return (float) Math.exp(-deltaSeconds / (flame ? FLAME_TIME_CONSTANT_SECONDS : TIME_CONSTANT_SECONDS));
    }

    /** UV displacement of the delayed flame, integrated by elapsed render time. */
    public static float flameRise(double deltaSeconds, int framebufferHeight) {
        if (retention(deltaSeconds) == 0 || framebufferHeight <= 0) return 0;
        double scale = Math.clamp(framebufferHeight / 540.0, 1.0, 3.0);
        return (float) (FLAME_RISE_PIXELS_PER_SECOND * scale * deltaSeconds / framebufferHeight);
    }

    /** Gentle sideways flow, integrated by elapsed time instead of frame count. */
    public static float flameSway(double deltaSeconds, int width, int height, double seconds) {
        if (retention(deltaSeconds) == 0 || width <= 0 || height <= 0 || !Double.isFinite(seconds)) return 0;
        double scale = Math.clamp(height / 540.0, 1.0, 3.0);
        return (float) (Math.sin(seconds * 1.9) * 34.0 * scale * deltaSeconds / width);
    }

    /** Outward motion of the low-frequency exterior aura, expressed in vertical UV units. */
    public static float flameSpread(double deltaSeconds, int height) {
        if (retention(deltaSeconds) == 0 || height <= 0) return 0;
        return (float) (24.0 * Math.clamp(height / 540.0, 1.0, 3.0) * deltaSeconds / height);
    }

}
