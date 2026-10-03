package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.ArrayList;
import java.util.List;

/** Pure, bounded solid visual geometry; no renderer, entity or game-state dependency. */
public final class SolidVisualMesh {
    public static final int MAX_QUADS = 768;
    public static final int MAX_INPUT_NODES = 2_048;
    private static final double EPSILON_SQUARED = 1.0E-12;
    private static final double TAU = Math.PI * 2.0;
    private static final Point X = new Point(1, 0, 0);
    private static final Point Y = new Point(0, 1, 0);
    private static final Point Z = new Point(0, 0, 1);

    private SolidVisualMesh() { }

    public record Point(double x, double y, double z) { }

    /** Width and depth are cross-section HALF axes, not full diameters. */
    public record Node(Point center, double width, double depth, int color) { }

    /** a/d belong to the start section, b/c to the next; color blends along U. */
    public record Face(Point a, Point b, Point c, Point d, int color, int endColor,
                       float uStart, float uEnd) { }

    /**
     * Four/six-sided elliptical tubes. A short budget resamples the whole path
     * instead of truncating it. Closed paths need at least three full sections;
     * open paths need one segment. No end caps are charged to this budget.
     * Invalid/oversized inputs are rejected before unbounded work/allocation.
     */
    public static List<Face> tube(List<Node> nodes, Point referenceAxis, int sides, int maxQuads, boolean closed) {
        if (nodes == null || (sides != 4 && sides != 6) || maxQuads < sides * (closed ? 3 : 1)
            || nodes.size() < (closed ? 3 : 2) || nodes.size() > MAX_INPUT_NODES) return List.of();
        int budget = Math.min(MAX_QUADS, maxQuads);
        List<Node> path = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            if (!valid(node)) return List.of();
            if (!path.isEmpty() && distanceSquared(path.getLast().center(), node.center()) <= EPSILON_SQUARED) continue;
            path.add(new Node(node.center(), halfAxis(node.width()), halfAxis(node.depth()), node.color()));
        }
        // The first section owns a repeated closed endpoint, including color.
        if (closed && path.size() > 1
            && distanceSquared(path.getFirst().center(), path.getLast().center()) <= EPSILON_SQUARED) path.removeLast();
        if (path.size() < (closed ? 3 : 2)) return List.of();

        int originalSegments = path.size() - (closed ? 0 : 1);
        int segments = Math.min(originalSegments, budget / sides);
        if (segments < (closed ? 3 : 1)) return List.of();
        if (segments < originalSegments) {
            path = resample(path, segments, closed);
            // A reversing/retraced path can sample the same position twice.
            // Compact again before normalizing tangents; never emit NaN faces.
            for (int i = path.size() - 1; i > 0; i--) {
                if (distanceSquared(path.get(i).center(), path.get(i - 1).center()) <= EPSILON_SQUARED) path.remove(i);
            }
            if (closed && path.size() > 1
                && distanceSquared(path.getFirst().center(), path.getLast().center()) <= EPSILON_SQUARED) path.removeLast();
            segments = path.size() - (closed ? 0 : 1);
            if (segments < (closed ? 3 : 1)) return List.of();
        }
        int count = path.size();
        double[] distances = distances(path, closed);
        double total = distances[distances.length - 1];
        if (!Double.isFinite(total) || total <= 1.0E-6) return List.of();

        Point[] tangent = new Point[count];
        Point[] normal = new Point[count];
        for (int i = 0; i < count; i++) tangent[i] = tangent(path, i, closed);
        Point reference = finite(referenceAxis) ? referenceAxis : Y;
        normal[0] = perpendicular(reference, tangent[0]);
        for (int i = 1; i < count; i++) normal[i] = transport(normal[i - 1], tangent[i - 1], tangent[i]);
        if (closed) {
            Point returned = transport(normal[count - 1], tangent[count - 1], tangent[0]);
            double correction = Math.atan2(dot(tangent[0], cross(returned, normal[0])), dot(returned, normal[0]));
            // Spread closed-loop frame twist along the full path, avoiding a
            // single flipped facet at the seam of a tilted/nonplanar orbit.
            for (int i = 1; i < count; i++) {
                normal[i] = rotate(normal[i], tangent[i], correction * distances[i] / total);
            }
        }

        Point[][] sections = new Point[count][sides];
        for (int i = 0; i < count; i++) {
            Node node = path.get(i);
            Point side = normalize(cross(tangent[i], normal[i]));
            for (int j = 0; j < sides; j++) {
                double angle = -TAU * j / sides;
                sections[i][j] = add(node.center(), add(scale(normal[i], Math.cos(angle) * node.width()),
                    scale(side, Math.sin(angle) * node.depth())));
            }
        }

