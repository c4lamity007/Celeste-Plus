package dev.c4lam.celeste.hud;

import java.awt.Color;

/** One flat near-black surface shared by every HUD; no scene blur or glass tint. */
public final class HudSurface {
    private static final Color BACKGROUND = new Color(7, 7, 10, 239);

    private HudSurface() { }

    public static Color color(float opacity) {
        float safe = Float.isFinite(opacity) ? Math.clamp(opacity, 0.0f, 1.0f) : 0.0f;
        return new Color(BACKGROUND.getRed(), BACKGROUND.getGreen(), BACKGROUND.getBlue(),
            Math.round(BACKGROUND.getAlpha() * safe));
    }

    public static float radius(HudElementType type, float width, float height) {
        if (!Float.isFinite(width) || !Float.isFinite(height) || width <= 0 || height <= 0) return 0;
        float limit = Math.min(width, height) * 0.5f;
        return switch (type) {
            case ARRAYLIST -> 0.0f;
            case DYNAMIC_ISLAND -> limit;
            case SPOTIFY -> Math.min(11.0f, limit);
            case TARGET_HUD -> Math.min(7.0f, limit);
            default -> Math.min(6.0f, limit);
        };
    }
}
