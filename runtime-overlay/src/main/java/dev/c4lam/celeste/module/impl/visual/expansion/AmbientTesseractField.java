package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Nearby world cells own independent random births; camera motion never moves them. */
public final class AmbientTesseractField {
    public static final int MAX_PARTICLES = 192 * AmbientField.DENSITY_COPIES;
    public static final double CELL = 4.0;
    public static final double RANGE = 22.0;
    public static final int CELL_RADIUS = 6;
    public static final double MIN_SPEED = .14, MAX_SPEED = .38;
    private static final double MAX_DRIFT = MAX_SPEED * 6.5;
    private static final double AIR_FADE = .35;
    private static final double SLOT = 14.0;
    public record Candidate(AmbientField.Mote mote, long priority, int cellX, int cellY, int cellZ) { }
    private long seed;
    public AmbientTesseractField(long seed) { this.seed = mix(seed); }
    /** A new module/world lifetime has no surviving particle positions or ages. */
    public void reset() { seed = mix(seed + 0x9E3779B97F4A7C15L); }
    public AmbientField.Mote sample(int x, int y, int z, double seconds) {
        return sample(x, y, z, seconds, seed);
    }
    public List<Candidate> candidates(double eyeX, double eyeY, double eyeZ, double seconds, double density) {
        return candidates(eyeX, eyeY, eyeZ, seconds, density, seed);
    }
    /** Reserve potential cells before life opacity, camera direction, or air/terrain tests. */
    public static List<Candidate> candidates(double eyeX, double eyeY, double eyeZ, double seconds, double density, long seed) {
        if (!Double.isFinite(eyeX + eyeY + eyeZ) || !Double.isFinite(density) || density <= 0) return List.of();
        int cx = (int)Math.floor(eyeX / CELL), cy = (int)Math.floor(eyeY / CELL), cz = (int)Math.floor(eyeZ / CELL);
        List<Candidate> candidates = new ArrayList<>(MAX_PARTICLES * 2);
        for (int x = cx - CELL_RADIUS; x <= cx + CELL_RADIUS; x++)
            for (int y = cy - CELL_RADIUS; y <= cy + CELL_RADIUS; y++)
                for (int z = cz - CELL_RADIUS; z <= cz + CELL_RADIUS; z++) {
                    if (!AmbientField.occupied(x, y, z, density) || !mayBeVisible(x, y, z, eyeX, eyeY, eyeZ)) continue;
                    for (int lane=0;lane<AmbientField.DENSITY_COPIES;lane++) {
                        AmbientField.Mote mote = sample(x,y,z,seconds,lane==0?seed:mix(seed ^ lane*0xD1B54A32D192ED03L));
                        double dx = mote.x() - eyeX, dy = mote.y() - eyeY, dz = mote.z() - eyeZ;
                        if (dx * dx + dy * dy + dz * dz >= RANGE * RANGE) continue;
                        candidates.add(new Candidate(mote, priority(x, y, z), x, y, z));
                    }
                }
        candidates.sort(Comparator.comparingLong(Candidate::priority));
        if (candidates.size() > MAX_PARTICLES) candidates.subList(MAX_PARTICLES, candidates.size()).clear();
        return List.copyOf(candidates);
    }
    /** The last selected ranks vanish before an entering boundary cell can displace them. */
    public static double capFade(int rank) {
        return AmbientField.smooth((MAX_PARTICLES - 1.0 - rank) / (32.0 * AmbientField.DENSITY_COPIES));
    }
    public static AmbientField.Mote sample(int x, int y, int z, double seconds, long seed) {
        long cell = mix(seed ^ x * 0x632BE59BD9B4E019L ^ y * 0x9E3779B97F4A7C15L ^ z * 0x94D049BB133111EBL);
        double time = Double.isFinite(seconds) ? Math.max(0, Math.min(1.0E9, seconds)) : 0;
        double shifted = time + unit(cell) * SLOT;
        long generation = (long) Math.floor(shifted / SLOT);
        long birth = mix(cell ^ generation * 0xD1B54A32D192ED03L);
        double life = 7.0 + unit(mix(birth + 1)) * 6.0;
        double delay = unit(mix(birth + 2)) * (SLOT - life);
        double age = shifted - generation * SLOT - delay;
        double alpha = AmbientField.smooth(age / 1.15) * AmbientField.smooth((life - age) / 1.45);
        double driftTime = Math.max(0, Math.min(life, age)) - life * .5;
        // One uniform 3D direction and nonzero speed for the whole life. No orbit or sway.
        double vertical = signed(birth + 4), azimuth = unit(mix(birth + 6)) * Math.PI * 2;
        double horizontal = Math.sqrt(Math.max(0, 1 - vertical * vertical));
        double travel = (MIN_SPEED + unit(mix(birth + 8)) * (MAX_SPEED - MIN_SPEED)) * driftTime;
        double px = (x + .18 + unit(mix(birth + 3)) * .64) * CELL + horizontal * Math.cos(azimuth) * travel;
        double py = (y + .18 + unit(mix(birth + 5)) * .64) * CELL + vertical * travel;
        double pz = (z + .18 + unit(mix(birth + 7)) * .64) * CELL + horizontal * Math.sin(azimuth) * travel;
        double radius = .085 + unit(mix(birth + 9)) * .055;
        double yaw = unit(mix(birth + 10)) * Math.PI * 2 + angularSpeed(birth + 11, .24, .48) * driftTime;
        double pitch = unit(mix(birth + 12)) * Math.PI * 2 + angularSpeed(birth + 13, .14, .30) * driftTime;
        return new AmbientField.Mote(px, py, pz, radius, yaw, pitch, alpha, 0x48ACFF);
    }
    /** Includes maximum lifetime drift, so coarse rejection never clips a moving mote. */
    public static boolean mayBeVisible(int x, int y, int z, double eyeX, double eyeY, double eyeZ) {
        double dx = distanceToCell(x, eyeX), dy = distanceToCell(y, eyeY), dz = distanceToCell(z, eyeZ);
        return dx * dx + dy * dy + dz * dz <= RANGE * RANGE;
    }
    private static double distanceToCell(int cell, double eye) {
        double low = cell * CELL - MAX_DRIFT, high = (cell + 1) * CELL + MAX_DRIFT;
        return eye < low ? low - eye : eye > high ? eye - high : 0;
    }
    /** Equal vertical/horizontal reach, fading over the final four blocks. */
    public static double visibility(double dx, double dy, double dz) {
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return AmbientField.smooth((RANGE - distance) / 4.0) * AmbientField.smooth((distance - 1.15) / 1.85);
    }
    /** Fixed world-cell priority keeps the cap spatially spread without distance-order churn. */
    public static long priority(int x, int y, int z) {
        return mix(x * 0x632BE59BD9B4E019L ^ y * 0x9E3779B97F4A7C15L ^ z * 0x94D049BB133111EBL);
    }
    /** Circumscribes every rotated corner and the widest glow beam. */
    public static double bounds(double radius) { return radius * 1.9; }
    @FunctionalInterface public interface AirCell { boolean clear(int x, int y, int z); }
    /** No draw can overlap a non-air/unloaded cell; approaching surfaces fades first. */
    public static double airVisibility(double x, double y, double z, double radius, AirCell air) {
        if (!Double.isFinite(x + y + z + radius) || radius <= 0 || radius > .28) return 0;
        double bound = bounds(radius), reach = bound + AIR_FADE, nearest = AIR_FADE;
        for (int bx = (int)Math.floor(x - reach); bx <= (int)Math.floor(x + reach); bx++)
            for (int by = (int)Math.floor(y - reach); by <= (int)Math.floor(y + reach); by++)
                for (int bz = (int)Math.floor(z - reach); bz <= (int)Math.floor(z + reach); bz++) {
                    if (air.clear(bx, by, bz)) continue;
                    double dx = Math.max(Math.max(bx - x, x - bx - 1), 0);
                    double dy = Math.max(Math.max(by - y, y - by - 1), 0);
                    double dz = Math.max(Math.max(bz - z, z - bz - 1), 0);
                    double gap = Math.max(dx, Math.max(dy, dz)) - bound;
                    if (gap <= 0) return 0;
                    nearest = Math.min(nearest, gap);
                }
        return AmbientField.smooth(nearest / AIR_FADE);
    }
    /** Each life turns gently in its own direction; no almost-stationary cubes. */
    private static double angularSpeed(long seed, double minimum, double maximum) {
        double direction = signed(seed);
        return Math.copySign(minimum + (maximum - minimum) * Math.abs(direction), direction);
    }
    private static double signed(long x) { return unit(mix(x)) * 2.0 - 1.0; }
    private static double unit(long x) { return (x >>> 11) * 0x1.0p-53; }
    private static long mix(long x) { x=(x^(x>>>30))*0xBF58476D1CE4E5B9L; x=(x^(x>>>27))*0x94D049BB133111EBL; return x^(x>>>31); }
}
