package dev.c4lam.celeste.module.impl.visual;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.util.math.MathHelper;

/** Animated lightmap state; the user's gamma option is never read or written here. */
public final class FullbrightFeature implements ModuleFeature {
    private static double gammaStrength;
    private static double nightVisionStrength;
    private static double gammaTarget;
    private static double nightVisionTarget;
    private static double transitionMs = 300.0D;
    private static long lastSampleNanos = System.nanoTime();

    @Override
    public String name() {
        return "Fullbright";
    }

    @Override
    public void onEnable(ModuleContext context) {
        setEnabledState(context, true);
    }

    @Override
    public void onStartTick(ModuleContext context) {
        setEnabledState(context, true);
    }

    @Override
    public void onDisable(ModuleContext context) {
        synchronized (FullbrightFeature.class) {
            advance(System.nanoTime());
            gammaTarget = 0.0D;
            nightVisionTarget = 0.0D;
        }
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        synchronized (FullbrightFeature.class) {
            gammaStrength = 0.0D;
            nightVisionStrength = 0.0D;
            gammaTarget = 0.0D;
            nightVisionTarget = 0.0D;
            lastSampleNanos = System.nanoTime();
        }
    }

    /** Vanilla refreshes at 20 Hz; only an unsettled fade needs frame-rate uploads. */
    public static synchronized boolean isTransitioning() {
        return gammaStrength != gammaTarget || nightVisionStrength != nightVisionTarget;
    }

    /** Adjusts only the temporary gamma scalar uploaded into this frame's lightmap UBO. */
    public static synchronized float adjustGammaLightmap(float vanilla) {
        advance(System.nanoTime());
        if (gammaStrength <= 0.0D) {
            return vanilla;
        }
        return Math.max(vanilla, MathHelper.lerp((float) gammaStrength, vanilla, 1.0F));
    }

    /** Emulates the visual lightmap contribution without adding a status effect. */
    public static synchronized float adjustNightVisionLightmap(float vanilla) {
        advance(System.nanoTime());
        double exposure = Math.max(nightVisionStrength, gammaStrength * 0.35D);
        if (exposure <= 0.0D) {
            return vanilla;
        }
        // BrightnessFactor is a [0,1] mix weight, not an exponent. A gentle
        // exposure contribution still lights dark areas at vanilla gamma=1
        // without extrapolating/clipping that shader's RGB mix.
        return Math.max(vanilla, (float) exposure);
    }

    private static void setEnabledState(ModuleContext context, boolean enabled) {
        synchronized (FullbrightFeature.class) {
            advance(System.nanoTime());
            Mode mode = Mode.from(context.mode("Mode", "Gamma"));
            double amount = enabled ? MathHelper.clamp(context.slider("Strength", 1.0D), 0.0D, 1.0D) : 0.0D;
            transitionMs = MathHelper.clamp(context.slider("Transition", 300.0D), 0.0D, 1500.0D);
            gammaTarget = mode == Mode.GAMMA ? amount : 0.0D;
            nightVisionTarget = mode == Mode.NIGHT_VISION ? amount : 0.0D;
        }
    }

    private static void advance(long now) {
        double elapsed = Math.max(0.0D, (now - lastSampleNanos) / 1_000_000_000.0D);
        lastSampleNanos = now;
        // Independent channels crossfade when modes change. The old contribution
        // decays instead of vanishing immediately on the next lightmap upload.
        double blend = transitionMs <= 0.0D ? 1.0D : 1.0D - Math.exp(-3.0D * elapsed * 1000.0D / transitionMs);
        gammaStrength = approach(gammaStrength, gammaTarget, blend);
        nightVisionStrength = approach(nightVisionStrength, nightVisionTarget, blend);
    }

    private static double approach(double current, double target, double blend) {
        double value = current + (target - current) * blend;
        return Math.abs(target - value) < 1.0E-4D ? target : value;
    }

    private enum Mode {
        GAMMA,
        NIGHT_VISION;

        private static Mode from(String value) {
            return value != null && value.equalsIgnoreCase("Night Vision") ? NIGHT_VISION : GAMMA;
        }
    }
}
