package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Exact joins and stable shading normals, independent of opacity or a game runtime. */
public final class VolumeSurfaceMeshCheck {
    private static long assertions;
    private static final VolumeSurfaceMesh.Point A = point(0, 0, 0);
    private static final VolumeSurfaceMesh.Point B = point(1, 0, 0);
    private static final VolumeSurfaceMesh.Point C = point(1, 1, 0);
    private static final VolumeSurfaceMesh.Point D = point(0, 1, 0);

    public static void main(String[] args) {
        flatAndTriangles();
        for (int sides : new int[]{4, 6}) for (int segments : new int[]{6, 12, 24, 48}) {
            for (double offset : new double[]{0, 1.0E6}) curved(sides, segments, offset);
        }
        invalidAndBounds();
        System.out.println("PASS VolumeSurfaceMeshCheck: " + assertions + " assertions; shared-position normals, seams, order, degenerates and fixed budgets");
    }

    private static void flatAndTriangles() {
        var quad = new VolumeSurfaceMesh.Face(A, B, C, D);
        var first = new VolumeSurfaceMesh.Face(A, B, C, C);
        var second = new VolumeSurfaceMesh.Face(A, C, D, D);
        for (var faces : List.of(List.of(quad), List.of(first, second))) {
            var smooth = VolumeSurfaceMesh.smooth(faces);
            verify(faces, smooth);
            for (var vertex : smooth) {
                near(0, vertex.normal().x()); near(0, vertex.normal().y()); near(1, vertex.normal().z());
            }
        }
        var signedZero = new VolumeSurfaceMesh.Face(point(-0.0, 0, 0), B, C, D);
        var shared = VolumeSurfaceMesh.smooth(List.of(quad, signedZero));
        require(shared.get(0).normal() == shared.get(4).normal(), "signed zero welds to the same normal");
        var triangle = VolumeSurfaceMesh.smooth(List.of(first));
        require(triangle.get(2).normal() == triangle.get(3).normal(), "triangle-quad duplicate keeps one normal");
        var collapsed = VolumeSurfaceMesh.smooth(List.of(new VolumeSurfaceMesh.Face(A, A, A, A)));
        for (var vertex : collapsed) near(1, length(vertex.normal()));
        var opposing = VolumeSurfaceMesh.smooth(List.of(quad, new VolumeSurfaceMesh.Face(D, C, B, A)));
        for (var vertex : opposing) near(1, length(vertex.normal()));
    }

    private static void curved(int sides, int segments, double offset) {
        List<SolidVisualMesh.Node> nodes = new ArrayList<>();
        for (int i = 0; i < segments; i++) {
            double angle = i * Math.PI * 2 / segments;
            nodes.add(new SolidVisualMesh.Node(new SolidVisualMesh.Point(offset + Math.cos(angle),
                .5 + Math.sin(angle * 2) * .19, offset + Math.sin(angle)), .16, .12, 0xCC50C0FF));
        }
        List<VolumeSurfaceMesh.Face> faces = new ArrayList<>();
        for (var face : SolidVisualMesh.tube(nodes, new SolidVisualMesh.Point(0, 1, 0), sides, 768, true)) {
            faces.add(new VolumeSurfaceMesh.Face(point(face.a()), point(face.b()), point(face.c()), point(face.d())));
        }
        var vertices = VolumeSurfaceMesh.smooth(faces);
        Map<VolumeSurfaceMesh.Point, VolumeSurfaceMesh.Point> normals = verify(faces, vertices);
        require(normals.size() == segments * sides, "one welded normal per unique tube-section point");
        List<VolumeSurfaceMesh.Face> reversed = new ArrayList<>(faces);
        Collections.reverse(reversed);
        var reversedVertices = VolumeSurfaceMesh.smooth(reversed);
        verify(reversed, reversedVertices);
        for (var vertex : reversedVertices) {
            var expected = normals.get(vertex.position());
            near(expected.x(), vertex.normal().x()); near(expected.y(), vertex.normal().y()); near(expected.z(), vertex.normal().z());
        }
        // Last section joins the first with bit-identical shading, even at tiny opacity.
        for (int side = 0; side < sides; side++) {
            var closing = vertices.get((faces.size() - sides + side) * 4 + 1);
            var opening = vertices.get(side * 4);
            require(closing.position().equals(opening.position()), "exact closed position");
            require(closing.normal() == opening.normal(), "no independent closing-seam normal");
        }
    }

    private static Map<VolumeSurfaceMesh.Point, VolumeSurfaceMesh.Point> verify(
        List<VolumeSurfaceMesh.Face> faces, List<VolumeSurfaceMesh.Vertex> vertices) {
        require(vertices.size() == faces.size() * 4, "exact quad order and vertex count");
        Map<VolumeSurfaceMesh.Point, VolumeSurfaceMesh.Point> normals = new HashMap<>();
        for (int i = 0; i < faces.size(); i++) {
            var face = faces.get(i);
            var corners = new VolumeSurfaceMesh.Point[]{face.a(), face.b(), face.c(), face.d()};
            for (int j = 0; j < 4; j++) {
                var vertex = vertices.get(i * 4 + j);
                require(vertex.position() == corners[j], "never rounds or moves source geometry");
                near(1, length(vertex.normal()));
                var previous = normals.putIfAbsent(vertex.position(), vertex.normal());
                require(previous == null || previous == vertex.normal(), "all joins reuse the identical normal");
            }
        }
        return normals;
    }

    private static void invalidAndBounds() {
        require(VolumeSurfaceMesh.smooth(null).isEmpty(), "null batch");
        require(VolumeSurfaceMesh.smooth(List.of()).isEmpty(), "empty frame");
        var face = new VolumeSurfaceMesh.Face(A, B, C, D);
        require(VolumeSurfaceMesh.smooth(Collections.nCopies(768, face)).size() == 3072, "fixed maximum vertex count");
        require(VolumeSurfaceMesh.smooth(new AbstractList<VolumeSurfaceMesh.Face>() {
            @Override public int size() { return Integer.MAX_VALUE; }
            @Override public VolumeSurfaceMesh.Face get(int index) { throw new AssertionError("Oversized input was accessed"); }
        }).isEmpty(), "oversized list rejected before work");
        require(VolumeSurfaceMesh.smooth(Collections.singletonList(null)).isEmpty(), "null face");
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1.0E10}) {
            require(VolumeSurfaceMesh.smooth(List.of(new VolumeSurfaceMesh.Face(A, B, C, point(invalid, 0, 0)))).isEmpty(), "invalid coordinates rejected");
        }
    }

    private static VolumeSurfaceMesh.Point point(SolidVisualMesh.Point p) { return point(p.x(), p.y(), p.z()); }
    private static VolumeSurfaceMesh.Point point(double x, double y, double z) { return new VolumeSurfaceMesh.Point(x, y, z); }
    private static double length(VolumeSurfaceMesh.Point p) { return Math.sqrt(p.x() * p.x() + p.y() * p.y() + p.z() * p.z()); }
    private static void near(double a, double b) { require(Double.isFinite(b) && Math.abs(a - b) < 1.0E-9, "finite unit/continuous normal: " + a + " vs " + b); }
    private static void require(boolean ok, String reason) { assertions++; if (!ok) throw new AssertionError(reason); }
}
