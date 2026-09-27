package dev.c4lam.celeste.hud;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToIntFunction;

/** Keep every active effect visible, with the soonest expiring first. */
public final class EffectsHudOrder {
    private EffectsHudOrder() {
    }

    public static <T> List<T> all(Collection<T> effects, ToIntFunction<T> durationTicks) {
        return effects.stream()
            .sorted(Comparator.comparingInt(effect -> {
                int ticks = durationTicks.applyAsInt(effect);
                return ticks < 0 ? Integer.MAX_VALUE : ticks;
            }))
            .toList();
    }
}
