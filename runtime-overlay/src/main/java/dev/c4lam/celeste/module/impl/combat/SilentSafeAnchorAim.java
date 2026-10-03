package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.rotation.Rotation;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;
import java.util.Random;

/** A bounded intertick turn; the action deadline always lands on the current world target. */
final class SilentSafeAnchorAim {
    private static final long TICK_NANOS = 50_000_000L;
    private final Random random;
    private Rotation origin;
    private long startedNanos;
    private long durationNanos;
    private double timingCurve;
    private double yawCurve;
    private double pitchCurve;

    SilentSafeAnchorAim(Random random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    void start(Rotation from, long nowNanos, int ticks) {
        Objects.requireNonNull(from, "from");
        if (ticks < 1) throw new IllegalArgumentException("ticks must be positive");
        origin = from;
        startedNanos = nowNanos;
        durationNanos = ticks * TICK_NANOS;
        // Keep each segment stable across frames. Re-sampling here also makes
        // elapsed-time sampling independent of FPS and render callback count.
        timingCurve = (random.nextDouble() * 2.0D - 1.0D) * 0.3D;
        yawCurve = random.nextDouble() * 2.0D - 1.0D;
        pitchCurve = random.nextDouble() * 2.0D - 1.0D;
    }

    Rotation sample(Vec3d eye, Vec3d target, long nowNanos, boolean due) {
        Rotation destination = Rotation.lookingAt(eye, target);
        if (origin == null || due) return destination;
        double progress = Math.max(0.0D, Math.min(1.0D,
            (nowNanos - startedNanos) / (double) durationNanos));
        if (progress <= 0.0D) return origin;
        if (progress >= 1.0D) return destination;

        double shaped = progress + timingCurve * progress * (1.0D - progress);
        double eased = shaped * shaped * (3.0D - 2.0D * shaped);
        double taper = 4.0D * eased * (1.0D - eased);
        float yawDelta = origin.yawDeltaTo(destination);
        float pitchDelta = origin.pitchDeltaTo(destination);
        // Limit curvature by angular distance as well as degrees. The curve
        // cannot overshoot either endpoint, including very small face changes.
        double yawBend = yawCurve * Math.min(3.0D, Math.abs(yawDelta) * 0.06D);
        double pitchBend = pitchCurve * Math.min(2.0D, Math.abs(pitchDelta) * 0.08D);
        return new Rotation(
            (float) (origin.yaw() + yawDelta * eased + yawBend * taper),
            (float) (origin.pitch() + pitchDelta * eased + pitchBend * taper));
    }

    void clear() {
        origin = null;
        startedNanos = 0L;
        durationNanos = 0L;
        timingCurve = yawCurve = pitchCurve = 0.0D;
    }
}
