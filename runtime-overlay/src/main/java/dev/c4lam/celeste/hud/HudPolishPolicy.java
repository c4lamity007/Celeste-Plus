package dev.c4lam.celeste.hud;

/** Bounded, frame-independent layout and interaction math shared by the HUD. */
public final class HudPolishPolicy {
    public static final float MEDIA_HEIGHT = 72.0F;
    public static final float MEDIA_CONTROL_Y = 60.0F;
    public static final float MEDIA_CONTROL_HIT_TOP = 50.0F;
    public static final float MEDIA_CONTROL_HIT_HEIGHT = 20.0F;

    private HudPolishPolicy() { }

    public static String lyricText(String text) {
        return text == null ? "" : text.replace("\u2026", "").replace("...", "");
    }

    public static String lyricPrefix(String text, int end) {
        return lyricText(text.substring(0, Math.clamp(end, 0, text.length())));
    }

    public static float lyricScroll(float fullWidth, float viewportWidth, float highlight) {
        if (!Float.isFinite(fullWidth) || !Float.isFinite(viewportWidth)
                || fullWidth <= viewportWidth || viewportWidth <= 0) return 0;
        float progress = Float.isFinite(highlight) ? Math.clamp(highlight, 0, 1) : 0;
        return Math.clamp(fullWidth * progress - viewportWidth * .72F, 0, fullWidth - viewportWidth);
    }

    public static float mediaWidth(float titleWidth, float artistWidth) {
        return Math.clamp(Math.max(titleWidth, artistWidth) + 54, 130, 196);
    }

    public static float mediaButton(int index, float width) {
        float safeWidth = Float.isFinite(width) ? Math.max(0.0F, width) : 0.0F;
        float padding = Math.min(8.0F, safeWidth * 0.1F);
        float cell = Math.max(0.0F, safeWidth - padding * 2.0F) / 4.0F;
        return padding + (Math.clamp(index, 0, 3) + 0.5F) * cell;
    }

    public static float mediaButtonCell(float width) {
        float safeWidth = Float.isFinite(width) ? Math.max(0.0F, width) : 0.0F;
        float padding = Math.min(8.0F, safeWidth * 0.1F);
        return Math.max(0.0F, safeWidth - padding * 2.0F) / 4.0F;
    }

    /** Square destination sizes preserve each raster's aspect; back/next intentionally match. */
    public static float mediaIconSize(int index) {
        return switch (Math.clamp(index, 0, 3)) {
            case 0, 2 -> 15.5F;
            case 1 -> 14.5F;
            default -> 13.5F;
        };
    }

    public static float fontSize(float measuredAtMaximum, float available, float minimum, float maximum) {
        return measuredAtMaximum <= available || measuredAtMaximum <= 0 ? maximum
            : Math.clamp(maximum * available / measuredAtMaximum, minimum, maximum);
    }

    public static final class Interaction {
        private boolean hovered;
        private float hover;
        private float press;
        public void hovered(boolean value) { hovered = value; }
        public void click() { press = 1; }
        public void update(float dt) {
            float seconds = Float.isFinite(dt) ? Math.clamp(dt, 0, 1) : 0;
            hover += ((hovered ? 1 : 0) - hover) * (1 - (float) Math.exp(-18 * seconds));
            press *= (float) Math.exp(-13 * seconds);
        }
        public float scale() { return 1 + .015F * hover - .025F * press; }
        public float hover() { return hover; }
        public void reset() { hovered = false; hover = 0; press = 0; }
    }
}
