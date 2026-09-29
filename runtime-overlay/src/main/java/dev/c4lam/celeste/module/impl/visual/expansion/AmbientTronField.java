package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.ArrayList;
import java.util.List;

/** Local, fixed-budget light trails. Each trail follows its own world-anchored XZ grid. */
public final class AmbientTronField {
    public static final int MAX_TRAILS = 24;
    public static final int MAX_SEGMENTS_PER_TRAIL = 8;
    public static final int MAX_AIR_QUERIES = MAX_TRAILS * MAX_SEGMENTS_PER_TRAIL * 2;
    private static final int STEPS = 36;
    private static final int[] DX = {1, 0, -1, 0}, DZ = {0, 1, 0, -1};
    @FunctionalInterface public interface AirCell { boolean isAir(int x, int y, int z); }
    public record Segment(long trail, double ax, double ay, double az, double bx, double by, double bz,
                          double width, float startOpacity, float endOpacity, double palette) { }

    private static final class Trail {
        final double[] x = new double[STEPS + 1], z = new double[STEPS + 1];
        final boolean[] clear = new boolean[STEPS];
        final long[] checked = new long[STEPS];
        long identity;
        double y, birth, life, speed, tail, palette;
        Trail() { java.util.Arrays.fill(checked, Long.MIN_VALUE); }
    }
    private final long seed;
    private final Trail[] slots = new Trail[MAX_TRAILS];
    private long serial;
    private double lastSeconds = Double.NaN, lastX, lastY, lastZ;

    public AmbientTronField(long seed) { this.seed = seed; }
    public void reset() { java.util.Arrays.fill(slots, null); lastSeconds = Double.NaN; serial = 0; }

    /** Fixed cell probes only, once per time slice; this never searches or loads terrain. */
    public List<Segment> sample(double eyeX, double eyeY, double eyeZ, double seconds,
                                double density, double size, AirCell air) {
        if (!Double.isFinite(eyeX) || !Double.isFinite(eyeY) || !Double.isFinite(eyeZ)
            || !Double.isFinite(seconds) || seconds < 0 || Math.abs(eyeX) > 3.1E7
            || Math.abs(eyeZ) > 3.1E7 || Math.abs(eyeY) > 1.0E6 || air == null) return List.of();
        if (Double.isFinite(lastSeconds) && (seconds < lastSeconds
            || squared(eyeX - lastX, eyeY - lastY, eyeZ - lastZ) > 48 * 48)) reset();
        lastSeconds = seconds; lastX = eyeX; lastY = eyeY; lastZ = eyeZ;
        density = finiteRange(density, 0, 1, .6); size = finiteRange(size, .5, 2, 1);
        int count = (int)Math.ceil(density * MAX_TRAILS);
        if (count == 0) return List.of();
        long slice = (long)Math.floor(seconds * 20);
        List<Segment> out = new ArrayList<>(count * MAX_SEGMENTS_PER_TRAIL);
        for (int slot = 0; slot < count; slot++) {
            Trail trail = slots[slot];
            if (trail == null || seconds >= trail.birth + trail.life) {
                trail = spawn(slot, eyeX, eyeY, eyeZ, seconds, trail == null);
                slots[slot] = trail;
            }
            double age = seconds - trail.birth;
            double life = smooth(age / .65) * smooth((trail.life - age) / 1.1);
            double weight = smooth(Math.min(1, density * MAX_TRAILS - slot));
            double head = Math.min(STEPS, Math.max(0, age * trail.speed));
            double tail = Math.max(0, head - trail.tail);
            int from = (int)Math.floor(tail), to = Math.min(STEPS, (int)Math.ceil(head));
            for (int step = from; step < to; step++) {
                double begin = Math.max(tail, step), end = Math.min(head, step + 1);
                if (end - begin < 1.0E-7) continue;
                double ax = lerp(trail.x[step], trail.x[step + 1], begin - step);
                double az = lerp(trail.z[step], trail.z[step + 1], begin - step);
                double bx = lerp(trail.x[step], trail.x[step + 1], end - step);
                double bz = lerp(trail.z[step], trail.z[step + 1], end - step);
                double visibility = visibility((ax + bx) * .5 - eyeX, trail.y - eyeY, (az + bz) * .5 - eyeZ);
                if (visibility * life * weight < .006) continue;
                if (trail.checked[step] != slice) {
                    trail.checked[step] = slice;
                    // A unit grid edge only crosses these two cells; checking both also avoids fluids.
                    trail.clear[step] = air.isAir((int)Math.floor(lerp(trail.x[step], trail.x[step + 1], .02)),
                        (int)Math.floor(trail.y), (int)Math.floor(lerp(trail.z[step], trail.z[step + 1], .02)))
                        && air.isAir((int)Math.floor(lerp(trail.x[step], trail.x[step + 1], .98)),
                        (int)Math.floor(trail.y), (int)Math.floor(lerp(trail.z[step], trail.z[step + 1], .98)));
                }
                if (!trail.clear[step]) continue;
                double alpha = life * weight * visibility * .62;
                out.add(new Segment(trail.identity, ax, trail.y, az, bx, trail.y, bz, .11 * size,
                    (float)(alpha * trailFade(begin, head, trail.tail)),
                    (float)(alpha * trailFade(end, head, trail.tail)), trail.palette));
            }
        }
        return List.copyOf(out);
    }

