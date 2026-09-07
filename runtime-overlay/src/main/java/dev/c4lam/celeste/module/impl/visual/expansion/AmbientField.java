package dev.c4lam.celeste.module.impl.visual.expansion;

/** Pure, world-anchored ambient field. Camera movement never seeds or advances a mote. */
public final class AmbientField {
    public static final double CELL = 5.0;
    public static final double RANGE = 22.0;
    public static final double HEIGHT = 11.0;
    public enum Style { FIREFLIES, CRYSTALS, SNOW }
    public record Mote(double x, double y, double z, double size, double yaw, double pitch,
                       double opacity, int color) { }

    private AmbientField() { }

    /** Reject only whole cells outside the visibility ellipsoid, before motion/trigonometry. */
    public static boolean mayBeVisible(int x, int y, int z, double eyeX, double eyeY, double eyeZ) {
        double dx = distanceToCell(x, eyeX), dy = distanceToCell(y, eyeY), dz = distanceToCell(z, eyeZ);
        return (dx * dx + dz * dz) / (RANGE * RANGE) + dy * dy / (HEIGHT * HEIGHT) <= 1.0;
    }

    private static double distanceToCell(int cell, double eye) {
        double low = cell * CELL, high = low + CELL;
        return eye < low ? low - eye : eye > high ? eye - high : 0.0;
    }
    public static boolean occupied(int x, int y, int z, double density) {
        return unit(hash(x, y, z)) < .75 * clamp(density);
    }

    public static Mote sample(int x, int y, int z, Style style, double seconds) {
        long seed = hash(x, y, z);
        double a = unit(mix(seed + 1)), b = unit(mix(seed + 2)), c = unit(mix(seed + 3));
        double time = Double.isFinite(seconds) ? seconds : 0.0;
        double phase = a * Math.PI * 2.0;
        double speed = style == Style.SNOW ? -.085 : style == Style.CRYSTALS ? .018 : .035;
        double cycle = b + time * speed;
        cycle -= Math.floor(cycle);
        // The recycle point is invisible, so a bounded field needs no spawn/death objects.
        double life = smooth(cycle / .14) * smooth((1.0 - cycle) / .14);
        double sway = style == Style.SNOW ? .24 : .13;
        double px = (x + .25 + a * .50) * CELL + Math.sin(time * .31 + phase) * sway;
        double py = (y + cycle) * CELL;
        double pz = (z + .25 + c * .50) * CELL + Math.cos(time * .24 + phase) * sway;
        double size = (style == Style.CRYSTALS ? .13 : style == Style.SNOW ? .075 : .035) * (.8 + c * .4);
        int color = style == Style.SNOW ? 0xE3F5FF : style == Style.CRYSTALS
            ? blend(0x6BDEFF, 0xC59AFF, a) : blend(0xA6FFE1, 0xFFE4A3, a);
        double breathing = style == Style.FIREFLIES ? .65 + .35 * Math.pow(.5 + .5 * Math.sin(time * 1.8 + phase), 2) : 1;
        return new Mote(px, py, pz, size, phase + time * .32,
            style == Style.SNOW ? time * .48 + phase : .22 * Math.sin(time * .29 + phase),
            life * breathing, color);
    }

    /** Ellipsoidal far fade plus near-camera clearance; no screen-space particles. */
    public static double visibility(double dx, double dy, double dz) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)) return 0;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double ellipsoid = Math.sqrt((dx * dx + dz * dz) / (RANGE * RANGE) + dy * dy / (HEIGHT * HEIGHT));
        return smooth((1.0 - ellipsoid) / .24) * smooth((distance - 1.15) / 1.85);
    }

    public static double smooth(double x) { x = clamp(x); return x * x * (3.0 - 2.0 * x); }
    private static double clamp(double x) { return Double.isFinite(x) ? Math.max(0, Math.min(1, x)) : 0; }
    private static long hash(int x, int y, int z) {
        return mix(x * 0x632BE59BD9B4E019L ^ y * 0x9E3779B97F4A7C15L ^ z * 0x94D049BB133111EBL);
    }
    private static long mix(long x) { x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L; x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL; return x ^ (x >>> 31); }
    private static double unit(long x) { return (x >>> 11) * 0x1.0p-53; }
    private static int blend(int a, int b, double t) {
        int r = (int) Math.round((a >> 16 & 255) * (1 - t) + (b >> 16 & 255) * t);
        int g = (int) Math.round((a >> 8 & 255) * (1 - t) + (b >> 8 & 255) * t);
        int blue = (int) Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return r << 16 | g << 8 | blue;
    }
}
