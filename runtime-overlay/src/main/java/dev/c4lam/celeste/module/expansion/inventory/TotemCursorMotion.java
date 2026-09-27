package dev.c4lam.celeste.module.expansion.inventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/** Pure, deterministic GUI-pixel cursor motion; never sends input or changes inventory. */
public final class TotemCursorMotion {
    public static final double DEFAULT_SPEED = 650.0;
    private static final double MAX_COORDINATE = 1_000_000.0;
    private static final double MAX_FRAME_SECONDS = .1;

    public record Point(double x, double y) { }
    public record Box(double x, double y, double width, double height) {
        public Point center() { return new Point(x + width * .5, y + height * .5); }
        public boolean valid() {
            return finiteCoordinate(x) && finiteCoordinate(y) && Double.isFinite(width) && Double.isFinite(height)
                && width >= 4 && height >= 4 && width <= MAX_COORDINATE && height <= MAX_COORDINATE
                && finiteCoordinate(x + width) && finiteCoordinate(y + height);
        }
        public boolean contains(Point point) {
            return valid() && validPoint(point) && point.x >= x && point.x < x + width
                && point.y >= y && point.y < y + height;
        }
        private boolean containsInterior(Point point) {
            return contains(point) && point.x >= x + 1 && point.x <= x + width - 1
                && point.y >= y + 1 && point.y <= y + height - 1;
        }
    }
    public record Candidate(int inventoryIndex, Box bounds) { }
    public record Step(Point position, boolean dwelling, boolean reactionReady) { }

    private Box target;
    private boolean haveFrame, dwelling;
    private long previousNanos, reactionStartNanos;
    private double orbitAngle;
    private Point lastFinitePosition;

    /**
     * Main storage only (9..35), ordered from the actual inventory-opening cursor
     * origin. The screen/recipe-book layout is represented by the supplied bounds.
     */
    public static List<Candidate> centerOutMainSlots(List<Candidate> candidates, Point origin) {
        if (candidates == null || !validPoint(origin)) return List.of();
        var unique = new TreeMap<Integer, Candidate>();
        for (Candidate candidate : candidates) {
            if (candidate != null && candidate.inventoryIndex >= 9 && candidate.inventoryIndex <= 35
                && candidate.bounds != null && candidate.bounds.valid())
                unique.putIfAbsent(candidate.inventoryIndex, candidate);
        }
        var result = new ArrayList<>(unique.values());
        result.sort(Comparator.comparingDouble((Candidate candidate) -> distanceSquared(candidate.bounds.center(), origin))
            .thenComparingInt(Candidate::inventoryIndex));
        return List.copyOf(result);
    }

    /**
     * Call once per rendered frame using the actual current GUI cursor position.
     * Speed is GUI pixels/second (100..2000); reaction is milliseconds (0..500).
     * The reaction phase begins only after the ACTUAL cursor enters the target's
     * one-pixel inset. During that phase a small, bounded orbit keeps it moving.
     * Call reset after an action/target identity change, even for identical bounds.
     * Physical-user movement arbitration belongs to the caller, not this helper.
     */
    public Step step(Point actualCursor, Box slot, long nowNanos, double speed, long reactionMillis) {
        if (!validPoint(actualCursor) || slot == null || !slot.valid()) {
            Point safe = safePoint(actualCursor, lastFinitePosition);
            reset();
            return new Step(safe, false, false);
        }
        Point current = actualCursor;
        double seconds = 0;
        boolean targetChanged = !slot.equals(target);
        if (targetChanged) {
            reset();
            target = slot;
        }
        if (haveFrame) {
            long elapsed = nowNanos - previousNanos;
            if (elapsed < 0) {
                // A clock discontinuity cannot complete a reaction early.
                dwelling = false;
                haveFrame = false;
            } else seconds = Math.min(MAX_FRAME_SECONDS, elapsed * 1e-9);
        }
        previousNanos = nowNanos;
        haveFrame = true;
        speed = Double.isFinite(speed) ? Math.clamp(speed, 100, 2000) : DEFAULT_SPEED;
        long duration = Math.clamp(reactionMillis, 0, 500) * 1_000_000L;

        if (dwelling && !slot.containsInterior(current)) dwelling = false;
        if (!dwelling && slot.containsInterior(current)) {
            dwelling = true;
            reactionStartNanos = nowNanos; // Explicit flag: timestamp zero is valid.
            Point center = slot.center();
            orbitAngle = Math.atan2(current.y - center.y, current.x - center.x);
        }
        Point next;
        if (!dwelling) {
            next = toward(current, slot.center(), speed * seconds);
        } else {
            Point center = slot.center();
            double radius = Math.min(3, Math.min(slot.width, slot.height) * .18);
            double driftSpeed = Math.clamp(speed * .06, 12, 42);
            orbitAngle = (orbitAngle + driftSpeed * .55 / radius * seconds) % (Math.PI * 2);
            Point orbit = new Point(center.x + Math.cos(orbitAngle) * radius,
                center.y + Math.sin(orbitAngle) * radius);
            // Both endpoints are inside the inset, so the entire movement stays
            // in the same slot. Following from the actual point avoids a snap.
            next = toward(current, orbit, driftSpeed * seconds);
        }
        lastFinitePosition = next;
        long reacted = nowNanos - reactionStartNanos;
        return new Step(next, dwelling, dwelling && reacted >= 0 && reacted >= duration);
    }

    /** Clears phase/timing only; does not emit a new cursor position or input. */
    public void reset() {
        target = null;
        haveFrame = false;
        dwelling = false;
        previousNanos = 0;
        reactionStartNanos = 0;
        orbitAngle = 0;
        lastFinitePosition = null;
    }

    private static Point toward(Point current, Point goal, double limit) {
        double dx = goal.x - current.x, dy = goal.y - current.y;
        double distance = Math.hypot(dx, dy);
        if (distance == 0 || limit <= 0) return current;
        double fraction = Math.min(1, limit / distance);
        return new Point(current.x + dx * fraction, current.y + dy * fraction);
    }
    private static double distanceSquared(Point point, Point origin) {
        double dx = point.x - origin.x, dy = point.y - origin.y;
        return dx * dx + dy * dy;
    }
    private static boolean finiteCoordinate(double coordinate) {
        return Double.isFinite(coordinate) && Math.abs(coordinate) <= MAX_COORDINATE;
    }
    private static boolean validPoint(Point point) {
        return point != null && finiteCoordinate(point.x) && finiteCoordinate(point.y);
    }
    private static Point safePoint(Point point, Point previous) {
        if (validPoint(point)) return point;
        if (validPoint(previous)) return previous;
        return new Point(0, 0);
    }
}
