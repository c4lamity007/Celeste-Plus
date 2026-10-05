package dev.c4lam.celeste.module.impl.visual.expansion;

/** Finite presentation policy shared by native horizon color and the depth-aware fog shader. */
public final class ShaderFogMath {
    public static final double WORLD_PERIOD = 8192;
    private ShaderFogMath() { }

    public static int mode(String preset, int skyMode) {
        if ("Match Sky".equalsIgnoreCase(preset)) return Math.max(0, Math.min(4, skyMode));
        return CustomFogSkyboxMath.mode(preset);
    }

    public static float density(double value) { return CustomFogSkyboxMath.finiteClamp(value, .45, 0, 1); }
    public static float distance(double value) { return CustomFogSkyboxMath.finiteClamp(value, 160, 32, 512); }

    /** Every shader noise lattice is periodic, so this precision-preserving wrap has no seam. */
    public static float worldCoordinate(double value) {
        return Double.isFinite(value) ? (float) (value - Math.floor(value / WORLD_PERIOD) * WORLD_PERIOD) : 0;
    }

    public static int horizon(int mode) {
        return switch (mode) {
            case 1 -> 0x635A98;
            case 2 -> 0xD8926D;
            case 3 -> 0x193E57;
            case 4 -> 0x453C56;
            default -> 0x548F91;
        };
    }

    /** Alpha policy before bounded material modulation. No foreground, sky, or density-zero haze. */
    public static double opacity(double rayDistance, double configuredDistance, double configuredDensity) {
        if (!Double.isFinite(rayDistance) || rayDistance <= 8) return 0;
        double normalized = Math.max(0, rayDistance - 8) / distance(configuredDistance);
        double clear = Math.min(1, Math.max(0, (rayDistance - 8) / 16));
        clear = clear * clear * (3 - 2 * clear);
        return Math.min(.94, (1 - Math.exp(-normalized * density(configuredDensity) * 1.85)) * clear);
    }
}
