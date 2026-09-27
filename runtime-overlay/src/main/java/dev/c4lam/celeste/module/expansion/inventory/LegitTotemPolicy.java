package dev.c4lam.celeste.module.expansion.inventory;

/** Small deterministic policy shared by the visible refill lifecycle and its fixtures. */
public final class LegitTotemPolicy {
    private long lastCounter;
    private int pendingPops;
    private boolean autoOpenPreviously;

    public void reset(long counter, boolean autoOpen) {
        lastCounter = Math.max(0, counter);
        pendingPops = 0;
        autoOpenPreviously = autoOpen;
    }

    public void observe(long counter, boolean autoOpen) {
        counter = Math.max(0, counter);
        if (!autoOpen || !autoOpenPreviously || counter < lastCounter) {
            pendingPops = 0;
        } else if (counter > lastCounter) {
            pendingPops = (int) Math.min(2L, pendingPops + Math.min(2L, counter - lastCounter));
        }
        lastCounter = counter;
        autoOpenPreviously = autoOpen;
    }

    public boolean wantsOpen(int afterPops) { return pendingPops >= Math.clamp(afterPops, 1, 2); }
    public void openedOrCancelled() { pendingPops = 0; }

    public static int nextDestination(boolean offhandTotem, boolean reserveTotem, int hotbarSlot) {
        return !offhandTotem ? 40 : !reserveTotem ? Math.clamp(hotbarSlot, 0, 8) : -1;
    }

    public static boolean mayClose(boolean autoClose, boolean transferred, boolean emptyCursor,
                                   boolean offhandTotem, boolean reserveTotem, int age, int lastSwapAge) {
        return autoClose && transferred && emptyCursor && offhandTotem && reserveTotem && age > lastSwapAge;
    }
}
