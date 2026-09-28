package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Java 21 checks of the actual production mesh, without Minecraft or GPU fixtures. */
public final class SolidVisualMeshCheck {
    private static long assertions;
    private static int builds;
    private static final SolidVisualMesh.Point UP = point(0, 1, 0);

    public static void main(String[] args) {
        tubesAndBudgets();
        framesAndDegenerates();
        boundedInvalidInputs();
        diamonds();
        frameMotion();
        System.out.println("PASS SolidVisualMeshCheck: " + assertions + " assertions over " + builds + " mesh builds");
    }

    private static void tubesAndBudgets() {
        for (int sides : new int[]{4, 6}) {
            for (double phase : new double[]{0.0, 0.27, 1.2, 4.8}) {
                List<SolidVisualMesh.Node> ring = ring(24, phase, true);
                for (int budget = 0; budget <= 800; budget++) {
                    var faces = tube(ring, UP, sides, budget, true);
                    verifyTube(faces, sides, budget, true, true);
                    int expected = budget < sides * 3 ? 0 : Math.min(24, Math.min(768, budget) / sides) * sides;
                    require(faces.size() == expected, "whole closed ring adapts to every budget");
                }
            }
            List<SolidVisualMesh.Node> helix = new ArrayList<>();
            for (int i = 0; i <= 64; i++) {
                double t = i / 64.0;
                helix.add(node(point(Math.cos(t * 8.5), t * 2, Math.sin(t * 8.5)), 0.14, 0.105, color(i)));
            }
            for (int budget = 0; budget <= 768; budget += 3) {
                var faces = tube(helix, UP, sides, budget, false);
                verifyTube(faces, sides, budget, false, true);
                require(faces.size() == Math.min(64, budget / sides) * sides, "open path budget is complete sections");
            }
            var straight = tube(List.of(node(point(0, 0, 0), 0.3, 0.1, 0xFF123456),
                node(point(2, 0, 0), 0.3, 0.1, 0xFFABCDEF)), UP, sides, 768, false);
            for (var face : straight) {
                require(face.color() == 0xFF123456 && face.endColor() == 0xFFABCDEF, "path endpoint colors preserved");
                var normal = cross(subtract(face.b(), face.a()), subtract(face.c(), face.a()));
                var radial = point(0, (face.a().y() + face.c().y()) / 2, (face.a().z() + face.c().z()) / 2);
                require(dot(normal, radial) > 0, "tube facets wind outward");
            }
        }
        var horizontal = tube(ring(24, 0, false), UP, 6, 144, true);
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        double maxRadius = 0;
        for (var face : horizontal) for (var p : corners(face)) {
            minY = Math.min(minY, p.y()); maxY = Math.max(maxY, p.y());
            maxRadius = Math.max(maxRadius, Math.hypot(p.x(), p.z()));
        }
        close(0.32, maxY - minY, "width is a genuine cross-section half axis");
        require(maxRadius > 1.09 && maxRadius < 1.13, "ring also has radial thickness, not a flat ribbon");

        List<SolidVisualMesh.Node> explicitSeam = new ArrayList<>(ring(24, 0.2, true));
        explicitSeam.add(node(explicitSeam.getFirst().center(), 0.9, 0.7, 0x01020304));
        var seam = tube(explicitSeam, UP, 6, 144, true);
        verifyTube(seam, 6, 144, true, true);
        require(seam.getLast().endColor() == explicitSeam.getFirst().color(), "first color owns repeated closing point");
    }

    private static void framesAndDegenerates() {
        for (var reference : Arrays.asList(UP, point(0, 0, 0), point(1, 0, 0), point(0, 0, 1), null,
            point(Double.NaN, 0, 0))) {
            var vertical = tube(List.of(node(point(0, 0, 0)), node(point(0, 1, 0)), node(point(0, 2, 0))), reference, 6, 768, false);
            verifyTube(vertical, 6, 768, false, true);
            require(vertical.size() == 12, "vertical and degenerate reference axes use a stable frame");
            for (int side = 0; side < 6; side++) {
                close(vertical.get(side).a().x(), vertical.get(side).b().x(), "vertical frame x continuity");
                close(vertical.get(side).a().z(), vertical.get(side).b().z(), "vertical frame z continuity");
            }
        }
        var repeats = List.of(node(point(0, 0, 0)), node(point(0, 0, 0)), node(point(0, 1, 0)),
            node(point(0, 1, 0)), node(point(1, 2, 0)));
        verifyTube(tube(repeats, UP, 6, 768, false), 6, 768, false, true);
        var reversals = List.of(node(point(0, 0, 0)), node(point(1, 0, 0)), node(point(0, 0, 0)),
            node(point(1, 0, 0)), node(point(0, 0, 0)), node(point(-1, 0, 0)));
        for (int budget = 0; budget < 128; budget++) for (boolean closed : new boolean[]{false, true}) {
            verifyTube(tube(reversals, UP, 6, budget, closed), 6, budget, closed, false);
        }
        require(tube(List.of(node(point(0, 0, 0)), node(point(0, 0, 0))), UP, 6, 768, false).isEmpty(), "zero path rejected");
    }

