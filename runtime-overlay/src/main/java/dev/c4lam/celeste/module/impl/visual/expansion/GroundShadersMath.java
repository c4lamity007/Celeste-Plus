package dev.c4lam.celeste.module.impl.visual.expansion;

/** Bounded ambient terrain settings, independent of the game and GPU. */
public final class GroundShadersMath {
    private GroundShadersMath() { }

    public record Settings(float intensity, int color) { }

    public static Settings settings(double intensity, int color) {
        return new Settings((float) (Double.isFinite(intensity) ? Math.clamp(intensity, 0, 1) : .35),
            color & 0xFFFFFF);
    }

    /** Identity tokens keep mutable worlds out of extracted render states. */
    public static boolean currentFrame(long capturedGeneration, long currentGeneration,
                                       Object capturedWorld, Object currentWorld, Settings active) {
        return capturedGeneration == currentGeneration && capturedWorld != null && capturedWorld == currentWorld
            && active != null && active.intensity > 0;
    }
}
