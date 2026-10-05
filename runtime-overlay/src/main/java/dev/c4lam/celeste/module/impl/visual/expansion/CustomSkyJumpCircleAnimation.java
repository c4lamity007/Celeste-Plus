package dev.c4lam.celeste.module.impl.visual.expansion;

/** Pure animation math; world observation and rendering never change player movement. */
final class CustomSkyJumpCircleAnimation {
    private CustomSkyJumpCircleAnimation() { }

    static double unit(double value) { return Math.max(0.0, Math.min(1.0, value)); }

    static double radius(double age, double maximum) {
        double remaining = 1.0 - unit(age);
        return maximum * (0.14 + 0.86 * (1.0 - remaining * remaining * remaining));
    }

    static double opacity(double age) {
        if (age < 0.0 || age >= 1.0) return 0.0;
        double enter = unit(age / 0.075);
        double exit = unit((1.0 - age) / 0.64);
        return enter * enter * (3.0 - 2.0 * enter) * exit * exit;
    }

    record Frame(double radius, double opacity, double rotation) { }

    /** Grow with sine easing, hold, then fade; Duration is the complete lifetime. */
    static Frame frame(double elapsed, double duration, double size, boolean shrink) {
        if (!Double.isFinite(elapsed + duration + size) || duration <= 0 || size <= 0
            || elapsed <= 0 || elapsed >= duration) return new Frame(0, 0, 0);
        double spawn = Math.min(.30, duration * .30);
        double fade = Math.min(.20, duration * .25);
        double enter = Math.sin(unit(elapsed / spawn) * Math.PI * .5);
        double exit = 1 - Math.sin(unit((elapsed - duration + fade) / fade) * Math.PI * .5);
        return new Frame(size * enter * (shrink ? exit : 1), enter * exit,
            Math.toRadians((elapsed * 1000) % 360));
    }

    static boolean takeoff(boolean wasGrounded, boolean grounded, boolean jumping,
                           boolean allowed, double dy, double distanceSquared) {
        return wasGrounded && !grounded && jumping && allowed
            && dy > 0.025 && dy < 1.0 && distanceSquared < 4.0;
    }

    /** Monotonic seconds with pause-time excluded; no wall-clock or tick-rate dependency. */
    static final class Clock {
        private long previous;
        private boolean initialized;
        private boolean previouslyPaused;
        private double elapsed;

        synchronized double sample(long now, boolean paused) {
            if (initialized && !paused && !previouslyPaused)
                elapsed += Math.max(0L, now - previous) / 1_000_000_000.0;
            previous = now;
            previouslyPaused = paused;
            initialized = true;
            return elapsed;
        }

        synchronized void reset() {
            previous = 0L;
            initialized = false;
            previouslyPaused = false;
            elapsed = 0.0;
        }
    }
}
