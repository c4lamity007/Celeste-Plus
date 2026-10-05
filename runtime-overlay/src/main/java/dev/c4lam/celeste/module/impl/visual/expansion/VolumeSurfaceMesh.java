package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bounded exact-position normal welding for the unified transparent surface pass. */
public final class VolumeSurfaceMesh {
    public static final int MAX_FACES = 768;
    private static final Point UP = new Point(0, 1, 0);
    private static final double EPSILON = 1.0E-24;

    private VolumeSurfaceMesh() { }

    public record Point(double x, double y, double z) {
        public Point {
            // Signed zero is the same position, including across a closing seam.
            if (x == 0) x = 0;
            if (y == 0) y = 0;
            if (z == 0) z = 0;
        }
    }
    public record Face(Point a, Point b, Point c, Point d) { }
    public record Vertex(Point position, Point normal) { }

    /**
     * Four vertices per input face in the original order; no positions are snapped
     * or moved. Adjacent faces share the exact same angle-weighted unit normal.
     * Degenerate triangle-quads are supported. Invalid/oversized input is rejected.
     */
    public static List<Vertex> smooth(List<Face> faces) {
        if (faces == null || faces.isEmpty() || faces.size() > MAX_FACES) return List.of();
        for (Face face : faces) {
            if (face == null || !finite(face.a()) || !finite(face.b()) || !finite(face.c()) || !finite(face.d()))
                return List.of();
        }
        Map<Point, Sum> normals = new HashMap<>(faces.size() * 3);
        for (Face face : faces) {
            Point[] corners = {face.a(), face.b(), face.c(), face.d()};
            Point[] polygon = new Point[4];
            int count = 0;
            for (Point corner : corners) {
                normals.computeIfAbsent(corner, ignored -> new Sum());
                if (count == 0 || !polygon[count - 1].equals(corner)) polygon[count++] = corner;
            }
            if (count > 1 && polygon[count - 1].equals(polygon[0])) count--;
            if (count < 3) continue;
            Point area = new Point(0, 0, 0);
            for (int i = 1; i + 1 < count; i++) {
                area = add(area, cross(sub(polygon[i], polygon[0]), sub(polygon[i + 1], polygon[0])));
            }
            if (dot(area, area) < EPSILON) continue;
            Point normal = unit(area);
            for (int i = 0; i < count; i++) {
                Point point = polygon[i];
                Point before = sub(polygon[(i + count - 1) % count], point);
                Point after = sub(polygon[(i + 1) % count], point);
                if (dot(before, before) < EPSILON || dot(after, after) < EPSILON) continue;
                Point crossed = cross(before, after);
                double angle = Math.atan2(Math.sqrt(dot(crossed, crossed)), dot(before, after));
                normals.get(point).add(normal, angle);
            }
        }
        List<Vertex> result = new ArrayList<>(faces.size() * 4);
        for (Face face : faces) {
            for (Point point : new Point[]{face.a(), face.b(), face.c(), face.d()})
                result.add(new Vertex(point, normals.get(point).normal()));
        }
        return List.copyOf(result);
    }

    private static final class Sum {
        double x, y, z;
        Point fallback = UP;
        Point resolved;
        void add(Point normal, double weight) {
            x += normal.x() * weight; y += normal.y() * weight; z += normal.z() * weight;
            fallback = normal;
        }
        Point normal() {
            if (resolved == null) {
                Point sum = new Point(x, y, z);
                resolved = dot(sum, sum) < EPSILON ? fallback : unit(sum);
            }
            return resolved;
        }
    }

    private static boolean finite(Point p) {
        return p != null && Double.isFinite(p.x()) && Double.isFinite(p.y()) && Double.isFinite(p.z())
            && Math.abs(p.x()) <= 1.0E9 && Math.abs(p.y()) <= 1.0E9 && Math.abs(p.z()) <= 1.0E9;
    }
    private static Point add(Point a, Point b) { return new Point(a.x() + b.x(), a.y() + b.y(), a.z() + b.z()); }
    private static Point sub(Point a, Point b) { return new Point(a.x() - b.x(), a.y() - b.y(), a.z() - b.z()); }
    private static double dot(Point a, Point b) { return a.x() * b.x() + a.y() * b.y() + a.z() * b.z(); }
    private static Point cross(Point a, Point b) {
        return new Point(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
    }
    private static Point unit(Point p) {
        double inverse = 1.0 / Math.sqrt(dot(p, p));
        return new Point(p.x() * inverse, p.y() * inverse, p.z() * inverse);
    }
}