    private static void boundedInvalidInputs() {
        var good = List.of(node(point(0, 0, 0)), node(point(1, 0, 0)));
        for (int invalidSides : new int[]{Integer.MIN_VALUE, -6, 0, 1, 3, 5, 7, Integer.MAX_VALUE}) {
            require(tube(good, UP, invalidSides, Integer.MAX_VALUE, false).isEmpty(), "unsupported polygon side count rejected");
        }
        require(tube(null, UP, 6, 768, false).isEmpty(), "null path rejected");
        require(tube(good, UP, 6, -1, false).isEmpty(), "negative budget rejected");
        for (var bad : Arrays.asList(null, node(null), node(point(Double.NaN, 0, 0)),
            node(point(Double.POSITIVE_INFINITY, 0, 0)), node(point(Double.MAX_VALUE, 0, 0)),
            node(point(0, 0, 0), 0, 1, -1), node(point(0, 0, 0), -1, 1, -1),
            node(point(0, 0, 0), Double.NaN, 1, -1), node(point(0, 0, 0), 1, Double.POSITIVE_INFINITY, -1))) {
            require(tube(Arrays.asList(bad, good.getLast()), UP, 6, 768, false).isEmpty(), "invalid node rejected atomically");
        }
        List<SolidVisualMesh.Node> oversized = new AbstractList<>() {
            @Override public SolidVisualMesh.Node get(int index) { throw new AssertionError("oversized input must not be read"); }
            @Override public int size() { return Integer.MAX_VALUE; }
        };
        require(tube(oversized, UP, 6, 768, false).isEmpty(), "oversized input rejected before allocation/iteration");
        var many = tube(ring(SolidVisualMesh.MAX_INPUT_NODES, 0.5, true), UP, 6, Integer.MAX_VALUE, true);
        verifyTube(many, 6, 768, true, true);
        require(many.size() == 768, "hard output cap applies to arbitrarily generous budget");
        var hugeAxes = tube(List.of(node(point(-1.0E9, 0, 0), Double.MAX_VALUE, Double.MAX_VALUE, -1),
            node(point(1.0E9, 0, 0), Double.MAX_VALUE, Double.MAX_VALUE, -1)), UP, 6, 6, false);
        verifyTube(hugeAxes, 6, 6, false, true);
        for (var face : hugeAxes) for (var p : corners(face)) require(Math.abs(p.y()) <= 64 && Math.abs(p.z()) <= 64,
            "finite oversized half axes are safely clamped");
        try { many.add(many.getFirst()); throw new AssertionError("mutable result"); }
        catch (UnsupportedOperationException expected) { assertions++; }
    }

    private static void diamonds() {
        var center = point(2, 3, 4);
        for (int budget = -1; budget <= 800; budget++) {
            var faces = SolidVisualMesh.diamond(center, 0.4, 0.7, 0xCC49AAFF, budget); builds++;
            require(faces.size() == (budget < 8 ? 0 : 8), "diamond is all eight facets or absent");
            Set<SolidVisualMesh.Point> vertices = new HashSet<>();
            for (var face : faces) {
                require(face.c() == face.d(), "diamond triangle encodes exact repeated final point");
                for (var p : corners(face)) { finite(p); vertices.add(p); }
                require(face.color() == 0xCC49AAFF && face.endColor() == face.color(), "diamond color");
                var normal = cross(subtract(face.b(), face.a()), subtract(face.c(), face.a()));
                var outside = subtract(point((face.a().x() + face.b().x() + face.c().x()) / 3,
                    (face.a().y() + face.b().y() + face.c().y()) / 3,
                    (face.a().z() + face.b().z() + face.c().z()) / 3), center);
                require(dot(normal, outside) > 0, "nondegenerate outward diamond facets");
            }
            if (!faces.isEmpty()) require(vertices.size() == 6, "shared six diamond vertices");
        }
        require(SolidVisualMesh.diamond(null, 1, 1, 0, 8).isEmpty(), "invalid diamond center");
        require(SolidVisualMesh.diamond(center, 0, 1, 0, 8).isEmpty(), "zero diamond radius");
        require(SolidVisualMesh.diamond(center, 1, Double.NaN, 0, 8).isEmpty(), "invalid diamond height");
    }

    private static void frameMotion() {
        var first = tube(ring(24, 0.40, true), UP, 6, 144, true);
        var next = tube(ring(24, 0.41, true), UP, 6, 144, true);
        double movement = 0;
        for (int i = 0; i < first.size(); i++) {
            double step = distance(first.get(i).a(), next.get(i).a());
            require(step > 0 && step < 0.04, "fractional phase smoothly moves solid vertices"); movement += step;
        }
        require(movement > 0.2, "animation is not a static sampled plane");
    }

