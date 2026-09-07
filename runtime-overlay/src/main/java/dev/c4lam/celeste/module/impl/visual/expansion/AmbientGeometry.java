package dev.c4lam.celeste.module.impl.visual.expansion;

/** Primitive scratch geometry for the same six-vertex/eight-face ambient cores. */
public final class AmbientGeometry {
    public static final int SCRATCH_SIZE = 21;
    private AmbientGeometry() { }

    /** Slots 0..17 are world vertices; slots 18..20 are the current face normal. */
    public static void core(double[] out, double px, double py, double pz, double radius,
                            double stretch, double yaw, double pitch) {
        double sy = Math.sin(yaw), cy = Math.cos(yaw), sp = Math.sin(pitch), cp = Math.cos(pitch);
        for (int i = 0; i < 6; i++) {
            double vx = i == 2 ? radius : i == 4 ? -radius : 0;
            double vy = i == 0 ? radius * stretch : i == 1 ? -radius * stretch : 0;
            double vz = i == 3 ? radius : i == 5 ? -radius : 0;
            double y = vy * cp - vz * sp, z = vy * sp + vz * cp;
            out[i * 3] = (vx * cy - z * sy) + px;
            out[i * 3 + 1] = y + py;
            out[i * 3 + 2] = (vx * sy + z * cy) + pz;
        }
    }

    public static void normal(double[] out, int a, int b, int c) {
        int ai = a * 3, bi = b * 3, ci = c * 3;
        double bx = out[bi] - out[ai], by = out[bi + 1] - out[ai + 1], bz = out[bi + 2] - out[ai + 2];
        double cx = out[ci] - out[ai], cy = out[ci + 1] - out[ai + 1], cz = out[ci + 2] - out[ai + 2];
        double nx = by * cz - bz * cy, ny = bz * cx - bx * cz, nz = bx * cy - by * cx;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1.0E-5) {
            out[18] = out[19] = out[20] = 0;
        } else {
            double inverse = 1.0 / length;
            out[18] = nx * inverse; out[19] = ny * inverse; out[20] = nz * inverse;
        }
    }
}
