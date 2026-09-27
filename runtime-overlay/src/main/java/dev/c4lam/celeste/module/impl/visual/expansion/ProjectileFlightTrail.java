package dev.c4lam.celeste.module.impl.visual.expansion;

import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** A short history of observed positions, never extrapolated beyond the projectile. */
final class ProjectileFlightTrail {
    static final int MAX_PROJECTILES = 8;
    private static final int HISTORY_TICKS = 20;
    private final IdentityHashMap<Object, Track> tracks = new IdentityHashMap<>();
    private Object world;
    private long frame;

    void beginFrame(Object currentWorld) {
        if (world != currentWorld) {
            clear();
            world = currentWorld;
        }
        frame++;
    }

    void retain(List<?> selected) {
        tracks.keySet().removeIf(projectile -> selected.stream().noneMatch(candidate -> candidate == projectile));
    }

    List<Vec3d> observe(Object projectile, int age, Vec3d tickStart, Vec3d current) {
        if (projectile == null || !finite(tickStart) || !finite(current)) {
            tracks.remove(projectile);
            return List.of();
        }
        Track track = tracks.get(projectile);
        if (track == null) {
            if (tracks.size() >= MAX_PROJECTILES) return List.of();
            track = new Track();
            tracks.put(projectile, track);
        }
        track.seen = frame;
        Sample last = track.samples.peekLast();
        if (last != null && (age < last.age || age > last.age + 2
            || current.squaredDistanceTo(last.position) > 256)) {
            track.samples.clear(); // Do not connect a teleport, reused age or tracking gap.
        }
        if (track.samples.isEmpty() && current.squaredDistanceTo(tickStart) <= 256) {
            track.samples.add(new Sample(age - 1, tickStart));
        }
        last = track.samples.peekLast();
        if (last != null && last.age == age) track.samples.removeLast();
        track.samples.addLast(new Sample(age, current));
        while (track.samples.size() > HISTORY_TICKS + 1
            || track.samples.getFirst().age < age - HISTORY_TICKS) track.samples.removeFirst();

        // Existing trail material fades from its first point, so the observed head comes first.
        List<Vec3d> points = new ArrayList<>(track.samples.size());
        var iterator = track.samples.descendingIterator();
        while (iterator.hasNext()) {
            Vec3d point = iterator.next().position;
            if (points.isEmpty() || points.getLast().squaredDistanceTo(point) > 1.0E-12D) points.add(point);
        }
        return points;
    }

    void endFrame() {
        tracks.values().removeIf(track -> track.seen != frame);
    }

    void clear() {
        tracks.clear();
        world = null;
        frame = 0;
    }

    private static boolean finite(Vec3d vector) {
        return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y)
            && Double.isFinite(vector.z);
    }

    private static final class Track {
        private final ArrayDeque<Sample> samples = new ArrayDeque<>();
        private long seen;
    }

    private record Sample(int age, Vec3d position) { }
}
