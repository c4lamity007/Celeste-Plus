package dev.c4lam.celeste.gui;

/** Pixel-stable geometry shared by the block and storage picker screens. */
public final class BlockPickerLayout {
    public static final float PANEL_WIDTH = 410.0F;
    public static final float PANEL_HEIGHT = 354.0F;
    public static final Rect SEARCH = new Rect(16.0F, 51.0F, 378.0F, 25.0F);
    public static final Rect LIST = new Rect(16.0F, 115.0F, 378.0F, 184.0F);
    public static final float BUTTON_HEIGHT = 22.0F;
    public static final float ROW_HEIGHT = 29.0F;

    private BlockPickerLayout() { }

    /** Top edge that centers one MSDF line inside a local-space box. */
    public static float centeredTextY(float top, float height, float textHeight) {
        if (!Float.isFinite(top) || !Float.isFinite(height) || !Float.isFinite(textHeight)) {
            return 0.0F;
        }
        return top + Math.max(0.0F, height - Math.max(0.0F, textHeight)) * 0.5F;
    }

    /** Top edge that centers a title/detail pair while preserving a fixed gap. */
    public static float centeredPairY(float top, float height, float firstHeight,
                                      float secondHeight, float gap) {
        float safeGap = Float.isFinite(gap) ? Math.max(0.0F, gap) : 0.0F;
        float content = Math.max(0.0F, firstHeight) + safeGap + Math.max(0.0F, secondHeight);
        return centeredTextY(top, height, content);
    }

    public record Rect(float x, float y, float width, float height) {
        public Rect {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(width)
                || !Float.isFinite(height) || width < 0.0F || height < 0.0F) {
                throw new IllegalArgumentException("Picker rectangle must be finite and non-negative");
            }
        }

        public float right() { return x + width; }
        public float bottom() { return y + height; }
    }
}
