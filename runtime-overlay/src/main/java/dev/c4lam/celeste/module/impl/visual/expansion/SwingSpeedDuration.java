package dev.c4lam.celeste.module.impl.visual.expansion;

/** Keeps one swing's visual duration stable without retaining it after the module is disabled. */
final class SwingSpeedDuration {
    private int activeDuration;
    private int previousSwingTick;
    private boolean swingActive;

    int resolve(boolean enabled, boolean easeChanges, int requested, int vanilla,
                boolean handSwinging, int swingTick) {
        if (!enabled) {
            reset();
            return vanilla;
        }
        requested = Math.max(1, requested);
        // A newly accepted swing resets handSwingTicks to -1. Changes to the
        // speed setting should not alter the denominator of an ongoing swing.
        if (!handSwinging || !swingActive || swingTick < previousSwingTick) {
            activeDuration = requested;
        }
        swingActive = handSwinging;
        previousSwingTick = swingTick;
        return easeChanges && swingActive ? activeDuration : requested;
    }

    void reset() {
        activeDuration = 0;
        previousSwingTick = 0;
        swingActive = false;
    }
}
