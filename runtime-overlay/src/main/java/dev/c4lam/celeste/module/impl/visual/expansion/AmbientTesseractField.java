package dev.c4lam.celeste.module.impl.visual.expansion;

/** Nearby world cells own independent random births; camera motion never moves them. */
public final class AmbientTesseractField {
    public static final int MAX_PARTICLES = 64;
    private static final double SLOT = 14.0;
    private long seed;
    public AmbientTesseractField(long seed) { this.seed = mix(seed); }
    /** A new module/world lifetime has no surviving particle positions or ages. */
    public void reset() { seed = mix(seed + 0x9E3779B97F4A7C15L); }
    public AmbientField.Mote sample(int x, int y, int z, double seconds) {
        return sample(x, y, z, seconds, seed);
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
        // Straight, independently seeded drift; no circular/orbit/sway motion.
        double px = (x + .18 + unit(mix(birth + 3)) * .64) * AmbientField.CELL + signed(birth + 4) * .045 * driftTime;
        double py = (y + .18 + unit(mix(birth + 5)) * .64) * AmbientField.CELL + signed(birth + 6) * .032 * driftTime;
        double pz = (z + .18 + unit(mix(birth + 7)) * .64) * AmbientField.CELL + signed(birth + 8) * .045 * driftTime;
        double radius = .085 + unit(mix(birth + 9)) * .055;
        double yaw = unit(mix(birth + 10)) * Math.PI * 2 + signed(birth + 11) * .10 * driftTime;
        double pitch = unit(mix(birth + 12)) * Math.PI * 2 + signed(birth + 13) * .075 * driftTime;
        return new AmbientField.Mote(px, py, pz, radius, yaw, pitch, alpha, 0x48ACFF);
    }
    private static double signed(long x) { return unit(mix(x)) * 2.0 - 1.0; }
    private static double unit(long x) { return (x >>> 11) * 0x1.0p-53; }
    private static long mix(long x) { x=(x^(x>>>30))*0xBF58476D1CE4E5B9L; x=(x^(x>>>27))*0x94D049BB133111EBL; return x^(x>>>31); }
}
