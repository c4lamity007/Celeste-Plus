package dev.c4lam.celeste.compat;

/** Pure filter check; the gameplay fixture separately proves the real packet hook. */
public final class TotemPopTrackerCheck {
    private TotemPopTrackerCheck() { }

    public static int run() {
        Object local = new Object();
        Object other = new Object();
        require(TotemPopTracker.isLocalTotemPop((byte) 35, local, local), "local pop counted");
        require(!TotemPopTracker.isLocalTotemPop((byte) 35, other, local), "other player excluded");
        require(!TotemPopTracker.isLocalTotemPop((byte) 34, local, local), "other status excluded");
        require(!TotemPopTracker.isLocalTotemPop((byte) 35, null, local), "missing entity excluded");
        require(!TotemPopTracker.isLocalTotemPop((byte) 35, null, null), "missing local player excluded");
        return 5;
    }

    private static void require(boolean valid, String label) {
        if (!valid) throw new AssertionError(label);
    }
}
