package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Bounded movement history. Rendering never alters player or world state. */
public final class PlayerTrailHistory {
    public static final int MAX_SAMPLES = 96;
    private static final long SAMPLE_INTERVAL_MS = 40;
    public record Point(double x, double y, double z) {
        double distanceSquared(Point other) {
            double dx = x - other.x, dy = y - other.y, dz = z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
        boolean finite() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z); }
    }
    public record Sample(Point point, long timeMs) { }

    /** Pause freezes visual lifetime, including the gap before the first resumed frame. */
    public static final class Clock {
        private long previousMs = Long.MIN_VALUE, elapsedMs;
        private boolean wasPaused;
        public long update(long nowMs, boolean paused) {
            if (previousMs != Long.MIN_VALUE && nowMs >= previousMs && !paused && !wasPaused)
                elapsedMs += nowMs - previousMs;
            previousMs = nowMs;
            wasPaused = paused;
            return elapsedMs;
        }
        public void clear() { previousMs = Long.MIN_VALUE; elapsedMs = 0; wasPaused = false; }
    }
    private final ArrayDeque<Sample> committed = new ArrayDeque<>();
    private Object world, player;
    private Sample head;
    private long lastClock = Long.MIN_VALUE;

    public void clear() {
        committed.clear();
        head = null;
        world = player = null;
        lastClock = Long.MIN_VALUE;
    }

    public List<Sample> observe(Object world, Object player, Point point, long nowMs, long lifetimeMs) {
        if (world == null || player == null || point == null || !point.finite() || lifetimeMs <= 0) {
            clear();
            return List.of();
        }
        if (this.world != world || this.player != player || nowMs < lastClock
            || head != null && head.point.distanceSquared(point) > 64) clear();
        this.world = world;
        this.player = player;
        lastClock = nowMs;
        long lifetime = Math.max(250, Math.min(3000, lifetimeMs));
        while (!committed.isEmpty() && nowMs - committed.getFirst().timeMs >= lifetime) committed.removeFirst();
        if (head == null || head.point.distanceSquared(point) > .0001) {
            Sample next = new Sample(point, nowMs);
            if (committed.isEmpty() || nowMs - committed.getLast().timeMs >= SAMPLE_INTERVAL_MS) {
                committed.addLast(next);
                while (committed.size() >= MAX_SAMPLES) committed.removeFirst();
            }
            head = next;
        }
        List<Sample> result = new ArrayList<>(committed);
        if (head != null && nowMs - head.timeMs < lifetime
            && (result.isEmpty() || !result.getLast().point.equals(head.point))) result.add(head);
        return List.copyOf(result);
    }

    public static double alpha(Sample sample, long nowMs, long lifetimeMs) {
        if (sample == null || lifetimeMs <= 0) return 0;
        double remaining = 1 - (double) Math.max(0, nowMs - sample.timeMs) / lifetimeMs;
        double fade = Math.max(0, Math.min(1, remaining / .55));
        return fade * fade * (3 - 2 * fade);
    }
}
