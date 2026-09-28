package dev.c4lam.celeste.hud;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Observes real removals after an effect's countdown, never a pre-expiry warning. */
public final class DynamicIslandEffectExpiry {
    private final Map<String, Effect> previous = new LinkedHashMap<>();
    private int previousTick;
    private long suppressUntilTick = Long.MIN_VALUE;
    private boolean primed;

    public List<Effect> update(int tick, boolean drinkingMilk, List<Effect> effects) {
        if (primed && tick == previousTick) return List.of();
        int elapsed = tick - previousTick;
        // A long hidden-HUD interval is not evidence of natural expiration.
        boolean continuous = primed && elapsed > 0 && elapsed <= 5;
        if (drinkingMilk) suppressUntilTick = (long) tick + 3L;
        Map<String, Effect> current = new LinkedHashMap<>();
        for (Effect effect : effects) current.put(effect.texture(), effect);
        List<Effect> expired = new ArrayList<>();
        if (continuous && tick > suppressUntilTick) {
            for (Effect effect : previous.values()) {
                if (!current.containsKey(effect.texture()) && effect.ticks() > 0
                    && effect.ticks() <= elapsed) expired.add(effect);
            }
        }
        previous.clear();
        previous.putAll(current);
        previousTick = tick;
        primed = true;
        return List.copyOf(expired);
    }

    public void reset() {
        previous.clear();
        previousTick = 0;
        suppressUntilTick = Long.MIN_VALUE;
        primed = false;
    }

    public record Effect(String name, String texture, int ticks) { }
}
