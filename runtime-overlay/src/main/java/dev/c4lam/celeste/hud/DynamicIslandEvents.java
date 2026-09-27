package dev.c4lam.celeste.hud;

/**
 * Tiny latest-value bridge between modules and the Dynamic Island.
 *
 * <p>The bridge deliberately has no queue: a newer toggle replaces an older
 * one, which keeps a burst of config/module changes from replaying stale
 * notifications. The renderer only opens the gate while the island is active
 * in a real world, so construction and startup config restoration stay quiet.</p>
 */
public final class DynamicIslandEvents {
    private static final int MAX_NAME_CODE_POINTS = 48;

    private static boolean accepting;
    private static long sequence;
    private static Toggle latest;

    private DynamicIslandEvents() {
    }

    public static synchronized void setAccepting(boolean acceptingEvents) {
        accepting = acceptingEvents;
        if (!acceptingEvents) {
            latest = null;
        }
    }

    public static synchronized void publishModuleToggle(String moduleName, boolean enabled) {
        if (!accepting) {
            return;
        }
        String cleanName = cleanName(moduleName);
        if (cleanName.isEmpty()) {
            return;
        }
        latest = new Toggle(++sequence, cleanName, enabled);
    }

    /** Returns the newest event after {@code seenSequence}, never a backlog. */
    public static synchronized Toggle latestAfter(long seenSequence) {
        return latest != null && latest.sequence() > seenSequence ? latest : null;
    }

    /** Disables delivery and discards an event that has not been rendered yet. */
    public static synchronized void reset() {
        accepting = false;
        latest = null;
    }

    static synchronized boolean acceptingForTest() {
        return accepting;
    }

    private static String cleanName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder clean = new StringBuilder(Math.min(value.length(), MAX_NAME_CODE_POINTS));
        int count = 0;
        for (int offset = 0; offset < value.length() && count < MAX_NAME_CODE_POINTS; ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '\u00a7' || Character.isISOControl(codePoint)) {
                continue;
            }
            clean.appendCodePoint(codePoint);
            count++;
        }
        return clean.toString().strip();
    }

    public record Toggle(long sequence, String moduleName, boolean enabled) {
        public Toggle {
            if (sequence <= 0L) {
                throw new IllegalArgumentException("sequence must be positive");
            }
            moduleName = cleanName(moduleName);
            if (moduleName.isEmpty()) {
                throw new IllegalArgumentException("moduleName must not be blank");
            }
        }
    }
}
