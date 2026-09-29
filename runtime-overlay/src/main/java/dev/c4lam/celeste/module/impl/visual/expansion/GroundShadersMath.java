package dev.c4lam.celeste.module.impl.visual.expansion;

/** Bounded ground-material settings and animation, independent of the game and GPU. */
public final class GroundShadersMath {
    public static final double PATTERN_PERIOD = 1024.0;
    public static final double TAU = Math.PI * 2.0;
    private GroundShadersMath() { }

    public record Settings(int mode, float intensity, float range, float scale, float speed, int color) { }

    public static Settings settings(String mode, double intensity, double range, double scale, double speed, int color) {
        return new Settings("Veins".equalsIgnoreCase(mode) ? 1 : 0,
            finite(intensity, .35, 0, 1), finite(range, 32, 8, 64),
            finite(scale, 1, .5, 2), finite(speed, .6, 0, 2), color & 0xFFFFFF);
    }

    private static float finite(double value, double fallback, double min, double max) {
        return (float) (Double.isFinite(value) ? Math.clamp(value, min, max) : fallback);
    }

    /** Apply pattern size before wrapping; larger sizes produce wider patterns without world seams. */
    public static float coordinate(double worldCoordinate, double scale) {
        double coordinate = worldCoordinate / finite(scale, 1, .5, 2);
        if (!Double.isFinite(coordinate)) return 0;
        double wrapped = coordinate % PATTERN_PERIOD;
        if (wrapped < 0) wrapped += PATTERN_PERIOD;
        // Rounding the last fraction to 1024 is equivalent to zero for the periodic shader.
        return (float) wrapped;
    }

    /** Identity tokens keep mutable worlds out of extracted render states. */
    public static boolean currentFrame(long capturedGeneration, long currentGeneration,
                                       Object capturedWorld, Object currentWorld, Settings active) {
        return capturedGeneration == currentGeneration && capturedWorld != null && capturedWorld == currentWorld
            && active != null && active.intensity > 0;
    }

    /** Phase integrates speed changes continuously; paused time and the first resumed interval are excluded. */
    public static final class Clock {
        private long previous;
        private boolean initialized, previouslyPaused;
        private double phase;
        public synchronized float sample(long now, boolean paused, double speed) {
            long delta = now - previous;
            if (initialized && !paused && !previouslyPaused && delta > 0)
                phase = (phase + delta / 1_000_000_000.0 * finite(speed, .6, 0, 2) * .18) % TAU;
            previous = now;
            previouslyPaused = paused;
            initialized = true;
            return (float) phase;
        }
        public synchronized void reset() {
            previous = 0;
            initialized = previouslyPaused = false;
            phase = 0;
        }
    }
}
