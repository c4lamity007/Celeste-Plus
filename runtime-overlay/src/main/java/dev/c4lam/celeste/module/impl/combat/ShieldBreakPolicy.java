package dev.c4lam.celeste.module.impl.combat;

import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

/** Small, tick-based attempt policy. Percentages select clicks, not server-side outcomes. */
final class ShieldBreakPolicy {
    private static final int RETRY_TICKS = 8;
    private int targetId = -1;
    private long readyAt;
    private long retryAt;
    private long lastTick = Long.MIN_VALUE;

    boolean ready(int target, long tick, double delayMillis) {
        if (targetId != target || tick < lastTick) {
            targetId = target;
            int delay = (int) Math.ceil(clamp(delayMillis, 0, 1000) / 50.0);
            readyAt = after(tick, delay);
            retryAt = tick;
        }
        lastTick = tick;
        return readyFor(target, tick);
    }

    boolean readyFor(int target, long tick) {
        return targetId == target && lastTick != Long.MIN_VALUE && tick >= lastTick
            && tick >= readyAt && tick >= retryAt;
    }

    void attempted(long tick) {
        retryAt = after(tick, RETRY_TICKS);
    }

    void reset() {
        targetId = -1;
        lastTick = Long.MIN_VALUE;
        readyAt = retryAt = 0;
    }

    /** One miss roll per executed opportunity; otherwise one or two same-tick axe clicks. */
    static int clicks(double stunPercent, double missPercent, DoubleSupplier random) {
        if (roll(missPercent, random)) return 0;
        return roll(stunPercent, random) ? 2 : 1;
    }

    /** No timer/thread between clicks; the caller holds one slot scope across this whole burst. */
    static int runClicks(int clicks, BooleanSupplier stillValid, Runnable attack) {
        int sent = 0;
        for (int i = 0; i < Math.min(2, Math.max(0, clicks)); i++) {
            if (!stillValid.getAsBoolean()) break;
            attack.run();
            sent++;
        }
        return sent;
    }

    private static boolean roll(double percent, DoubleSupplier random) {
        double value = clamp(percent, 0, 100);
        if (value == 0) return false;
        if (value == 100) return true;
        double sample = random.getAsDouble();
        return Double.isFinite(sample) && sample >= 0 && sample < 1 && sample * 100 < value;
    }

    private static double clamp(double value, double low, double high) {
        return Double.isFinite(value) ? Math.max(low, Math.min(high, value)) : low;
    }

    private static long after(long tick, int delay) {
        return tick > Long.MAX_VALUE - delay ? Long.MAX_VALUE : tick + delay;
    }
}
