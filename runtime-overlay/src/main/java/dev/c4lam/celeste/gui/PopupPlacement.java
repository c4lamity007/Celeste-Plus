package dev.c4lam.celeste.gui;

/** Places an anchored popup inside the visible screen, preferring below its control. */
public final class PopupPlacement {
    private PopupPlacement() { }

    public record Position(float x, float y) { }

    public static Position place(float anchorX, float anchorY, float anchorWidth, float anchorHeight,
                                 float popupWidth, float popupHeight, float left, float top,
                                 float viewportWidth, float viewportHeight) {
        float x = anchorX + anchorWidth - popupWidth;
        float y = anchorY + anchorHeight + 6;
        if (!Float.isFinite(viewportWidth) || !Float.isFinite(viewportHeight)) return new Position(x, y);
        float right = left + Math.max(0, viewportWidth) - 4;
        float bottom = top + Math.max(0, viewportHeight) - 4;
        if (y + popupHeight > bottom) y = anchorY - popupHeight - 6;
        return new Position(clamp(x, left + 4, right - popupWidth),
            clamp(y, top + 4, bottom - popupHeight));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(Math.max(minimum, maximum), value));
    }
}
