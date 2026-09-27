package dev.c4lam.celeste.hud;

/** Pixel-grid geometry for the Dynamic Island's seven independently animated media bars. */
public final class WaveformPixelGeometry {
    public static final float BAR_WIDTH = HudGlyphLayout.WAVEFORM_BAR_WIDTH;
    public static final float BAR_GAP = HudGlyphLayout.WAVEFORM_BAR_GAP;
    public static final float WIDTH = HudGlyphLayout.WAVEFORM_BAR_COUNT * BAR_WIDTH
        + (HudGlyphLayout.WAVEFORM_BAR_COUNT - 1) * BAR_GAP;

    private WaveformPixelGeometry() {
    }

    /**
     * The matrix has only HUD translation and scale. Snap transformed edges,
     * then return local coordinates so the normal matrix still owns placement.
     */
    public static Bar bar(int index, float centerX, float centerY, float height,
                          float pixelsPerX, float offsetX, float pixelsPerY, float offsetY) {
        if (index < 0 || index >= HudGlyphLayout.WAVEFORM_BAR_COUNT
            || !Float.isFinite(pixelsPerX) || pixelsPerX <= 0.0F
            || !Float.isFinite(pixelsPerY) || pixelsPerY <= 0.0F) {
            throw new IllegalArgumentException("Invalid waveform bar or pixel transform");
        }
        int widthPixels = Math.max(1, Math.round(BAR_WIDTH * pixelsPerX));
        int gapPixels = Math.max(1, Math.round(BAR_GAP * pixelsPerX));
        int groupPixels = HudGlyphLayout.WAVEFORM_BAR_COUNT * widthPixels
            + (HudGlyphLayout.WAVEFORM_BAR_COUNT - 1) * gapPixels;
        int leftPixel = Math.round(centerX * pixelsPerX + offsetX - groupPixels * 0.5F)
            + index * (widthPixels + gapPixels);
        int topPixel = Math.round((centerY - height * 0.5F) * pixelsPerY + offsetY);
        int bottomPixel = Math.max(topPixel + 1,
            Math.round((centerY + height * 0.5F) * pixelsPerY + offsetY));
        return new Bar((leftPixel - offsetX) / pixelsPerX,
            (topPixel - offsetY) / pixelsPerY,
            (leftPixel + widthPixels - offsetX) / pixelsPerX,
            (bottomPixel - offsetY) / pixelsPerY);
    }

    public record Bar(float left, float top, float right, float bottom) {
    }
}
