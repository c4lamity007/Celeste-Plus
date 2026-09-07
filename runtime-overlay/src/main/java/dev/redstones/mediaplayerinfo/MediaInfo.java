package dev.redstones.mediaplayerinfo;

/** JNI data shape required by the bundled Windows MediaPlayerInfo bridge. */
public final class MediaInfo {
    private final String title;
    private final String artist;
    private final byte[] artworkPng;
    private final long position;
    private final long duration;
    private final boolean playing;
    private final String syncedLyrics;

    /** Preserve the exact constructor descriptor used by the current DLL, which has no lyric payload. */
    public MediaInfo(String title, String artist, byte[] artworkPng,
                     long position, long duration, boolean playing) {
        this(title, artist, artworkPng, position, duration, playing, "");
    }

    /** Optional in-memory LRC payload from a bridge that actually supports synchronized lyrics. */
    public MediaInfo(String title, String artist, byte[] artworkPng,
                     long position, long duration, boolean playing, String syncedLyrics) {
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        this.artworkPng = artworkPng == null ? new byte[0] : artworkPng.clone();
        this.position = Math.max(0L, position);
        this.duration = Math.max(0L, duration);
        this.playing = playing;
        this.syncedLyrics = syncedLyrics == null || syncedLyrics.length() > 512_000 ? "" : syncedLyrics;
    }

    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public byte[] getArtworkPng() { return artworkPng.clone(); }
    public long getPosition() { return position; }
    public long getDuration() { return duration; }
    public boolean isPlaying() { return playing; }
    public String getSyncedLyrics() { return syncedLyrics; }
}