    private static List<SolidVisualMesh.Face> tube(List<SolidVisualMesh.Node> nodes, SolidVisualMesh.Point reference,
                                                   int sides, int budget, boolean closed) {
        builds++;
        return SolidVisualMesh.tube(nodes, reference, sides, budget, closed);
    }

    private static List<SolidVisualMesh.Node> ring(int count, double phase, boolean tilted) {
        List<SolidVisualMesh.Node> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double angle = Math.PI * 2 * i / count + phase;
            result.add(node(point(Math.cos(angle), 1 + (tilted ? Math.sin(angle + phase * 0.60) * 0.48 : 0), Math.sin(angle)),
                0.16, 0.12, color(i)));
        }
        return result;
    }

    private static void verifyTube(List<SolidVisualMesh.Face> faces, int sides, int budget, boolean closed, boolean area) {
        require(faces.size() <= Math.max(0, Math.min(768, budget)), "output budget respected");
        require(faces.size() % sides == 0, "no partial cross-section");
        if (faces.isEmpty()) return;
        int segments = faces.size() / sides;
        require(!closed || segments >= 3, "closed model has at least three sections");
        close(0, faces.getFirst().uStart(), "U begins at zero");
        close(1, faces.getLast().uEnd(), "whole path reaches U one");
        for (int i = 0; i < segments; i++) for (int side = 0; side < sides; side++) {
            var face = faces.get(i * sides + side);
            for (var p : corners(face)) finite(p);
            require(face.uStart() >= 0 && face.uEnd() <= 1 && face.uEnd() > face.uStart(), "finite increasing UV interval");
            var adjacent = faces.get(i * sides + (side + 1) % sides);
            require(face.d() == adjacent.a() && face.c() == adjacent.b(), "exact cross-section shared edge");
            if (i + 1 < segments || closed) {
                var next = faces.get(((i + 1) % segments) * sides + side);
                require(face.b() == next.a() && face.c() == next.d(), "exact path/closing seam shared edge");
                require(face.endColor() == next.color(), "color continuity including closing seam");
                if (i + 1 < segments) close(face.uEnd(), next.uStart(), "UV continuity");
            }
            if (area) {
                require(lengthSquared(cross(subtract(face.b(), face.a()), subtract(face.c(), face.a()))) > 1.0E-18,
                    "first triangle has solid area");
                require(lengthSquared(cross(subtract(face.c(), face.a()), subtract(face.d(), face.a()))) > 1.0E-18,
                    "second triangle has solid area");
            }
        }
    }

    private static SolidVisualMesh.Point[] corners(SolidVisualMesh.Face face) { return new SolidVisualMesh.Point[]{face.a(), face.b(), face.c(), face.d()}; }
    private static SolidVisualMesh.Point point(double x, double y, double z) { return new SolidVisualMesh.Point(x, y, z); }
    private static SolidVisualMesh.Node node(SolidVisualMesh.Point p) { return node(p, 0.16, 0.12, 0xFF4080C0); }
    private static SolidVisualMesh.Node node(SolidVisualMesh.Point p, double w, double d, int c) { return new SolidVisualMesh.Node(p, w, d, c); }
    private static int color(int index) { return 0xC0000000 | ((index * 41) & 255) << 16 | ((index * 67) & 255) << 8 | ((index * 97) & 255); }
    private static SolidVisualMesh.Point subtract(SolidVisualMesh.Point a, SolidVisualMesh.Point b) { return point(a.x() - b.x(), a.y() - b.y(), a.z() - b.z()); }
    private static SolidVisualMesh.Point cross(SolidVisualMesh.Point a, SolidVisualMesh.Point b) { return point(a.y()*b.z()-a.z()*b.y(), a.z()*b.x()-a.x()*b.z(), a.x()*b.y()-a.y()*b.x()); }
    private static double dot(SolidVisualMesh.Point a, SolidVisualMesh.Point b) { return a.x()*b.x()+a.y()*b.y()+a.z()*b.z(); }
    private static double lengthSquared(SolidVisualMesh.Point p) { return dot(p, p); }
    private static double distance(SolidVisualMesh.Point a, SolidVisualMesh.Point b) { return Math.sqrt(lengthSquared(subtract(a, b))); }
    private static void finite(SolidVisualMesh.Point p) { require(Double.isFinite(p.x()) && Double.isFinite(p.y()) && Double.isFinite(p.z()), "finite geometry"); }
    private static void close(double expected, double actual, String reason) { require(Double.isFinite(actual) && Math.abs(expected - actual) < 1.0E-7, reason + " expected=" + expected + " actual=" + actual); }
    private static void require(boolean condition, String reason) { assertions++; if (!condition) throw new AssertionError(reason); }
}
