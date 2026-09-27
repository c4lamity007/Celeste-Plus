package dev.c4lam.celeste.module.expansion.inventory;

import java.util.function.IntPredicate;

/** Exact destination policy for Hover Totem's optional hotbar reserve. */
public final class HoverTotemPolicy {
    private HoverTotemPolicy() { }

    public static boolean reserveNeeded(int sourceInventoryIndex, int configuredHotbarSlot,
                                        IntPredicate hasTotemAtHotbarSlot) {
        if (sourceInventoryIndex < 9 || hasTotemAtHotbarSlot == null) return false;
        int slot = Math.max(0, Math.min(8, configuredHotbarSlot));
        return !hasTotemAtHotbarSlot.test(slot);
    }
}
