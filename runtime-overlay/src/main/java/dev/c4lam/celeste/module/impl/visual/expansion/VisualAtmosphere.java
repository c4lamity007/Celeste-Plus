package dev.c4lam.celeste.module.impl.visual.expansion;

/** Effective visual palette only. Never edits a module's saved settings. */
public final class VisualAtmosphere {
    private static final double TAU = Math.PI * 2;
    private static volatile Preset active;
    private VisualAtmosphere() { }

    public enum Preset {
        AURORA("Aurora", 0x22CDB7, 0x446EF2),
        NEBULA("Nebula", 0x8661DF, 0xC651B8),
        SUNSET("Sunset", 0xF88C53, 0xCE5C84),
        ABYSS("Abyss", 0x2A9ADF, 0x31C3CC),
        BLACK_HOLE("Black Hole", 0xF3A851, 0xD55E32);
        private final String sky;
        private final int primary, accent;
        Preset(String sky, int primary, int accent) {
            this.sky = sky; this.primary = primary; this.accent = accent;
        }
        public String sky() { return sky; }
        public int primary() { return primary; }
        public int accent() { return accent; }
    }

    /** The feature owns activation; a missing game context or Advanced restores local settings. */
    static void configure(boolean gameContext, String preset, boolean advanced) {
        active = gameContext && !advanced ? preset(preset) : null;
    }
    static void clear() { active = null; }
    public static String activePresetName() { Preset value = active; return value == null ? null : value.sky; }
    private static Preset effective() {
        Object name = dev.c4lam.celeste.module.delivery.ModuleHooks.invoke("atmosphere", "atmosphere.palette", activePresetName());
        return name instanceof String value ? preset(value) : null;
    }
    public static boolean usesPreset() { return effective() != null; }

    public static Preset preset(String name) {
        for (Preset value : Preset.values()) if (value.sky.equalsIgnoreCase(name)) return value;
        return Preset.ABYSS;
    }

    public static String sky(String fallback) {
        Preset current = effective();
        return current == null ? fallback : current.sky;
    }

    /** RGB input/output; phase is in turns. Natural texture/alpha remain the caller's responsibility. */
    public static int tint(int fallbackRgb, double seconds, double phaseOffset) {
        Preset current = effective();
        return current == null ? fallbackRgb & 0xFFFFFF : sample(current, seconds, phaseOffset);
    }

    public static int sample(Preset preset, double seconds, double phaseOffset) {
        Preset chosen = preset == null ? Preset.ABYSS : preset;
        double t = Double.isFinite(seconds) ? seconds % (TAU / .32) : 0;
        double phase = Double.isFinite(phaseOffset) ? phaseOffset % 1 : 0;
        double blend = .5 + .5 * Math.sin(t * .32 + phase * TAU);
        int rgb = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            int a = chosen.primary >> shift & 255, b = chosen.accent >> shift & 255;
            rgb |= (int) Math.round(a + (b - a) * blend) << shift;
        }
        return rgb;
    }
}
