package dev.c4lam.celeste.gui;

/** Row scrolling with trackpad remainder and matching scrollbar geometry. */
final class RowScroll {
    private double remainder;

    int wheel(int first, int total, int visible, double amount) {
        int maximum = Math.max(0, total - visible);
        int current = Math.clamp(first, 0, maximum);
        if (!Double.isFinite(amount) || amount == 0) return current;
        remainder += Math.clamp(amount * 3, -total, total);
        int rows = (int) remainder;
        remainder -= rows;
        int result = Math.clamp((long) current - rows, 0, maximum);
        if ((result == 0 && amount > 0) || (result == maximum && amount < 0)) remainder = 0;
        return result;
    }

    void reset() { remainder = 0; }

    static float thumbHeight(int total, int visible, float trackHeight) {
        return Math.min(trackHeight, Math.max(14, visible * trackHeight / Math.max(visible, total)));
    }

    static float thumbTop(int first, int total, int visible, float trackHeight) {
        int maximum = Math.max(0, total - visible);
        return Math.clamp(first, 0, maximum) * (trackHeight - thumbHeight(total, visible, trackHeight))
            / Math.max(1, maximum);
    }

    static int fromThumb(float top, int total, int visible, float trackHeight) {
        int maximum = Math.max(0, total - visible);
        float travel = trackHeight - thumbHeight(total, visible, trackHeight);
        if (!Float.isFinite(top) || travel <= 0) return 0;
        return Math.clamp(Math.round(Math.clamp(top / travel, 0, 1) * maximum), 0, maximum);
    }
}
