package dev.c4lam.celeste.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/** Geometry shared by the content scissor, inspection panel and measured text. */
public final class GuiContentLayout {
    private GuiContentLayout() { }

    public record Box(float x, float y, float width, float height) {
        public boolean contains(double px, double py) {
            return width > 0 && height > 0 && px >= x && px <= x + width && py >= y && py <= y + height;
        }

        public Box intersect(Box clip) {
            float left = Math.max(x, clip.x);
            float top = Math.max(y, clip.y);
            return new Box(left, top, Math.max(0, Math.min(x + width, clip.x + clip.width) - left),
                Math.max(0, Math.min(y + height, clip.y + clip.height) - top));
        }
    }

    public record Inspection(Box panel, Box list, Box close, float minimumScroll) { }

    public static Inspection inspection(Box viewport, int rows) {
        float width = Math.min(220, viewport.width * 0.46f);
        float height = Math.min(40 + Math.max(1, rows) * 13, Math.max(0, viewport.height - 16));
        Box panel = new Box(viewport.x + viewport.width - width, viewport.y + 8, width, height);
        Box list = new Box(panel.x + 10, panel.y + 26, Math.max(0, width - 20), Math.max(0, height - 36));
        return new Inspection(panel, list, new Box(panel.x + width - 23, panel.y + 5, 18, 18),
            minimumScroll(Math.max(1, rows) * 13, list.height));
    }

    public static float minimumScroll(float contentHeight, float viewportHeight) {
        return Math.min(0, viewportHeight - contentHeight);
    }

    public static float scroll(float value, float minimum) {
        if (!Float.isFinite(value)) return 0;
        return Math.max(Math.min(0, minimum), Math.min(0, value));
    }

    public static float labelWidth(float rowWidth, float controlWidth) {
        return Math.max(0, rowWidth - 27 - controlWidth);
    }

    /** Retains the existing UTF-16 animation indices without ever exposing half a code point. */
    public static int prefixBoundary(String text, int requested) {
        int end = Math.max(0, Math.min(text.length(), requested));
        return end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
            && Character.isLowSurrogate(text.charAt(end)) ? end - 1 : end;
    }

    public static String withoutLastCodePoint(String text) {
        return text.isEmpty() ? text : text.substring(0, text.offsetByCodePoints(text.length(), -1));
    }

    public static List<String> wrap(String text, double width, int maximumLines, ToDoubleFunction<String> measure) {
        List<String> lines = new ArrayList<>();
        String remaining = text == null ? "" : text.strip();
        while (!remaining.isEmpty() && lines.size() < maximumLines) {
            if (measure.applyAsDouble(remaining) <= width || lines.size() == maximumLines - 1) {
                lines.add(fit(remaining, width, measure));
                break;
            }
            int count = remaining.codePointCount(0, remaining.length());
            int low = 0;
            int high = count;
            while (low < high) {
                int candidate = (low + high + 1) / 2;
                if (measure.applyAsDouble(remaining.substring(0, remaining.offsetByCodePoints(0, candidate))) <= width)
                    low = candidate;
                else high = candidate - 1;
            }
            if (low == 0) break;
            int end = remaining.offsetByCodePoints(0, low);
            int space = remaining.lastIndexOf(' ', end);
            if (space > 0) end = space;
            lines.add(remaining.substring(0, end).stripTrailing());
            remaining = remaining.substring(end).stripLeading();
        }
        return List.copyOf(lines);
    }

    public static String fit(String text, double width, ToDoubleFunction<String> measure) {
        return fit(text, width, measure, false);
    }

    public static String fitTail(String text, double width, ToDoubleFunction<String> measure) {
        return fit(text, width, measure, true);
    }

    private static String fit(String text, double width, ToDoubleFunction<String> measure, boolean tail) {
        if (text == null || text.isEmpty() || !(width > 0)) return "";
        if (measure.applyAsDouble(text) <= width) return text;
        String ellipsis = "…";
        if (measure.applyAsDouble(ellipsis) > width) return "";
        int count = text.codePointCount(0, text.length());
        int low = 0;
        int high = count;
        while (low < high) {
            int length = (low + high + 1) / 2;
            String candidate = tail ? ellipsis + text.substring(text.offsetByCodePoints(0, count - length))
                : text.substring(0, text.offsetByCodePoints(0, length)) + ellipsis;
            if (measure.applyAsDouble(candidate) <= width) low = length;
            else high = length - 1;
        }
        return tail ? ellipsis + text.substring(text.offsetByCodePoints(0, count - low))
            : text.substring(0, text.offsetByCodePoints(0, low)) + ellipsis;
    }
}
