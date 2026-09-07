package dev.c4lam.celeste.media;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Bounded immutable LRC timeline. Parsing happens on the lyric worker, never during drawing. */
public record TimedLyrics(List<Line> lines) {
    public static final TimedLyrics EMPTY = new TimedLyrics(List.of());
    public static final int MAX_LINES = 1500, MAX_TEXT = 180;
    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:([+-]?\\d{1,7})]", Pattern.CASE_INSENSITIVE);
    public TimedLyrics { lines = List.copyOf(lines); }
    public record Line(long timeMs, String text) { }
    public record Cue(String current, String next, double progress) {
        public static final Cue EMPTY = new Cue("", "", 0);
    }
    public static TimedLyrics parse(String lrc) {
        if (lrc == null || lrc.length() > 512_000) return EMPTY;
        long offset = 0;
        var offsetMatch = OFFSET.matcher(lrc);
        if (offsetMatch.find()) offset = Long.parseLong(offsetMatch.group(1));
        List<Line> result = new ArrayList<>();
        for (String row : lrc.split("\\R", 6000)) {
            var match = STAMP.matcher(row);
            List<Long> times = new ArrayList<>(2);
            int end = 0;
            while (match.find() && times.size() < 32) {
                int seconds = Integer.parseInt(match.group(2));
                if (seconds >= 60) continue;
                String fraction = match.group(3);
                long millis = fraction == null ? 0 : Integer.parseInt((fraction + "00").substring(0, 3));
                times.add(Math.max(0, Long.parseLong(match.group(1)) * 60_000 + seconds * 1000L + millis - offset));
                end = match.end();
            }
            if (times.isEmpty()) continue;
            String value = clean(row.substring(end).replaceAll("<\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?>", ""));
            for (long time : times) {
                if (result.size() == MAX_LINES) break;
                result.add(new Line(time, value));
            }
            if (result.size() == MAX_LINES) break;
        }
        result.sort(Comparator.comparingLong(Line::timeMs));
        // Duplicate timestamps choose the last supplied line deterministically.
        List<Line> unique = new ArrayList<>(result.size());
        for (Line line : result) {
            if (!unique.isEmpty() && unique.getLast().timeMs() == line.timeMs()) unique.set(unique.size() - 1, line);
            else unique.add(line);
        }
        return unique.isEmpty() ? EMPTY : new TimedLyrics(unique);
    }
    public Cue cue(long positionMs, long durationMs) {
        // Native progress stops at duration; a stray later LRC stamp must not keep a finished song visible.
        if (durationMs > 0 && positionMs >= durationMs) return Cue.EMPTY;
        int low = 0, high = lines.size() - 1, found = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (lines.get(middle).timeMs() <= positionMs) { found = middle; low = middle + 1; }
            else high = middle - 1;
        }
        if (found < 0) return new Cue("", lines.isEmpty() ? "" : lines.getFirst().text(), 0);
        Line line = lines.get(found);
        long end = found + 1 < lines.size() ? lines.get(found + 1).timeMs()
            : Math.min(durationMs > line.timeMs() ? durationMs : Long.MAX_VALUE, line.timeMs() + 8000);
        if (positionMs >= end || positionMs - line.timeMs() > 16_000) return Cue.EMPTY;
        double progress = Math.clamp((positionMs - line.timeMs()) / (double)Math.max(1, end - line.timeMs()), 0, 1);
        return new Cue(line.text(), found + 1 < lines.size() ? lines.get(found + 1).text() : "", progress);
    }
    public static String clean(String input) {
        if (input == null) return "";
        String clean = input.replaceAll("[\\p{Cntrl}\\u00a7]", "").strip();
        int count = clean.codePointCount(0, clean.length());
        return count <= MAX_TEXT ? clean : clean.substring(0, clean.offsetByCodePoints(0, MAX_TEXT - 1)) + "\u2026";
    }
}