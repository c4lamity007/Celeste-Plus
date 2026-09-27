package dev.c4lam.celeste.hud;

import dev.c4lam.celeste.media.MediaPlaybackSnapshot;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Render-thread playback clock for quantized system-media samples.
 *
 * <p>The Windows bridge reports whole seconds every roughly 350 ms. Treating
 * each report as an exact position makes a locally extrapolated clock jump
 * backwards on most polls. This clock keeps one monotonic anchor for the same
 * playing track, applies only a small forward phase correction, and accepts a
 * large discontinuity as an intentional seek/replay.</p>
 */
public final class DynamicIslandPlaybackClock {
    static final long SEEK_THRESHOLD_MS = 2_000L;
    static final long MAX_FORWARD_PHASE_CORRECTION_MS = 120L;
    static final long MAX_STALE_EXTRAPOLATION_MS = 2_000L;

    private MediaPlaybackSnapshot observedSnapshot;
    private String trackKey = "";
    private long anchorPositionMs;
    private long anchorNanos;
    private long lastPositionMs;
    private long lastRawPositionMs;
    private long rawAdvanceBasePositionMs;
    private long lastNowNanos;
    private Instant lastSampleUpdatedAt;
    private boolean playing;
    private boolean hasTrack;
    private boolean hasNow;

    /** Uses {@link System#nanoTime()} and is the normal render integration API. */
    public long positionMs(MediaPlaybackSnapshot snapshot) {
        return positionMs(snapshot, System.nanoTime());
    }

    /** Deterministic overload for focused tests and callers with a shared frame clock. */
    public long positionMs(MediaPlaybackSnapshot snapshot, long monotonicNanos) {
        long now = normalizeNow(monotonicNanos);
        if (snapshot == null || !snapshot.available()) {
            clearTrack();
            return 0L;
        }

        String nextTrackKey = trackKey(snapshot);
        long samplePosition = clampToDuration(Math.max(0L, snapshot.progressMs()), snapshot.durationMs());
        if (!hasTrack || !trackKey.equals(nextTrackKey)) {
            initializeTrack(snapshot, nextTrackKey, samplePosition, now);
            return lastPositionMs;
        }

        boolean freshSample = snapshot != observedSnapshot;
        long predicted = extrapolated(now, snapshot.durationMs());
        if (freshSample) {
            observedSnapshot = snapshot;
            if (isOlder(snapshot.updatedAt(), lastSampleUpdatedAt)) {
                lastPositionMs = Math.max(lastPositionMs, predicted);
                return lastPositionMs;
            }

            long error = samplePosition - predicted;
            long rawDelta = samplePosition - lastRawPositionMs;
            boolean backwardSeek = rawDelta <= -SEEK_THRESHOLD_MS;
            boolean forwardSeek = error >= SEEK_THRESHOLD_MS;
            boolean clearSeek = backwardSeek || forwardSeek;
            long reconciled;
            if (clearSeek) {
                // Explicit forward/backward seeks and same-track replay are the
                // only normal cases allowed to move the presentation backwards.
                reconciled = samplePosition;
            } else if (error > 0L) {
                reconciled = safeAdd(predicted, Math.min(error, MAX_FORWARD_PHASE_CORRECTION_MS));
            } else {
                // A sub-threshold backward report is expected whole-second
                // quantization, not a reason to rewind the displayed lyric.
                reconciled = predicted;
            }
            reconciled = clampToDuration(reconciled, snapshot.durationMs());
            if (!clearSeek) {
                reconciled = Math.max(lastPositionMs, reconciled);
            }

            anchorPositionMs = reconciled;
            anchorNanos = now;
            playing = snapshot.playing();
            lastPositionMs = reconciled;
            if (clearSeek || samplePosition > lastRawPositionMs) {
                lastRawPositionMs = samplePosition;
                rawAdvanceBasePositionMs = reconciled;
            }
            lastSampleUpdatedAt = snapshot.updatedAt();
            return reconciled;
        }

        // The same immutable snapshot is read for every render frame between
        // service polls. It must advance from the existing anchor, not rebase.
        lastPositionMs = Math.max(lastPositionMs, predicted);
        return lastPositionMs;
    }

    public void reset() {
        clearTrack();
        observedSnapshot = null;
        lastNowNanos = 0L;
        hasNow = false;
    }

    private void initializeTrack(MediaPlaybackSnapshot snapshot, String nextTrackKey,
                                 long samplePosition, long now) {
        observedSnapshot = snapshot;
        trackKey = nextTrackKey;
        anchorPositionMs = samplePosition;
        anchorNanos = now;
        lastPositionMs = samplePosition;
        lastRawPositionMs = samplePosition;
        rawAdvanceBasePositionMs = samplePosition;
        lastSampleUpdatedAt = snapshot.updatedAt();
        playing = snapshot.playing();
        hasTrack = true;
    }

    private long extrapolated(long now, long durationMs) {
        if (!playing) {
            return clampToDuration(anchorPositionMs, durationMs);
        }
        long elapsedMs = elapsedMillis(anchorNanos, now);
        long predicted = safeAdd(anchorPositionMs, elapsedMs);
        long staleCeiling = safeAdd(rawAdvanceBasePositionMs, MAX_STALE_EXTRAPOLATION_MS);
        return clampToDuration(Math.min(predicted, staleCeiling), durationMs);
    }

    private long normalizeNow(long proposed) {
        if (!hasNow) {
            hasNow = true;
            lastNowNanos = proposed;
            return proposed;
        }
        if (proposed - lastNowNanos < 0L) {
            return lastNowNanos;
        }
        lastNowNanos = proposed;
        return proposed;
    }

    private void clearTrack() {
        observedSnapshot = null;
        trackKey = "";
        anchorPositionMs = 0L;
        anchorNanos = 0L;
        lastPositionMs = 0L;
        lastRawPositionMs = 0L;
        rawAdvanceBasePositionMs = 0L;
        lastSampleUpdatedAt = null;
        playing = false;
        hasTrack = false;
    }

    private static long elapsedMillis(long startNanos, long endNanos) {
        long elapsed = endNanos - startNanos;
        return elapsed <= 0L ? 0L : TimeUnit.NANOSECONDS.toMillis(elapsed);
    }

    private static long clampToDuration(long positionMs, long durationMs) {
        long nonNegative = Math.max(0L, positionMs);
        return durationMs > 0L ? Math.min(nonNegative, durationMs) : nonNegative;
    }

    private static long safeAdd(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static boolean isOlder(Instant candidate, Instant accepted) {
        return candidate != null && accepted != null && candidate.isBefore(accepted);
    }

    private static String trackKey(MediaPlaybackSnapshot snapshot) {
        return safe(snapshot.sourceId()) + '\u0000' + safe(snapshot.title()) + '\u0000'
            + safe(snapshot.artist());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
