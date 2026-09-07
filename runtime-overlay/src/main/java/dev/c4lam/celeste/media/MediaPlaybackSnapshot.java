package dev.c4lam.celeste.media;

import java.time.Instant;

/** Immutable, render-thread-safe view of Windows' current system media session. */
public record MediaPlaybackSnapshot(
    boolean available,
    boolean playing,
    boolean shuffle,
    boolean canPrevious,
    boolean canToggle,
    boolean canNext,
    boolean canShuffle,
    String source,
    String sourceId,
    String title,
    String artist,
    String album,
    String artworkKey,
    String artworkBase64,
    long progressMs,
    long durationMs,
    String message,
    Instant updatedAt,
    TimedLyrics lyrics
) {
    public MediaPlaybackSnapshot { lyrics = lyrics == null ? TimedLyrics.EMPTY : lyrics; }

    /** Existing media HUD producers have no lyric capability unless a payload is explicitly supplied. */
    public MediaPlaybackSnapshot(boolean available, boolean playing, boolean shuffle, boolean canPrevious,
                                 boolean canToggle, boolean canNext, boolean canShuffle, String source,
                                 String sourceId, String title, String artist, String album, String artworkKey,
                                 String artworkBase64, long progressMs, long durationMs, String message, Instant updatedAt) {
        this(available, playing, shuffle, canPrevious, canToggle, canNext, canShuffle, source, sourceId,
            title, artist, album, artworkKey, artworkBase64, progressMs, durationMs, message, updatedAt, TimedLyrics.EMPTY);
    }

    public static MediaPlaybackSnapshot empty(String message) {
        return new MediaPlaybackSnapshot(
            false, false, false, false, false, false, false,
            "System media", "", "Nothing playing", "", "", "", "", 0, 0,
            message == null ? "" : message, Instant.now()
        );
    }

    /** Smoothly advances the displayed timeline between system-session polls. */
    public long estimatedProgressMs() {
        long progress = progressMs;
        if (playing && updatedAt != null) {
            progress += Math.max(0, Instant.now().toEpochMilli() - updatedAt.toEpochMilli());
        }
        return durationMs <= 0 ? Math.max(0, progress) : Math.max(0, Math.min(durationMs, progress));
    }
}
