package dev.c4lam.celeste.hud;

/** Frame-driven island morph and text crossfade. Reading state never advances it. */
public final class HudMediaMotion {
    private float width = 150.0f;
    private float height = 26.0f;
    private float progress = 1.0f;
    private String primary = "";
    private String secondary = "";
    private String previousPrimary = "";
    private String previousSecondary = "";
    private String key = "";
    private boolean initialized;

    public void update(String nextKey, String nextPrimary, String nextSecondary,
                       float targetWidth, float targetHeight, float seconds) {
        float dt = Float.isFinite(seconds) ? Math.max(0.0f, Math.min(.1f, seconds)) : 0.0f;
        if (!initialized) {
            initialized = true;
            width = targetWidth;
            height = targetHeight;
            primary = nextPrimary;
            secondary = nextSecondary;
            key = nextKey;
        } else if (!key.equals(nextKey)) {
            // Rapid seeking keeps the more visible phrase as the outgoing layer.
            if (progress >= .5f) {
                previousPrimary = primary;
                previousSecondary = secondary;
            }
            primary = nextPrimary;
            secondary = nextSecondary;
            key = nextKey;
            progress = 0.0f;
        } else {
            primary = nextPrimary;
            secondary = nextSecondary;
        }
        progress = Math.min(1.0f, progress + dt / .22f);
        width = approach(width, targetWidth, dt);
        height = approach(height, targetHeight, dt);
        if (progress == 1.0f) {
            previousPrimary = "";
            previousSecondary = "";
        }
    }

    private static float approach(float current, float target, float dt) {
        if (dt <= 0.0f) return current;
        float next = current + (target - current) * (1.0f - (float) Math.exp(-14.0f * dt));
        return Math.abs(next - target) < .02f ? target : next;
    }

    public float width() { return width; }
    public float height() { return height; }
    public float blend() { return progress * progress * (3.0f - 2.0f * progress); }
    public String primary() { return primary; }
    public String secondary() { return secondary; }
    public String previousPrimary() { return previousPrimary; }
    public String previousSecondary() { return previousSecondary; }
}
