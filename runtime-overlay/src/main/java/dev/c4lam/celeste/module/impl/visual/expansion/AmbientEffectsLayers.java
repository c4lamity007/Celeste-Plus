package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** Immutable layer snapshots and bounded, independent effect lifetimes. */
public final class AmbientEffectsLayers {
    public static final int MAX_PARTICLES = 256 * AmbientField.DENSITY_COPIES;
    public record Layer(AmbientField.Style style, double density, double size, int color) {
        public Layer(AmbientField.Style style,double density,double size) { this(style,density,size,defaultColor(style)); }
        public Layer {
            color &= 0xFFFFFF;
            density = range(density, 0, 1, .6);
            size = range(size, .5, 2, 1);
        }
    }
    private AmbientEffectsLayers() { }
    public static int defaultColor(AmbientField.Style style) {
        if(style==null)return 0xA6FFE1;
        return switch(style) {
            case LIGHT_STRANDS -> 0xBFA6FF;
            case CRYSTALS -> 0x6BDEFF;
            case SNOW -> 0xE3F5FF;
            case LEAVES -> 0xD98739;
            case TESSERACTS -> 0x48ACFF;
            case TRON_TRAILS -> 0x57DDEB;
            default -> 0xA6FFE1;
        };
    }
    public static int color(Layer layer,double seconds,double phase) {
        return AmbientEffectsVisualAtmosphere.tint(layer.color(),seconds,phase);
    }

    public static List<Layer> normalize(List<Layer> input) {
        if (input == null || input.isEmpty()) return List.of();
        EnumSet<AmbientField.Style> seen = EnumSet.noneOf(AmbientField.Style.class);
        List<Layer> result = new ArrayList<>(AmbientField.Style.values().length);
        for (Layer layer : input) {
            if (layer != null && layer.style() != null && seen.add(layer.style())) result.add(layer);
        }
        return List.copyOf(result);
    }

    /** Every enabled particle style reserves a share; trails have their own fixed geometry bound. */
    public static int particleCap(List<Layer> layers, AmbientField.Style style) {
        if (style == null || style == AmbientField.Style.TRON_TRAILS || style == AmbientField.Style.LIGHT_STRANDS) return 0;
        int count = 0;
        boolean present = false;
        for (Layer layer : layers) if (layer.density() > 0 && layer.style() != AmbientField.Style.TRON_TRAILS && layer.style() != AmbientField.Style.LIGHT_STRANDS) {
            count++;
            present |= layer.style() == style;
        }
        if (!present) return 0;
        int original = style == AmbientField.Style.TESSERACTS ? AmbientTesseractField.MAX_PARTICLES : 128 * AmbientField.DENSITY_COPIES;
        return Math.min(original, MAX_PARTICLES / count);
    }

    private static double range(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }

    public static final class State {
        private final AmbientTesseractField tesseracts;
        private final AmbientTronField trails;
        private final AmbientEffectsJumpCircleAnimation.Clock clock = new AmbientEffectsJumpCircleAnimation.Clock();
        private EnumSet<AmbientField.Style> active = EnumSet.noneOf(AmbientField.Style.class);
        private Object world;

        public State(long seed) { tesseracts = new AmbientTesseractField(seed); trails = new AmbientTronField(seed); }
        public AmbientTesseractField tesseracts() { return tesseracts; }
        public AmbientTronField trails() { return trails; }
        public boolean hasWorld() { return world != null; }
        public boolean isWorld(Object candidate) { return world == candidate; }

        public double sample(Object currentWorld, long now, boolean paused, List<Layer> layers) {
            if (world != currentWorld) { reset(); world = currentWorld; }
            EnumSet<AmbientField.Style> next = EnumSet.noneOf(AmbientField.Style.class);
            for (Layer layer : layers) if (layer.density() > 0) next.add(layer.style());
            if (active.contains(AmbientField.Style.TESSERACTS) && !next.contains(AmbientField.Style.TESSERACTS)) tesseracts.reset();
            if (active.contains(AmbientField.Style.TRON_TRAILS) && !next.contains(AmbientField.Style.TRON_TRAILS)) trails.reset();
            active = next;
            return clock.sample(now, paused);
        }

        public void reset() {
            world = null;
            active.clear();
            clock.reset();
            tesseracts.reset();
            trails.reset();
        }
    }
}
