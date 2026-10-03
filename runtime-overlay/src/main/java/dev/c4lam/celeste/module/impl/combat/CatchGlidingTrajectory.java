package dev.c4lam.celeste.module.impl.combat;

import static dev.c4lam.celeste.module.impl.combat.CatchHorizontalPearlTrajectory.*;

/** Keeps the accepted pearl launch separate from the glider's later pose and movement. */
public final class CatchGlidingTrajectory {
    private State pearl;
    private long pearlTick;

    /** Movement is already filtered through vanilla's on-ground inheritance rule. */
    public void capture(Vec eyes, Vec inheritedMovement, float yaw, float pitch, long tick) {
        pearl = launch(eyes, inheritedMovement, false, yaw, pitch);
        pearlTick = tick;
    }

    public Solution solveCharge(long tick, Vec eyes, Vec inheritedMovement) {
        if (pearl == null || tick < pearlTick) return null;
        long elapsed = tick - pearlTick;
        if (elapsed < 1 || elapsed > Integer.MAX_VALUE) return null;
        return solveDelayed(pearl, (int) elapsed, eyes, inheritedMovement);
    }

    public void clear() {
        pearl = null;
        pearlTick = 0;
    }
}
