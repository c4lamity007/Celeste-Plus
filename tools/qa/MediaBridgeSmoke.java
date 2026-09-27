import dev.redstones.mediaplayerinfo.impl.win.WindowsMediaPlayerInfo;

/** Isolated JNI lifecycle check. Reads counts only; never controls playback. */
public final class MediaBridgeSmoke {
    private static void stage(String value) {
        System.out.println("CELESTE_MEDIA_SMOKE stage=" + value);
    }

    private static void failure(Throwable error) {
        // Class names only: exception messages may contain private media data.
        System.out.println("CELESTE_MEDIA_SMOKE error=" + error.getClass().getName());
    }

    private static void cleanup() {
        stage("before_cleanup");
        WindowsMediaPlayerInfo.cleanupNativeResources();
        stage("cleanup_returned");
    }

    private static void enumerate(WindowsMediaPlayerInfo bridge, boolean reload) {
        String prefix = reload ? "reload_" : "";
        var sessions = bridge.getMediaSessions();
        if (sessions == null) throw new IllegalStateException("Native session collection was null");
        System.out.println("CELESTE_MEDIA_SMOKE " + prefix + "sessions=" + sessions.size());
        int cycles = 0;
        for (var session : sessions) {
            session.getCycleType();
            cycles++;
        }
        System.out.println("CELESTE_MEDIA_SMOKE " + prefix + "cycle_reads=" + cycles);
        stage(prefix + "enumeration_complete");
    }

    public static void main(String[] args) {
        if (args.length != 1 || !(args[0].equals("direct") || args[0].equals("shutdown-hook") || args[0].equals("reload"))) {
            throw new IllegalArgumentException("Expected an explicit cleanup mode");
        }
        try {
            stage("before_load");
            var bridge = new WindowsMediaPlayerInfo();
            var availability = WindowsMediaPlayerInfo.class.getDeclaredField("cleanupAvailable");
            availability.setAccessible(true);
            System.out.println("CELESTE_MEDIA_SMOKE cleanup_available=" + availability.getBoolean(null));
            stage("loaded");
            enumerate(bridge, false);
            if (args[0].equals("shutdown-hook")) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try { cleanup(); }
                    catch (Throwable error) { failure(error); }
                }, "celeste-isolated-media-cleanup"));
            } else {
                cleanup();
                if (args[0].equals("reload")) {
                    stage("before_reload");
                    var reloaded = new WindowsMediaPlayerInfo();
                    stage("reloaded");
                    enumerate(reloaded, true);
                    cleanup();
                }
            }
            stage("before_exit");
            System.exit(0);
        } catch (Throwable error) {
            failure(error);
            System.exit(2);
        }
    }
}
