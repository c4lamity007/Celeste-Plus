package dev.c4lam.celeste.media;

import dev.redstones.mediaplayerinfo.IMediaSession;
import dev.redstones.mediaplayerinfo.MediaInfo;
import dev.redstones.mediaplayerinfo.impl.win.WindowsMediaPlayerInfo;

import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Native Windows System Media Transport Controls bridge for the media HUD.
 * MediaPlayerInfo.dll replaces the old PowerShell/WinRT helper process.
 */
public final class SystemMediaService implements AutoCloseable {
    private static final SystemMediaService INSTANCE = new SystemMediaService();
    private static final int MAX_ARTWORK_BYTES = 5_000_000;
    /** The bundled JNI bridge reports timeline values in whole seconds. */
    private static final long MILLISECONDS_PER_NATIVE_SECOND = 1_000L;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Celeste Native Media");
        thread.setDaemon(true);
        thread.setContextClassLoader(ClassLoader.getPlatformClassLoader());
        return thread;
    });
    private final AtomicReference<MediaPlaybackSnapshot> snapshot = new AtomicReference<>(
        MediaPlaybackSnapshot.empty("Waiting for a system media session")
    );
    private final AtomicReference<IMediaSession> activeSession = new AtomicReference<>();
    private final Object lifecycleLock = new Object();
    private final Thread shutdownHook;
    private final NativeMediaLyrics nativeLyrics = new NativeMediaLyrics();

    private volatile boolean initialized;
    private volatile boolean closed;
    private volatile WindowsMediaPlayerInfo bridge;
    private WindowsMediaPlayerInfo closingBridge;
    private CompletableFuture<Void> stopCompletion;

    private SystemMediaService() {
        shutdownHook = new Thread(this::close, "Celeste Native Media Shutdown");
        shutdownHook.setContextClassLoader(ClassLoader.getPlatformClassLoader());
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    public static SystemMediaService getInstance() {
        return INSTANCE;
    }

    public void initialize() {
        synchronized (lifecycleLock) {
            if (initialized || closed) return;
            initialized = true;
            if (!isWindows()) {
                snapshot.set(MediaPlaybackSnapshot.empty("Automatic media control currently requires Windows"));
                return;
            }
            try {
                bridge = new WindowsMediaPlayerInfo();
                executor.scheduleWithFixedDelay(this::pollSafely, 0L, 350L, TimeUnit.MILLISECONDS);
            } catch (RuntimeException | LinkageError exception) {
                snapshot.set(MediaPlaybackSnapshot.empty("Could not load native Windows media support"));
            }
        }
    }

    public MediaPlaybackSnapshot snapshot() {
        initialize();
        return snapshot.get();
    }

    public CompletableFuture<MediaPlaybackSnapshot> refresh() {
        return command(Command.STATE);
    }

    public CompletableFuture<MediaPlaybackSnapshot> togglePlayback() {
        return command(Command.TOGGLE);
    }

    public CompletableFuture<MediaPlaybackSnapshot> next() {
        return command(Command.NEXT);
    }

    public CompletableFuture<MediaPlaybackSnapshot> previous() {
        return command(Command.PREVIOUS);
    }

    public CompletableFuture<MediaPlaybackSnapshot> toggleShuffle() {
        return command(Command.CYCLE);
    }

    private CompletableFuture<MediaPlaybackSnapshot> command(Command command) {
        initialize();
        if (closed || bridge == null) return CompletableFuture.completedFuture(snapshot.get());
        try { return CompletableFuture.supplyAsync(() -> exchange(command), executor); }
        catch (RejectedExecutionException stopped) { return CompletableFuture.completedFuture(snapshot.get()); }
    }

    private void pollSafely() {
        try {
            exchange(Command.STATE);
        } catch (RuntimeException | LinkageError ignored) {
            // A media app can close while Windows enumerates sessions. The next
            // native poll supplies a fresh session object. Linkage failures are
            // also contained so one bad poll cannot cancel the scheduled worker.
        }
    }

    private MediaPlaybackSnapshot exchange(Command command) {
        WindowsMediaPlayerInfo currentBridge = bridge;
        if (closed || currentBridge == null) return snapshot.get();
        try {
            IMediaSession session = selectSession(currentBridge.getMediaSessions());
            if (session == null) {
                activeSession.set(null);
                MediaPlaybackSnapshot empty = MediaPlaybackSnapshot.empty("Nothing is playing");
                nativeLyrics.reset();
                return publish(empty);
            }
            activeSession.set(session);
            perform(command, session);

            // Native transport calls are synchronous. Re-enumerate once after
            // a button press so playing/progress/cycle state reflects Windows.
            if (command != Command.STATE) {
                IMediaSession refreshed = selectSession(currentBridge.getMediaSessions());
                if (refreshed != null) {
                    session = refreshed;
                    activeSession.set(session);
                }
            }
            MediaPlaybackSnapshot current = snapshotFor(session);
            return publish(current);
        } catch (RuntimeException | LinkageError exception) {
            activeSession.set(null);
            MediaPlaybackSnapshot failed = MediaPlaybackSnapshot.empty("Could not read Windows media");
            nativeLyrics.reset();
            return publish(failed);
        }
    }

    private MediaPlaybackSnapshot publish(MediaPlaybackSnapshot current) {
        synchronized (lifecycleLock) {
            if (closed) {
                nativeLyrics.reset();
                activeSession.set(null);
                return snapshot.get();
            }
            snapshot.set(current);
            return current;
        }
    }

    private static IMediaSession selectSession(List<IMediaSession> sessions) {
        if (sessions == null) return null;
        return sessions.stream()
            .filter(session -> session != null && session.getMedia() != null)
            .max(Comparator.comparingInt(SystemMediaService::sessionScore))
            .orElse(null);
    }

    private static int sessionScore(IMediaSession session) {
        MediaInfo media = session.getMedia();
        int score = media.isPlaying() ? 100 : 0;
        if (!media.getTitle().isBlank()) score += 10;
        if (!media.getArtist().isBlank()) score += 5;
        if (session.getOwner().toLowerCase(Locale.ROOT).contains("spotify")) score += 1;
        return score;
    }

    private static void perform(Command command, IMediaSession session) {
        switch (command) {
            case STATE -> { }
            case TOGGLE -> session.playPause();
            case NEXT -> session.next();
            case PREVIOUS -> session.previous();
            case CYCLE -> session.swapCycle();
        }
    }

    private MediaPlaybackSnapshot snapshotFor(IMediaSession session) {
        MediaInfo media = session.getMedia();
        String owner = fallback(session.getOwner(), "System media");
        String title = fallback(media.getTitle(), "Unknown track");
        String artist = media.getArtist();
        byte[] artwork = media.getArtworkPng();
        if (artwork.length > MAX_ARTWORK_BYTES) artwork = new byte[0];

        String artworkKey = owner + '\u0000' + title + '\u0000' + artist + '\u0000' + Arrays.hashCode(artwork);
        MediaPlaybackSnapshot previous = snapshot.get();
        String artworkBase64 = artworkKey.equals(previous.artworkKey())
            ? previous.artworkBase64()
            : artwork.length == 0 ? "" : Base64.getEncoder().encodeToString(artwork);
        boolean cycle = session.getCycleType() != 0;
        long progressMs = nativeSecondsToMillis(media.getPosition());
        long durationMs = nativeSecondsToMillis(media.getDuration());
        return new MediaPlaybackSnapshot(
            true,
            media.isPlaying(),
            cycle,
            true,
            true,
            true,
            true,
            displaySource(owner),
            owner,
            title,
            artist,
            "",
            artworkKey,
            artworkBase64,
            progressMs,
            durationMs,
            "",
            Instant.now(),
            nativeLyrics.update(owner, media)
        );
    }

    private static long nativeSecondsToMillis(long seconds) {
        if (seconds <= 0L) return 0L;
        return seconds > Long.MAX_VALUE / MILLISECONDS_PER_NATIVE_SECOND
            ? Long.MAX_VALUE
            : seconds * MILLISECONDS_PER_NATIVE_SECOND;
    }

    @Override
    public void close() {

        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
            snapshot.set(MediaPlaybackSnapshot.empty("Media support closed"));
            nativeLyrics.reset();
            activeSession.set(null);
            closingBridge = bridge;
            bridge = null;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException shuttingDown) {
            // The JVM already owns and is running its shutdown hooks.
        }
        executor.shutdownNow();
        boolean executorStopped = false;
        try {
            // Do not delay Minecraft shutdown if Windows is in the middle of
            // one last media-session enumeration.
            executorStopped = executor.awaitTermination(250L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        // The JNI bridge owns a global Windows media-session object. Release
        // it after the polling worker has exited; unlike the old synchronized
        // close path, this never waits indefinitely for a native poll.
        if (executorStopped) releaseNativeResources();
        MediaArtwork.getInstance().close();
    }

    /** Completes only after the native worker, artwork decoder and queued render work have drained. */
    public CompletionStage<Void> stopped() {
        synchronized (lifecycleLock) {
            if (!closed) return CompletableFuture.failedFuture(new IllegalStateException("Media support is not closed"));
            if (stopCompletion == null) {
                CompletableFuture<Void> workerStopped = CompletableFuture.runAsync(() -> {
                    try {
                        if (!executor.awaitTermination(30L, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Media worker did not finish stopping within 30 seconds");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Media shutdown observation was interrupted", interrupted);
                    }
                    releaseNativeResources();
                });
                stopCompletion = CompletableFuture.allOf(workerStopped,
                    MediaArtwork.getInstance().stopped().toCompletableFuture());
            }
            return stopCompletion.minimalCompletionStage();
        }
    }

    private void releaseNativeResources() {
        WindowsMediaPlayerInfo retired;
        synchronized (lifecycleLock) {
            if (!executor.isTerminated()) return;
            retired = closingBridge;
            closingBridge = null;
        }
        if (retired != null) WindowsMediaPlayerInfo.cleanupNativeResources();
    }
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    private static String displaySource(String owner) {
        return owner.toLowerCase(Locale.ROOT).contains("spotify") ? "Spotify" : owner;
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private enum Command { STATE, TOGGLE, NEXT, PREVIOUS, CYCLE }
}
