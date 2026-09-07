package dev.c4lam.celeste.media;

import dev.redstones.mediaplayerinfo.MediaInfo;
import java.util.Objects;

/** One-track in-memory parse cache, fed only by actual native media payloads on the media worker. */
final class NativeMediaLyrics {
    private String owner, title, artist, payload;
    private long duration;
    private TimedLyrics parsed = TimedLyrics.EMPTY;

    synchronized TimedLyrics update(String source, MediaInfo media) {
        if (media == null) { reset(); return TimedLyrics.EMPTY; }
        String input = media.getSyncedLyrics();
        if (Objects.equals(owner, source) && Objects.equals(title, media.getTitle())
            && Objects.equals(artist, media.getArtist()) && duration == media.getDuration()
            && Objects.equals(payload, input)) return parsed;
        owner = source; title = media.getTitle(); artist = media.getArtist();
        duration = media.getDuration(); payload = input;
        parsed = input.isBlank() ? TimedLyrics.EMPTY : TimedLyrics.parse(input);
        return parsed;
    }
    synchronized void reset() {
        owner = title = artist = payload = null; duration = 0; parsed = TimedLyrics.EMPTY;
    }
}
