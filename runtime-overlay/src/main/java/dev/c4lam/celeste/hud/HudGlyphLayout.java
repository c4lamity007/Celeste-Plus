package dev.c4lam.celeste.hud;

/** Pure sizing and motion rules for the owner-supplied HUD glyphs. */
public final class HudGlyphLayout {
    public static final float INLINE_SIZE = 9.0F;
    public static final float INLINE_GAP = 3.5F;
    public static final float INLINE_PADDING = 5.0F;
    public static final float ROW_SIZE = 9.0F;
    public static final float ROW_LEFT = 5.0F;
    public static final float ROW_TEXT_OFFSET = 16.0F;
    public static final float EFFECT_ROW_STEP = 9.0F;
    public static final float EFFECT_TEXT_GAP = 4.0F;
    public static final float EFFECT_RIGHT_PADDING = 6.0F;
    public static final float KEYBINDS_GLYPH_SIZE = 13.0F;
    public static final float KEYBINDS_TEXT_OFFSET = 22.0F;
    public static final int WAVEFORM_BAR_COUNT = 7;
    public static final float WAVEFORM_BAR_WIDTH = 2.0F;
    public static final float WAVEFORM_BAR_GAP = 1.0F;
    public static final float MEDIA_CHROME = 29.0F;
    public static final float IDLE_CHROME = 8.0F;
    public static final float MEDIA_GLYPH_INSET = 14.0F;
    public static final float TEXT_SCISSOR_X_BLEED = 0.75F;
    public static final float TEXT_SCISSOR_Y_BLEED = 2.0F;
    public static final float GLYPH_QUAD_INSET = 0.0F;
    public static final float ARMOR_ITEM_SIZE = 18.0F;
    public static final float ARMOR_ITEM_STEP = 29.5F;
    private static final long[] WAVEFORM_STEP_NANOS = {
        490_000_000L, 730_000_000L, 580_000_000L, 670_000_000L,
        540_000_000L, 810_000_000L, 610_000_000L
    };
    private static final long[] WAVEFORM_PHASE_NANOS = {
        0L, 211_000_000L, 83_000_000L, 357_000_000L,
        149_000_000L, 401_000_000L, 275_000_000L
    };

    private HudGlyphLayout() {
    }

    /** Width of a centered one-line icon + label group, including symmetric card padding. */
    public static float inlineWidth(float textWidth) {
        return Math.max(0.0F, finite(textWidth)) + INLINE_SIZE + INLINE_GAP + INLINE_PADDING * 2.0F;
    }

    /** Left edge of a centered icon + label group. */
    public static float inlineX(float x, float width, float textWidth) {
        if (!Float.isFinite(x) || !Float.isFinite(width) || !Float.isFinite(textWidth)) {
            return 0.0F;
        }
        float content = Math.max(0.0F, textWidth) + INLINE_SIZE + INLINE_GAP;
        return x + Math.max(0.0F, width - content) * 0.5F;
    }

    public static float centeredY(float y, float height, float size) {
        return finite(y) + (Math.max(0.0F, finite(height)) - Math.max(0.0F, finite(size))) * 0.5F;
    }

    /** Center the four armor slots as one group; the card has no separate glyph. */
    public static float armorItemX(float x, float width, int index) {
        float groupWidth = ARMOR_ITEM_SIZE + 3.0F * ARMOR_ITEM_STEP;
        float inset = Math.max(0.0F, finite(width) - groupWidth) * 0.5F;
        return finite(x) + inset + Math.clamp(index, 0, 3) * ARMOR_ITEM_STEP;
    }

    /** Compact effect rows with enough padding for the supplied top-left glyph. */
    public static float effectsHeight(int rowCount) {
        return Math.max(1, rowCount) * EFFECT_ROW_STEP + 5.0F;
    }

    public static float effectsGlyphY(float y, int rowCount) {
        return finite(y) + 2.0F;
    }

    public static float effectsRowY(float y, int rowCount, int index, float lineHeight) {
        int rows = Math.max(1, rowCount);
        int row = Math.max(0, Math.min(rows - 1, index));
        float textStackHeight = (rows - 1) * EFFECT_ROW_STEP + Math.max(0.0F, finite(lineHeight));
        return centeredY(y, effectsHeight(rows), textStackHeight) + row * EFFECT_ROW_STEP;
    }

    public static float effectsTextX(float x) {
        return finite(x) + ROW_TEXT_OFFSET;
    }

    /** Natural width for a name and timer on the same row, without a centered dead zone. */
    public static float effectsWidth(float nameWidth, float timerWidth) {
        return ROW_TEXT_OFFSET + Math.max(0.0F, finite(nameWidth)) + EFFECT_TEXT_GAP
            + Math.max(0.0F, finite(timerWidth)) + EFFECT_RIGHT_PADDING;
    }

    /** Seven bars with independent, repeatable random targets and eased motion. */
    public static float waveformHeight(int bar, long nowNanos) {
        if (bar < 0 || bar >= WAVEFORM_BAR_COUNT) return 0.0F;
        long stepNanos = WAVEFORM_STEP_NANOS[bar];
        long time = nowNanos + WAVEFORM_PHASE_NANOS[bar];
        long segment = Math.floorDiv(time, stepNanos);
        double progress = Math.floorMod(time, stepNanos) / (double) stepNanos;
        // Zero velocity at each target keeps a segment change imperceptible.
        double eased = progress * progress * (3.0D - 2.0D * progress);
        double from = waveformTarget(bar, segment);
        double to = waveformTarget(bar, segment + 1L);
        return (float) (3.0D + 9.5D * (from + (to - from) * eased));
    }

    private static double waveformTarget(int bar, long segment) {
        long value = segment * 0x9E3779B97F4A7C15L
            ^ (bar + 1L) * 0xD1B54A32D192ED03L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (value >>> 40) / (double) 0xFFFFFFL;
    }

    public static float waveformWidth() {
        return WAVEFORM_BAR_COUNT * WAVEFORM_BAR_WIDTH
            + (WAVEFORM_BAR_COUNT - 1) * WAVEFORM_BAR_GAP;
    }

    public static float mediaGlyphCenter(float x, float width) {
        return finite(x) + Math.max(0.0F, finite(width)) - MEDIA_GLYPH_INSET;
    }

    public static float keybindHeight(int rowCount) {
        return Math.max(17.0F, Math.max(0, rowCount) * 10.0F + 7.0F);
    }

    public static float keybindGlyphY(float y, int rowCount) {
        return finite(y) + 2.0F;
    }

    /** The supplied textures carry transparent padding; render the whole reserved glyph slot. */
    public static GlyphQuad glyphQuad(float x, float y, float slotSize) {
        float size = Math.max(0.0F, finite(slotSize));
        float inset = Math.min(GLYPH_QUAD_INSET, size * 0.25F);
        return new GlyphQuad(finite(x) + inset, finite(y) + inset,
            Math.max(0.0F, size - inset * 2.0F));
    }

    public static float mediaRightReserve(boolean available) {
        return available ? MEDIA_CHROME : IDLE_CHROME;
    }

    /** Explicit allow-list; the remaining HUDs stay text/artwork only. */
    public static boolean usesSuppliedGlyph(HudElementType type) {
        return type == HudElementType.FPS
            || type == HudElementType.EFFECTS || type == HudElementType.KEYBINDS;
    }

    private static float finite(float value) {
        return Float.isFinite(value) ? value : 0.0F;
    }

    public record GlyphQuad(float x, float y, float size) { }
}
