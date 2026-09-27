package dev.c4lam.celeste.hud;

/** Defers a single click briefly so a double click only skips, never toggles first. */
public final class DynamicIslandClicks {
    public static final long DOUBLE_CLICK_NANOS = 300_000_000L;
    public enum Action { NONE, TOGGLE, NEXT }

    private boolean pending;
    private long clickedAt;
    private String track = "";

    public Action click(long now, String currentTrack) {
        if (currentTrack == null || currentTrack.isEmpty()) {
            reset();
            return Action.NONE;
        }
        if (pending && !track.equals(currentTrack)) reset();
        if (pending && now - clickedAt >= 0 && now - clickedAt <= DOUBLE_CLICK_NANOS) {
            reset();
            return Action.NEXT;
        }
        Action expired = poll(now, currentTrack);
        pending = true;
        clickedAt = now;
        track = currentTrack;
        return expired;
    }

    public Action poll(long now, String currentTrack) {
        if (!pending) return Action.NONE;
        if (!track.equals(currentTrack) || now - clickedAt < 0) {
            reset();
            return Action.NONE;
        }
        if (now - clickedAt <= DOUBLE_CLICK_NANOS) return Action.NONE;
        reset();
        return Action.TOGGLE;
    }

    public boolean pending() { return pending; }

    public void reset() {
        pending = false;
        clickedAt = 0L;
        track = "";
    }

    /** Match the actual rounded capsule, not the invisible corners of its bounding box. */
    public static boolean contains(double mouseX, double mouseY, float x, float y, float width, float height) {
        if (!Double.isFinite(mouseX) || !Double.isFinite(mouseY)
                || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(width)
                || !Float.isFinite(height) || width <= 0 || height <= 0) return false;
        double radius = Math.min(width, height) * 0.5;
        double nearestX = Math.clamp(mouseX, x + radius, x + width - radius);
        double nearestY = Math.clamp(mouseY, y + radius, y + height - radius);
        return Math.hypot(mouseX - nearestX, mouseY - nearestY) <= radius;
    }
}
