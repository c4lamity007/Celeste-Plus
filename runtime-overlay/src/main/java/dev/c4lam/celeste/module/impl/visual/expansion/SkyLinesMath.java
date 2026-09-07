package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.ArrayList;
import java.util.List;

/** Bounded camera-relative celestial geometry, independent of Minecraft or GPU state. */
public final class SkyLinesMath {
    public static final double RADIUS = 128.0;
    public static final int MAX_SEGMENTS = 192;
    public static final int MAX_STARS = 24;
    public enum Pattern { ORBITAL_RIBBONS, CONSTELLATION_ARCS }
    public record Point(double x, double y, double z) { }
    public record Segment(Point from, Point to, double opacity, double width, int color) { }
    public record Star(Point position, double opacity, double radius, int color) { }
    public record Geometry(List<Segment> segments, List<Star> stars) {
        public static final Geometry EMPTY = new Geometry(List.of(), List.of());
        public boolean isEmpty() { return segments.isEmpty() && stars.isEmpty(); }
    }

    private SkyLinesMath() { }

    public static Geometry sample(Pattern pattern, double seconds, double intensity) {
        double strength = unit(intensity);
        if (strength <= 0) return Geometry.EMPTY;
        double time = Double.isFinite(seconds) ? seconds : 0;
        return pattern == Pattern.CONSTELLATION_ARCS ? constellations(time, strength) : ribbons(time, strength);
    }

    private static Geometry ribbons(double time, double strength) {
        List<Segment> segments = new ArrayList<>(MAX_SEGMENTS);
        List<Star> stars = new ArrayList<>(3);
        for (int arc = 0; arc < 3; arc++) {
            double phase = arc * 2.13;
            int color = arc == 0 ? 0x8BE9EB : arc == 1 ? 0xB3A3F5 : 0xC5DEFA;
            Point previous = ribbonPoint(arc, 0, time);
            for (int step = 1; step <= 64; step++) {
                double u = step / 64.0;
                Point next = ribbonPoint(arc, u, time);
                double envelope = endFade(u - .5 / 64);
                double pulse = .73 + .27 * Math.sin(u * Math.PI * 2 - time * .24 + phase);
                segments.add(new Segment(previous, next, strength * envelope * pulse * .9,
                    .14 + .08 * envelope, color));
                previous = next;
            }
            // A single moving highlight per arc. Its endpoints fade before recycling.
            double travel = fraction(time * .016 + arc * .31);
            stars.add(new Star(ribbonPoint(arc, travel, time), strength * endFade(travel) * .9, .32, color));
        }
        return new Geometry(List.copyOf(segments), List.copyOf(stars));
    }

    private static Point ribbonPoint(int arc, double u, double time) {
        double azimuth = arc * 2.13 - .92 + u * (1.88 - arc * .16) + time * .009;
        double elevation = .43 + arc * .16 + .10 * Math.sin(u * Math.PI)
            + .025 * Math.sin(u * Math.PI * 2 + time * .13 + arc);
        return sphere(azimuth, elevation);
    }

    private static Geometry constellations(double time, double strength) {
        List<Segment> segments = new ArrayList<>(80);
        List<Star> stars = new ArrayList<>(MAX_STARS);
        for (int arc = 0; arc < 4; arc++) {
            Point previous = constellationPoint(arc, 0, time);
            int color = (arc & 1) == 0 ? 0xADDEE9 : 0xC1B4F1;
            for (int node = 0; node <= 5; node++) {
                double u = node / 5.0;
                double shimmer = .72 + .28 * Math.sin(time * .32 + arc * 1.8 + node * 1.31);
                stars.add(new Star(constellationPoint(arc, u, time), strength * shimmer * .95,
                    node == 2 || node == 4 ? .40 : .26, color));
            }
            for (int step = 1; step <= 20; step++) {
                double u = step / 20.0;
                Point next = constellationPoint(arc, u, time);
                segments.add(new Segment(previous, next, strength * (.42 + .18 * endFade(u)), .12, color));
                previous = next;
            }
        }
        return new Geometry(List.copyOf(segments), List.copyOf(stars));
    }

    private static Point constellationPoint(int arc, double u, double time) {
        double azimuth = arc * Math.PI * .5 - .33 + u * .66 + time * .005;
        double elevation = .47 + (arc % 3) * .18 + .065 * Math.sin(u * Math.PI * 3 + arc * .6);
        return sphere(azimuth, elevation);
    }

    private static Point sphere(double azimuth, double elevation) {
        double horizontal = Math.cos(elevation) * RADIUS;
        return new Point(Math.cos(azimuth) * horizontal, Math.sin(elevation) * RADIUS,
            Math.sin(azimuth) * horizontal);
    }
    private static double fraction(double x) { return x - Math.floor(x); }
    private static double endFade(double u) { return smooth(u / .16) * smooth((1 - u) / .16); }
    private static double smooth(double x) { x = unit(x); return x * x * (3 - 2 * x); }
    private static double unit(double x) { return Double.isFinite(x) ? Math.max(0, Math.min(1, x)) : 0; }

    /** Integrating speed avoids phase jumps when the setting changes; long gaps never jump the sky. */
    public static final class Motion {
        private long previous;
        private boolean initialized, previouslyPaused;
        private double seconds;
        public synchronized double sample(long now, boolean paused, double speed) {
            double rate = Double.isFinite(speed) ? Math.max(0, Math.min(2, speed)) : 0;
            if (initialized && !paused && !previouslyPaused) {
                seconds += Math.min(.25, Math.max(0, (now - previous) / 1_000_000_000.0)) * rate;
            }
            previous = now;
            previouslyPaused = paused;
            initialized = true;
            return seconds;
        }
        public synchronized void reset() { previous = 0; initialized = previouslyPaused = false; seconds = 0; }
    }
}
