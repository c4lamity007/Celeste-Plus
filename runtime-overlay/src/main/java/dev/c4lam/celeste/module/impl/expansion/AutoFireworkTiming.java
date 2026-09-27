package dev.c4lam.celeste.module.impl.expansion;

/** Internal debounce for duplicate physical use edges; not a user-facing firing delay. */
final class AutoFireworkTiming {
    static final int MIN_USE_INTERVAL_TICKS = 2;

    private AutoFireworkTiming() {
    }

    static boolean ready(int playerAge, int lastUseAge) {
        return playerAge < lastUseAge
            || (long) playerAge - lastUseAge >= MIN_USE_INTERVAL_TICKS;
    }
}
