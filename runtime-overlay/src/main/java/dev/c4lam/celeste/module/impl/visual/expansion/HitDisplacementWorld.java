package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Short-lived surface anchors; camera motion never moves a hit back to the crosshair. */
public final class HitDisplacementWorld {
    public record Point(double x, double y, double z) {
        public Point add(Point other) { return new Point(x + other.x, y + other.y, z + other.z); }
        public Point subtract(Point other) { return new Point(x - other.x, y - other.y, z - other.z); }
        public boolean finite() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z); }
    }
    public record Anchor(int entityId, Point offset, Point fallback) { }
    public record Screen(float x, float y) { }
    public record WorldFrame(Point position, float radius, float strength, int color, float release) { }
    private record Pulse(long started, Anchor anchor, float size, int color) { }
    private final ArrayList<Pulse> pulses = new ArrayList<>(HitDisplacementAnimation.MAX_PULSES);

    public synchronized void pulse(long now, Anchor anchor, int color, double size) {
        expire(now);
        if (anchor == null || anchor.offset == null || anchor.fallback == null
            || !anchor.offset.finite() || !anchor.fallback.finite()) return;
        if (pulses.size() == HitDisplacementAnimation.MAX_PULSES) pulses.removeFirst();
        pulses.add(new Pulse(now, anchor, (float) Math.clamp(Double.isFinite(size) ? size : 1, .35, 2.5), color & 0xffffff));
    }

    public synchronized List<HitDisplacementAnimation.Frame> sample(long now, Function<Anchor, Screen> project) {
        expire(now);
        List<HitDisplacementAnimation.Frame> result = new ArrayList<>(pulses.size());
        for (Pulse pulse : pulses) {
            Screen screen = project.apply(pulse.anchor);
            if (screen == null || !Float.isFinite(screen.x) || !Float.isFinite(screen.y)) continue;
            var frame = HitDisplacementAnimation.at((now - pulse.started) * 1.0e-9,
                screen.x, screen.y, pulse.size, pulse.color);
            // Do not clamp offscreen hits to an edge or put behind-camera hits at the center.
            if (frame.strength() > 0 && frame.radius() > 0) result.add(frame);
        }
        return List.copyOf(result);
    }

    /** Keep view-space positions for projection by the exact world projection uniform on the GPU. */
    public synchronized List<WorldFrame> sampleWorld(long now, Function<Anchor, Point> resolve) {
        expire(now);
        List<WorldFrame> result = new ArrayList<>(pulses.size());
        for (Pulse pulse : pulses) {
            Point point = resolve.apply(pulse.anchor);
            if (point == null || !point.finite()) continue;
            var frame = HitDisplacementAnimation.at((now - pulse.started) * 1.0e-9, 0, 0, pulse.size, pulse.color);
            if (frame.strength() > 0 && frame.radius() > 0)
                result.add(new WorldFrame(point, frame.radius(), frame.strength(), frame.color(), frame.release()));
        }
        return List.copyOf(result);
    }

    /** NDC-to-texture helper for deterministic camera fixtures; scene UVs start at bottom left. */
    public static Screen screen(double x, double y, double z, double forwardDepth) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
            || !Double.isFinite(forwardDepth) || forwardDepth <= .05 || z < -1.01 || z > 1.01) return null;
        return new Screen((float) ((x + 1) * .5), (float) ((y + 1) * .5));
    }

    /** Intersect the action's look with the actual target box, independent of the rendered camera. */
    public static Point impact(Point eye, Point direction, Point min, Point max) {
        if (eye == null || direction == null || min == null || max == null
            || !eye.finite() || !direction.finite() || !min.finite() || !max.finite()
            || min.x > max.x || min.y > max.y || min.z > max.z) return null;
        Point result = intersect(eye, direction, min, max);
        if (result != null) return result;
        // Entity attack packets identify an entity, not a hit vector. If its action ray
        // missed (for example, a moving target), anchor to its facing surface, never the HUD.
        Point center = new Point((min.x + max.x) * .5, (min.y + max.y) * .5, (min.z + max.z) * .5);
        result = intersect(eye, center.subtract(eye), min, max);
        return result != null ? result : center;
    }

    private static Point intersect(Point eye, Point dir, Point min, Point max) {
        double near = 0, far = Double.POSITIVE_INFINITY;
        double[] origin = {eye.x, eye.y, eye.z}, direction = {dir.x, dir.y, dir.z};
        double[] low = {min.x, min.y, min.z}, high = {max.x, max.y, max.z};
        if (dir.x * dir.x + dir.y * dir.y + dir.z * dir.z < 1.0e-16) return null;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(direction[axis]) < 1.0e-10) {
                if (origin[axis] < low[axis] || origin[axis] > high[axis]) return null;
            } else {
                double a = (low[axis] - origin[axis]) / direction[axis];
                double b = (high[axis] - origin[axis]) / direction[axis];
                near = Math.max(near, Math.min(a, b));
                far = Math.min(far, Math.max(a, b));
                if (near > far) return null;
            }
        }
        // At point-blank range the eye can already be inside the target box.
        // Use its exit surface rather than a zero-depth point at the player's eye.
        double distance = near > 1.0e-8 ? near : far;
        if (!Double.isFinite(distance)) return null;
        return new Point(eye.x + dir.x * distance, eye.y + dir.y * distance, eye.z + dir.z * distance);
    }

    private void expire(long now) {
        pulses.removeIf(p -> now < p.started || (now - p.started) * 1.0e-9 >= HitDisplacementAnimation.DURATION);
    }
    public synchronized int size() { return pulses.size(); }
    public synchronized void reset() { pulses.clear(); }
}
