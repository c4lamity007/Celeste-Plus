package dev.c4lam.celeste.gui;

import java.util.Locale;

/** Locale-stable search ranking shared by names, namespace IDs and live suggestions. */
public final class BlockPickerSearch {
    private BlockPickerSearch() { }
    public static int rank(String query, String name, String id) {
        String q = normalize(query), n = normalize(name), i = normalize(id);
        if (q.isEmpty()) return 3;
        if (n.equals(q) || i.equals(q) || i.equals("minecraft:" + q)) return 0;
        if (n.startsWith(q) || i.startsWith(q) || i.startsWith("minecraft:" + q)) return 1;
        for (String term : q.split("\\s+")) if (!n.contains(term) && !i.contains(term)) return -1;
        return 2;
    }
    private static String normalize(String text) {
        return text == null ? "" : text.strip().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
    /** Exact local preset hit geometry; inter-button gaps are deliberately inert. */
    public static int storagePresetAt(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || y < 86 || y > 108) return -1;
        if (x >= 16 && x <= 98) return 0;
        return x >= 104 && x <= 174 ? 1 : -1;
    }
}
