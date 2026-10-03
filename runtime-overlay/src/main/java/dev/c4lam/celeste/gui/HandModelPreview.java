package dev.c4lam.celeste.gui;

/** Resident editor values only; contains no held-item transform or feature instance. */
public final class HandModelPreview {
    private static double rightX = Double.NaN, rightY = Double.NaN;
    private static double leftX = Double.NaN, leftY = Double.NaN;
    private static double rightSize = Double.NaN, leftSize = Double.NaN;
    private HandModelPreview() { }

    public static void setPreviewPosition(boolean right, double x, double y) {
        var position = HandModelsLayout.bounded(right, x, y);
        if (right) { rightX = position.x(); rightY = position.y(); }
        else { leftX = position.x(); leftY = position.y(); }
    }
    public static void setPreviewSize(boolean right, double size) {
        if (right) rightSize = HandModelsLayout.size(size);
        else leftSize = HandModelsLayout.size(size);
    }
    public static double x(boolean right) { return right ? rightX : leftX; }
    public static double y(boolean right) { return right ? rightY : leftY; }
    public static double size(boolean right) { return right ? rightSize : leftSize; }
    public static void clearPreview() {
        rightX = rightY = leftX = leftY = rightSize = leftSize = Double.NaN;
    }
}
