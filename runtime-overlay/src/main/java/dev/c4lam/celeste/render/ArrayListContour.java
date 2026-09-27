package dev.c4lam.celeste.render;

import java.util.ArrayList;
import java.util.List;

/** Pure bounded geometry for one descending stepped silhouette. */
public final class ArrayListContour {
    public static final float RADIUS = 2.0F;
    public static final float PADDING = 2.0F;
    private ArrayListContour() { }

    public record Row(int index, float previous, float width, float next, float height,
                      float topRadius, float bottomRadius) {
        public float top() { return index * height; }
        public float bottom() { return (index + 1) * height; }
        public float slabTop() { return previous < 0 ? top() - PADDING : top(); }
        public float slabBottom() { return next < 0 ? bottom() + PADDING : bottom(); }
        public float slabWidth() { return Math.max(width, previous) + PADDING; }
    }

    /** Invalid/non-descending input draws nothing; the caller retains its original text layout. */
    public static List<Row> rows(float[] widths, float height) {
        if (widths == null || widths.length == 0 || widths.length > 512
                || !Float.isFinite(height) || height <= 0
                || !Float.isFinite(height * widths.length + PADDING)) return List.of();
        for (int i = 0; i < widths.length; i++) {
            if (!Float.isFinite(widths[i]) || widths[i] <= 0
                    || !Float.isFinite(widths[i] + PADDING)
                    || i > 0 && widths[i] > widths[i - 1]) return List.of();
        }
        List<Row> rows = new ArrayList<>(widths.length);
        for (int i = 0; i < widths.length; i++) {
            float previous = i == 0 ? -1 : widths[i - 1];
            float next = i + 1 == widths.length ? -1 : widths[i + 1];
            rows.add(new Row(i, previous, widths[i], next, height,
                previous < 0 ? endRadius(widths[i], height) : joinRadius(previous, widths[i], height),
                next < 0 ? endRadius(widths[i], height) : joinRadius(widths[i], next, height)));
        }
        return List.copyOf(rows);
    }

    private static float endRadius(float width, float height) {
        return Math.min(RADIUS, Math.min(height * .25F, width * .5F));
    }

    private static float joinRadius(float wider, float narrower, float height) {
        return Math.min(endRadius(narrower, height), (wider - narrower) * .5F);
    }
}