    private Trail spawn(int slot, double eyeX, double eyeY, double eyeZ, double seconds, boolean initial) {
        long key = mix(seed ^ (++serial * 0x632BE59BD9B4E019L) ^ slot);
        Trail t = new Trail(); t.identity = key;
        t.life = 7.0 + unit(key + 1) * 3.0; t.speed = 1.5 + unit(key + 2) * 1.6;
        t.tail = 4 + unit(key + 3) * 2.7; t.palette = unit(key + 4);
        t.birth = seconds - (initial ? unit(key + 5) * (t.life - 1.2) : 0);
        t.x[0] = Math.floor(eyeX + (unit(key + 6) * 2 - 1) * 13) + .5;
        t.z[0] = Math.floor(eyeZ + (unit(key + 7) * 2 - 1) * 13) + .5;
        t.y = Math.floor(eyeY + (unit(key + 8) * 2 - 1) * 8) + .25 + unit(key + 9) * .5;
        int direction = (int)(unit(key + 10) * 4);
        for (int step = 0; step < STEPS; step++) {
            int selected = direction;
            double best = -Double.MAX_VALUE;
            for (int turn = -1; turn <= 1; turn++) {
                int candidate = (direction + turn + 4) & 3;
                double nx = t.x[step] + DX[candidate], nz = t.z[step] + DZ[candidate];
                boolean visited = false;
                for (int prior = 0; prior <= step; prior++) if (t.x[prior] == nx && t.z[prior] == nz) { visited = true; break; }
                double away = (nx - t.x[0]) * DX[candidate] + (nz - t.z[0]) * DZ[candidate];
                double score = unit(key + 32 + step * 3L + turn) + Math.min(4, away) * .065 - (visited ? 10 : 0);
                if (score > best) { best = score; selected = candidate; }
            }
            direction = selected;
            t.x[step + 1] = t.x[step] + DX[direction]; t.z[step + 1] = t.z[step] + DZ[direction];
        }
        return t;
    }

    private static double trailFade(double at, double head, double length) { return smooth(1 - (head - at) / length); }
    private static double visibility(double x, double y, double z) {
        double radius = Math.sqrt((x*x + z*z) / (25*25) + y*y / (13*13));
        return smooth((1 - radius) / .3) * smooth((Math.sqrt(x*x + y*y + z*z) - 1.2) / 1.5);
    }
    private static double finiteRange(double v, double min, double max, double fallback) { return Double.isFinite(v) ? Math.max(min, Math.min(max, v)) : fallback; }
    private static double smooth(double v) { v = Math.max(0, Math.min(1, v)); return v*v*(3 - 2*v); }
    private static double squared(double x, double y, double z) { return x*x + y*y + z*z; }
    private static double lerp(double a, double b, double t) { return a + (b-a)*t; }
    private static long mix(long x) { x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L; x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL; return x ^ (x >>> 31); }
    private static double unit(long x) { return (mix(x) >>> 11) * 0x1.0p-53; }
}
