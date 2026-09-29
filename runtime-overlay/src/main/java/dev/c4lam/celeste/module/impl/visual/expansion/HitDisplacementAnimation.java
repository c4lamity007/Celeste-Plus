package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.ArrayList;
import java.util.List;

/** Bounded hit-lens animation. No world, graphics or input state is retained here. */
public final class HitDisplacementAnimation {
    public static final int MAX_PULSES = 4;
    public static final double DURATION = .68;
    public record Frame(float x, float y, float radius, float strength, int color, float release) { }
    private record Pulse(long started, float x, float y, float size, int color) { }
    private final ArrayList<Pulse> pulses = new ArrayList<>(MAX_PULSES);

    public synchronized void pulse(long now, double x, double y, int color, double size) {
        expire(now);
        if (!Double.isFinite(x) || !Double.isFinite(y)) return;
        if (!Double.isFinite(size)) size = 1;
        if (pulses.size() == MAX_PULSES) pulses.removeFirst();
        pulses.add(new Pulse(now, (float) Math.clamp(x, 0, 1), (float) Math.clamp(y, 0, 1),
            (float) Math.clamp(size, .35, 2.5), color & 0xffffff));
    }

    public synchronized List<Frame> sample(long now) {
        expire(now);
        if (pulses.isEmpty()) return List.of();
        ArrayList<Frame> result = new ArrayList<>(pulses.size());
        for (Pulse pulse : pulses) {
            double age = (now - pulse.started) * 1.0e-9;
            Frame frame = at(age, pulse.x, pulse.y, pulse.size, pulse.color);
            if (frame.strength > 0 && frame.radius > 0) result.add(frame);
        }
        return List.copyOf(result);
    }

    public static Frame at(double age, float x, float y, float size, int color) {
        if (!Double.isFinite(age) || age <= 0 || age >= DURATION)
            return new Frame(x, y, 0, 0, color, 1);
        double growth = smooth(age / .24);
        double release = smooth((age - .32) / .36);
        double fade = smooth(age / .065) * (1 - smooth((age - .38) / .30));
        return new Frame(x, y, (float) (.125 * size * growth * (1 - .86 * release)),
            (float) fade, color, (float) release);
    }

    private static double smooth(double x) { x = Math.clamp(x, 0, 1); return x * x * (3 - 2 * x); }
    private void expire(long now) { pulses.removeIf(p -> now < p.started || (now - p.started) * 1.0e-9 >= DURATION); }
    public synchronized void reset() { pulses.clear(); }
    public synchronized int size() { return pulses.size(); }
}
