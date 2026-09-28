package dev.c4lam.celeste.hud;

import java.util.ArrayDeque;

/**
 * Bounded bridge between modules, expired effects and the Dynamic Island.
 *
 * <p>Module changes coalesce to their latest state. Effect expirations wait
 * their turn, so two effects ending together each get their full two seconds.
 * The renderer only opens the gate while the island is active
 * in a real world, so construction and startup config restoration stay quiet.</p>
 */
public final class DynamicIslandEvents {
    private static final int MAX_NAME_CODE_POINTS = 48;
    private static final int MAX_PENDING = 8;

    private static boolean accepting;
    private static long sequence;
    private static final ArrayDeque<Toggle> pending = new ArrayDeque<>();

    private DynamicIslandEvents() {
    }

    public static synchronized void setAccepting(boolean acceptingEvents) {
        accepting = acceptingEvents;
        if (!acceptingEvents) {
            pending.clear();
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
        pending.removeIf(event -> !event.effect());
        enqueue(new Toggle(++sequence, cleanName, enabled));
    }

    public static synchronized void publishEffectExpired(String effectName, String effectTexture) {
        String clean = cleanName(effectName);
        if (!accepting || clean.isEmpty() || effectTexture == null || effectTexture.isBlank()) return;
        pending.removeIf(event -> effectTexture.equals(event.effectTexture()));
        enqueue(new Toggle(++sequence, clean, false, effectTexture));
    }

    private static void enqueue(Toggle event) {
        while (pending.size() >= MAX_PENDING) pending.removeFirst();
        pending.addLast(event);
    }

    /** Poll only when the current notice has finished its complete lifetime. */
    public static synchronized Toggle latestAfter(long seenSequence) {
        while (!pending.isEmpty() && pending.peekFirst().sequence() <= seenSequence) pending.removeFirst();
        return pending.pollFirst();
    }

    /** Disables delivery and discards an event that has not been rendered yet. */
    public static synchronized void reset() {
        accepting = false;
        pending.clear();
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

    public record Toggle(long sequence, String moduleName, boolean enabled, String effectTexture) {
        public Toggle(long sequence, String moduleName, boolean enabled) {
            this(sequence, moduleName, enabled, "");
        }

        public Toggle {
            if (sequence <= 0L) {
                throw new IllegalArgumentException("sequence must be positive");
            }
            moduleName = cleanName(moduleName);
            if (moduleName.isEmpty()) {
                throw new IllegalArgumentException("moduleName must not be blank");
            }
            effectTexture = effectTexture == null ? "" : effectTexture;
        }

        public boolean effect() { return !effectTexture.isEmpty(); }
    }
}