        List<Face> faces = new ArrayList<>(segments * sides);
        for (int i = 0; i < segments; i++) {
            int next = (i + 1) % count;
            float uStart = (float) (distances[i] / total);
            float uEnd = i + 1 == segments ? 1.0f : (float) (distances[i + 1] / total);
            for (int j = 0; j < sides; j++) {
                int adjacent = (j + 1) % sides;
                faces.add(new Face(sections[i][j], sections[next][j], sections[next][adjacent], sections[i][adjacent],
                    path.get(i).color(), path.get(next).color(), uStart, uEnd));
            }
        }
        return List.copyOf(faces);
    }

    /** An eight-facet solid loot crystal. A triangle is encoded as a,b,c,c. */
    public static List<Face> diamond(Point center, double radius, double halfHeight, int color, int maxQuads) {
        if (maxQuads < 8 || !finite(center) || !positiveFinite(radius) || !positiveFinite(halfHeight)) return List.of();
        double r = halfAxis(radius);
        double h = halfAxis(halfHeight);
        Point top = add(center, new Point(0, h, 0));
        Point bottom = add(center, new Point(0, -h, 0));
        Point[] equator = {add(center, new Point(r, 0, 0)), add(center, new Point(0, 0, r)),
            add(center, new Point(-r, 0, 0)), add(center, new Point(0, 0, -r))};
        List<Face> faces = new ArrayList<>(8);
        for (int i = 0; i < 4; i++) {
            Point first = equator[i];
            Point next = equator[(i + 1) % 4];
            faces.add(new Face(top, next, first, first, color, color, i / 4.0f, (i + 1) / 4.0f));
            faces.add(new Face(bottom, first, next, next, color, color, i / 4.0f, (i + 1) / 4.0f));
        }
        return List.copyOf(faces);
    }

    private static List<Node> resample(List<Node> path, int segments, boolean closed) {
        double[] distance = distances(path, closed);
        double total = distance[distance.length - 1];
        int count = segments + (closed ? 0 : 1);
        List<Node> result = new ArrayList<>(count);
        int edge = 0;
        for (int i = 0; i < count; i++) {
            if (i == 0) { result.add(path.getFirst()); continue; }
            if (!closed && i == segments) { result.add(path.getLast()); continue; }
            double at = total * i / segments;
            while (edge + 1 < distance.length - 1 && distance[edge + 1] < at) edge++;
            Node a = path.get(edge);
            Node b = path.get((edge + 1) % path.size());
            double fraction = (at - distance[edge]) / (distance[edge + 1] - distance[edge]);
            result.add(new Node(add(a.center(), scale(subtract(b.center(), a.center()), fraction)),
                mix(a.width(), b.width(), fraction), mix(a.depth(), b.depth(), fraction), blend(a.color(), b.color(), fraction)));
        }
        return result;
    }

    private static double[] distances(List<Node> path, boolean closed) {
        int segments = path.size() - (closed ? 0 : 1);
        double[] result = new double[segments + 1];
        for (int i = 0; i < segments; i++) {
            result[i + 1] = result[i] + Math.sqrt(distanceSquared(path.get(i).center(), path.get((i + 1) % path.size()).center()));
        }
        return result;
    }

    private static Point tangent(List<Node> path, int index, boolean closed) {
        int last = path.size() - 1;
        Point before = index == 0 && !closed ? null
            : normalize(subtract(path.get(index).center(), path.get((index + last) % path.size()).center()));
        Point after = index == last && !closed ? null
            : normalize(subtract(path.get((index + 1) % path.size()).center(), path.get(index).center()));
        if (before == null) return after;
        if (after == null) return before;
        Point average = add(before, after);
        return dot(average, average) > EPSILON_SQUARED ? normalize(average) : after;
    }

    private static Point transport(Point normal, Point from, Point to) {
        Point axis = cross(from, to);
        double sine = Math.sqrt(dot(axis, axis));
        if (sine <= 1.0E-6) return perpendicular(normal, to);
        double cosine = Math.max(-1.0, Math.min(1.0, dot(from, to)));
        return perpendicular(rotate(normal, scale(axis, 1.0 / sine), Math.atan2(sine, cosine)), to);
    }

    private static Point perpendicular(Point reference, Point tangent) {
        Point projected = subtract(reference, scale(tangent, dot(reference, tangent)));
        if (dot(projected, projected) <= EPSILON_SQUARED) {
            Point axis = Math.abs(tangent.x()) <= Math.abs(tangent.y()) && Math.abs(tangent.x()) <= Math.abs(tangent.z())
                ? X : Math.abs(tangent.y()) <= Math.abs(tangent.z()) ? Y : Z;
            projected = subtract(axis, scale(tangent, dot(axis, tangent)));
        }
        return normalize(projected);
    }

    private static Point rotate(Point value, Point axis, double angle) {
        double cosine = Math.cos(angle);
        double sine = Math.sin(angle);
        return add(add(scale(value, cosine), scale(cross(axis, value), sine)),
            scale(axis, dot(axis, value) * (1.0 - cosine)));
    }

    private static boolean valid(Node node) {
        return node != null && finite(node.center()) && positiveFinite(node.width()) && positiveFinite(node.depth());
    }

    private static boolean finite(Point point) {
        return point != null && Double.isFinite(point.x()) && Double.isFinite(point.y()) && Double.isFinite(point.z())
            && Math.abs(point.x()) <= 1.0E9 && Math.abs(point.y()) <= 1.0E9 && Math.abs(point.z()) <= 1.0E9;
    }

    private static boolean positiveFinite(double value) { return Double.isFinite(value) && value > 0.0; }
    private static double halfAxis(double value) { return Math.max(1.0E-4, Math.min(64.0, value)); }
    private static double mix(double a, double b, double t) { return a + (b - a) * t; }
    private static Point add(Point a, Point b) { return new Point(a.x() + b.x(), a.y() + b.y(), a.z() + b.z()); }
    private static Point subtract(Point a, Point b) { return new Point(a.x() - b.x(), a.y() - b.y(), a.z() - b.z()); }
    private static Point scale(Point point, double scale) { return new Point(point.x() * scale, point.y() * scale, point.z() * scale); }
    private static double dot(Point a, Point b) { return a.x() * b.x() + a.y() * b.y() + a.z() * b.z(); }
    private static double distanceSquared(Point a, Point b) { Point delta = subtract(a, b); return dot(delta, delta); }
    private static Point normalize(Point point) { return scale(point, 1.0 / Math.sqrt(dot(point, point))); }
    private static Point cross(Point a, Point b) {
        return new Point(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
    }

    private static int blend(int a, int b, double t) {
        int color = 0;
        for (int shift = 0; shift <= 24; shift += 8) color |= (int) Math.round(mix(a >>> shift & 255, b >>> shift & 255, t)) << shift;
        return color;
    }
}
