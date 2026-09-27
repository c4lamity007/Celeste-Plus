package dev.c4lam.celeste.hud;

/** Latest-only, world-scoped toggle sound delivery: no startup or backlog sounds. */
public final class ModuleToggleSoundQueue {
    private static final ModuleToggleSoundQueue INSTANCE = new ModuleToggleSoundQueue();
    public static ModuleToggleSoundQueue getInstance() { return INSTANCE; }
    private Object world;
    private Boolean pending;
    private long lastSound;

    public synchronized void publish(boolean enabled) {
        if (world != null) pending = enabled;
    }

    public synchronized Boolean poll(Object currentWorld, long nowNanos) {
        if (currentWorld == null || currentWorld != world) {
            world = currentWorld;
            pending = null;
            lastSound = 0;
            return null;
        }
        Boolean result = pending;
        pending = null;
        if (result == null || lastSound != 0 && nowNanos - lastSound < 60_000_000L) return null;
        lastSound = nowNanos;
        return result;
    }

    public synchronized void reset() { world = null; pending = null; lastSound = 0; }
}
