package dev.c4lam.celeste.hud;

/** Content-sized config panel with one aligned column for all configured keybinds. */
public final class KeybindsHudLayout {
    public static final String TITLE = "Configs";
    public static final float TITLE_FONT = 7.0F;
    public static final float LABEL_FONT = 6.7F;
    public static final float KEY_FONT = 6.0F;
    public static final float GLYPH_SIZE = 9.0F;
    public static final float GLYPH_LEFT = 5.0F;
    public static final float TITLE_LEFT = 18.0F;
    public static final float LABEL_LEFT = 5.0F;
    public static final float RIGHT_PADDING = 5.0F;
    public static final float LABEL_KEY_GAP = 6.0F;
    public static final float HEADER_HEIGHT = 16.0F;
    public static final float HEADER_GAP = 1.0F;
    public static final float ROW_HEIGHT = 13.0F;
    public static final float ROW_GAP = 0.0F;
    public static final float ROW_STEP = ROW_HEIGHT + ROW_GAP;
    public static final float BOTTOM_PADDING = 2.0F;
    public static final float KEYCAP_HEIGHT = 9.0F;
    public static final float KEYCAP_PADDING = 3.0F;
    private static final float MIN_KEYCAP_WIDTH = 11.0F;
    private static final float MIN_ROW_WIDTH = LABEL_LEFT + LABEL_KEY_GAP + RIGHT_PADDING;
    private static final float MAX_ROW_WIDTH = 128.0F;

    private KeybindsHudLayout() {
    }

    /** Shortens a long module name without hiding the action associated with its bind. */
    public static String compactLabel(String value) {
        if (value == null) return "";
        String label = value.strip();
        int actionAt = label.indexOf(" · ");
        String module = actionAt < 0 ? label : label.substring(0, actionAt);
        int firstSpace = module.indexOf(' ');
        if (firstSpace > 7 && module.indexOf(' ', firstSpace + 1) > firstSpace) {
            module = module.charAt(0) + ". " + module.substring(firstSpace + 1);
        }
        return actionAt < 0 ? module : module + label.substring(actionAt);
    }

    public static Layout measure(float[] labelWidths, float[] keyWidths, float viewportWidth, float titleWidth) {
        if (labelWidths.length != keyWidths.length) {
            throw new IllegalArgumentException("Each bind needs a label and key width");
        }
        float screen = Float.isFinite(viewportWidth) ? Math.max(1.0F, viewportWidth) : 1.0F;
        float minimum = Math.min(MAX_ROW_WIDTH, TITLE_LEFT + safe(titleWidth) + RIGHT_PADDING);
        float maximum = Math.max(minimum, Math.min(MAX_ROW_WIDTH, screen - 12.0F));
        float width = minimum;
        for (int index = 0; index < labelWidths.length; index++) {
            width = Math.max(width, Math.min(maximum, MIN_ROW_WIDTH
                + safe(labelWidths[index]) + keycapWidth(keyWidths[index])));
        }
        float height = HEADER_HEIGHT;
        if (labelWidths.length > 0) {
            height += HEADER_GAP + labelWidths.length * ROW_HEIGHT
                + (labelWidths.length - 1) * ROW_GAP + BOTTOM_PADDING;
        }
        return new Layout(width, height, labelWidths.length);
    }

    public static float keycapWidth(float keyWidth) {
        return Math.max(MIN_KEYCAP_WIDTH, safe(keyWidth) + KEYCAP_PADDING * 2);
    }

    private static float safe(float width) {
        return Float.isFinite(width) ? Math.max(0.0F, width) : 0.0F;
    }

    public static final class Layout {
        private final float width;
        private final float height;
        private final int rows;

        private Layout(float width, float height, int rows) {
            this.width = width;
            this.height = height;
            this.rows = rows;
        }

        public int rows() {
            return rows;
        }

        public float width() {
            return width;
        }

        public float height() {
            return height;
        }

        public float headerWidth() {
            return width;
        }

        public float rowWidth(int index) {
            if (index < 0 || index >= rows) throw new IndexOutOfBoundsException(index);
            return width;
        }

        public float labelX(float x) {
            return x + LABEL_LEFT;
        }

        public float keyX(float x, int index, float keyWidth) {
            return keycapX(x, index, keyWidth) + (keycapWidth(keyWidth) - safe(keyWidth)) * 0.5F;
        }

        public float keycapX(float x, int index, float keyWidth) {
            return x + rowWidth(index) - RIGHT_PADDING - keycapWidth(keyWidth);
        }

        public float keycapY(float y, int index) {
            return rowY(y, index) + (ROW_HEIGHT - KEYCAP_HEIGHT) * 0.5F;
        }

        public float availableLabelWidth(int index, float keyWidth) {
            return Math.max(0.0F, rowWidth(index) - MIN_ROW_WIDTH - keycapWidth(keyWidth));
        }

        public float rowY(float y, int index) {
            return y + HEADER_HEIGHT + HEADER_GAP + index * ROW_STEP;
        }

        public float rowTextY(float y, int index, float fontHeight) {
            return rowY(y, index) + (ROW_HEIGHT - safe(fontHeight)) * 0.5F;
        }
    }
}
