package dev.c4lam.celeste.module.expansion.inventory;

import java.util.Objects;

/** Exempts only the inventory press inherited when a visible refill session opens. */
public final class TotemOpeningInput {
    private Object openingBinding;
    private boolean openingHold;

    public void opened(Object inventoryBinding, boolean inventoryDown) {
        openingBinding = inventoryBinding;
        openingHold = inventoryBinding != null && inventoryDown;
    }

    public boolean blocksMovement(Object inventoryBinding, boolean inventoryDown, boolean otherActionDown) {
        if (!inventoryDown || !Objects.equals(openingBinding, inventoryBinding)) openingHold = false;
        return otherActionDown || inventoryDown && !openingHold;
    }

    public void reset() {
        openingBinding = null;
        openingHold = false;
    }
}
