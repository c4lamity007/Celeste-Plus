package dev.c4lam.celeste.gui;

/** One geometry source for rendering, pointer routing and the small-window checks. */
public final class AmbientEffectsEditorLayout {
    public static final float WIDTH = 424, HEIGHT = 302;
    public static final Rect ENABLED = new Rect(334, 16, 74, 22);
    public static final Rect ADD = new Rect(131, 50, 73, 22);
    public static final Rect DONE = new Rect(340, 265, 68, 23);
    public static final Rect LIST = new Rect(16, 81, 188, 174);
    public static final Rect DETAILS = new Rect(218, 81, 190, 174);
    public static final Rect DENSITY = new Rect(232, 126, 162, 36);
    public static final Rect SIZE = new Rect(232, 185, 162, 36);
    public static final Rect COLOR = new Rect(232, 227, 162, 20);
    public static final Rect COLOR_SWATCH = new Rect(378, 229, 16, 16);

    private AmbientEffectsEditorLayout() { }
    public record Rect(float x, float y, float width, float height) {
        public boolean contains(double px, double py) {
            return Double.isFinite(px) && Double.isFinite(py)
                && px >= x && py >= y && px < x + width && py < y + height;
        }
        public float right() { return x + width; }
        public float bottom() { return y + height; }
    }
    public record View(float x, float y, float scale) {
        public double localX(double screenX) { return (screenX - x) / scale; }
        public double localY(double screenY) { return (screenY - y) / scale; }
    }
    public static View fit(int width, int height, float preferred) {
        float scale = Math.max(.001f, Math.min(Float.isFinite(preferred) ? preferred : 1,
            Math.min(Math.max(1, width - 16) / WIDTH, Math.max(1, height - 16) / HEIGHT)));
        return new View((width - WIDTH * scale) / 2, (height - HEIGHT * scale) / 2, scale);
    }
    public static Rect row(int index) {
        if (index < 0 || index >= 6) throw new IllegalArgumentException("Ambient row out of bounds");
        return new Rect(16, 81 + index * 29, 188, 26);
    }
    public static Rect remove(int index) {
        Rect row = row(index);
        return new Rect(179, row.y() + 2, 23, 22);
    }
    public static double progress(Rect slider, double x) {
        return Double.isFinite(x) ? Math.clamp((x - slider.x()) / slider.width(), 0, 1) : 0;
    }
}
