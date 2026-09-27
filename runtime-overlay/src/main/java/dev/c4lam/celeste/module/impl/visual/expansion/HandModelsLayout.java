package dev.c4lam.celeste.module.impl.visual.expansion;

/** Screen-fraction positioning: independent of resolution and Minecraft GUI scale. */
public final class HandModelsLayout {
    public static final double MIN = .08;
    public static final double MAX = .92;
    public static final double SNAP = .02;
    public static final double MIN_SIZE = .50, MAX_SIZE = 1.50, SIZE_STEP = .05;
    private HandModelsLayout() { }

    public record Position(double x, double y) { }

    public static Position defaults(boolean right) {
        return new Position(right ? .72 : .28, .78);
    }

    public static Position bounded(boolean right, double x, double y) {
        Position fallback = defaults(right);
        return new Position(clamp(x, fallback.x), clamp(y, fallback.y));
    }

    public static Position drag(boolean right, Position start, double startMouseX, double startMouseY,
                                double mouseX, double mouseY, double width, double height) {
        return drag(right, start, start, startMouseX, startMouseY, mouseX, mouseY, width, height);
    }

    public static Position drag(boolean right, Position start, Position previous, double startMouseX, double startMouseY,
                                double mouseX, double mouseY, double width, double height) {
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0
            || !Double.isFinite(mouseX) || !Double.isFinite(mouseY)) return previous;
        Position raw = bounded(right, start.x + (mouseX - startMouseX) / width,
            start.y + (mouseY - startMouseY) / height);
        return bounded(right, snap(raw.x, previous.x), snap(raw.y, previous.y));
    }

    private static double snap(double raw, double previous) {
        // A small dead band stops a hand repeatedly flipping at a grid boundary.
        return Math.abs(raw - previous) <= SNAP * .65 ? previous : Math.rint(raw / SNAP) * SNAP;
    }

    public static double size(double value) {
        value = Double.isFinite(value) ? Math.clamp(value, MIN_SIZE, MAX_SIZE) : 1.0;
        return Math.rint(value / SIZE_STEP) * SIZE_STEP;
    }

    public static double scrollSize(double current, double wheel) {
        if (!Double.isFinite(wheel) || wheel == 0) return size(current);
        int steps = (int) Math.clamp(Math.ceil(Math.abs(wheel)), 1, 5);
        return size(current + Math.copySign(steps * SIZE_STEP, wheel));
    }

    public static Boolean wheelTarget(Boolean dragging, Boolean hovered, Boolean selected) {
        return dragging != null ? dragging : hovered != null ? hovered : selected;
    }

    /** Generous item region: resource packs can give the same item very different meshes. */
    public static boolean contains(Position point, double x, double y, double width, double height) {
        return contains(point, 1.0, x, y, width, height);
    }

    public static boolean contains(Position point, double itemSize, double x, double y, double width, double height) {
        double reach = Math.sqrt(size(itemSize));
        return width > 0 && height > 0 && Math.abs(x / width - point.x) <= .15 * reach
            && Math.abs(y / height - point.y) <= .27 * reach;
    }

    public static double distanceSquared(Position point, double x, double y, double width, double height) {
        if (width <= 0 || height <= 0) return Double.POSITIVE_INFINITY;
        double dx = x / width - point.x;
        double dy = y / height - point.y;
        return dx * dx + dy * dy;
    }

    private static double clamp(double value, double fallback) {
        return Math.max(MIN, Math.min(MAX, Double.isFinite(value) ? value : fallback));
    }
}
