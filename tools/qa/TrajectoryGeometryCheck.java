package dev.c4lam.celeste.module.impl.visual.expansion;

import net.minecraft.util.math.Vec3d;
import java.util.ArrayList;
import java.util.List;

/** Production ribbon/presentation code with value-only adapters; not live Minecraft integration. */
public final class TrajectoryGeometryCheck {
    private static int checks;
    public static void main(String[] args) {
        for (int occupied : new int[] {0, 480, 575, 576, 768, 800}) {
            for (int ceiling : new int[] {0, 1, 96, 192, 576, 768}) {
                for (boolean marker : new boolean[] {false, true}) {
                    for (double glow : new double[] {0, .55, 1}) {
                        var budget = TrajectoryAppearance.budget(occupied, ceiling, marker, glow);
                        int room = Math.max(0, ceiling - occupied);
                        check(budget.pathSegments() >= 0 && budget.pathSegments() <= 128, "bounded ribbon segments");
                        check(budget.markerQuads() >= 0 && budget.markerQuads() <= (marker ? 2 : 0), "actual marker cost");
                        check(budget.glowSprites() >= 0 && budget.glowSprites() <= 64, "bounded stamp budget");
                        check(glow > .01 || budget.glowSprites() == 0, "Glow0 reserves no image stamps");
                        check(budget.pathSegments() + budget.glowSprites() + budget.markerQuads() <= room, "shared ceiling respected");
                        check(budget.absoluteLimit() == occupied + budget.pathSegments() + budget.glowSprites() + budget.markerQuads(), "absolute budget conversion");
                    }
                }
            }
        }
        var saturated = TrajectoryAppearance.budget(480, 576, true, .55);
        check(TrajectoryAppearance.budget(480, Integer.MIN_VALUE, true, 1).absoluteLimit() == 480,
            "negative extreme budget cannot overflow into available room");
        check(saturated.pathSegments() >= 24 && saturated.glowSprites() >= 32, "saturated scene keeps smooth core plus image halo");
        check(TrajectoryAppearance.budget(0, 192, true, 0).markerQuads() == 1, "Glow0 keeps marker core");
        check(TrajectoryAppearance.budget(0, 192, false, 1).markerQuads() == 0, "Landing off/no hit reserves nothing");
        check(!TrajectoryAppearance.enoughDetail(81, 1) && !TrajectoryAppearance.enoughDetail(81, 23), "starved arc keeps exact line fallback instead of cutting corners");
        check(TrajectoryAppearance.enoughDetail(81, 94) && TrajectoryAppearance.enoughDetail(2, 1), "normal and genuinely short path use shader");
        check(TrajectoryAppearance.sample(.1, 0, .9, true).alpha() == 0, "near-eye fade hides huge near-plane strip");
        check(TrajectoryAppearance.markerAlpha(.1, .9) == 0, "near-eye marker fades");
        check(TrajectoryAppearance.sample(8, 1, .9, false).alpha() == .9, "fade disabled keeps opacity");
        double last = 2;
        for (int i = 0; i <= 160; i++) {
            var sample = TrajectoryAppearance.sample(12, i / 160D, .9, true);
            check(sample.alpha() <= last && sample.alpha() >= .9 * .55 - 1e-12, "smooth monotonic tail without hard banding");
            check(sample.width() > 0 && sample.width() <= .65, "bounded nonzero width");
            last = sample.alpha();
        }
        check(TrajectoryAppearance.sample(Double.NaN, 0, .9, true).alpha() == 0, "invalid presentation input rejected");
        var curve = new ArrayList<ConnectedRibbon.Node>();
        for (int i = 0; i <= 80; i++) curve.add(new ConnectedRibbon.Node(new Vec3d(i * .12, 1 + i * .04 - i * i * .0006, 0),
            .09 + i * .001, 0xDDE879D9 - i * 0x10000));
        List<ConnectedRibbon.Node> original = List.copyOf(curve);
        for (Vec3d camera : List.of(new Vec3d(0, 2, 4), new Vec3d(0, 1, 0), new Vec3d(0, -8, 0))) {
            for (int budget : new int[] {1, 32, 94, 128, 192}) {
                var output = new ArrayList<WorldGlowRenderer.Quad>();
                int written = ConnectedRibbon.append(output, curve, camera, null, budget, false, WorldGlowRenderer.Texture.TRAJECTORY, .55F);
                check(written > 0 && written <= Math.min(128, budget) && written == output.size(), "complete bounded path");
                equal(mid(output.getFirst().a(), output.getFirst().d()), curve.getFirst().position(), "start stays exact");
                equal(mid(output.getLast().b(), output.getLast().c()), curve.getLast().position(), "impact endpoint stays exact");
                for (int i = 0; i < output.size(); i++) {
                    var quad = output.get(i);
                    check(quad.texture() == WorldGlowRenderer.Texture.TRAJECTORY && quad.uStart() == .55F && quad.uEnd() == .55F, "dedicated material uses constant Glow");
                    check(finite(quad.a()) && finite(quad.b()) && finite(quad.c()) && finite(quad.d()), "finite ribbon cross-sections");
                    if (i > 0) {
                        var before = output.get(i - 1);
                        equal(before.b(), quad.a(), "left edge shares exact joint");
                        equal(before.c(), quad.d(), "right edge shares exact joint");
                        check(before.endColor() == quad.color(), "continuous tint across joints");
                    }
                }
            }
        }
        check(curve.equals(original), "renderer never edits source prediction points");
        var legacy = new ArrayList<WorldGlowRenderer.Quad>();
        ConnectedRibbon.append(legacy, curve, new Vec3d(0, 0, 3), null, 94, false);
        check(legacy.getFirst().texture() == WorldGlowRenderer.Texture.RIBBON && legacy.getFirst().uStart() == 0
            && legacy.getLast().uEnd() == 1, "legacy other-module ribbon material and longitudinal UV unchanged");
        var vertical = List.of(new ConnectedRibbon.Node(new Vec3d(0, 0, 0), .1, -1),
            new ConnectedRibbon.Node(new Vec3d(0, 4, 0), .1, -1));
        var output = new ArrayList<WorldGlowRenderer.Quad>();
        check(ConnectedRibbon.append(output, vertical, new Vec3d(0, 8, 0), null, 16, false,
            WorldGlowRenderer.Texture.TRAJECTORY, 0) > 0, "vertical camera-parallel path and Glow0 retain geometry");
        check(ConnectedRibbon.append(new ArrayList<>(), vertical, new Vec3d(0, 0, 2), null, 0, false) == 0, "zero budget requires line fallback");
        check(ConnectedRibbon.append(new ArrayList<>(), List.of(vertical.getFirst(), vertical.getFirst()), new Vec3d(0, 0, 2), null, 20, false) == 0,
            "degenerate path safely rejected");
        glowTrail();
        timedGlow();
        System.out.println("PASS TrajectoryGeometry: " + checks + " assertions; production presentation/ribbon with value-only adapters, not a game test.");
    }

    private static void timedGlow() {
        var points = List.of(new Vec3d(0, 1, 0), new Vec3d(4, 1, 0));
        var camera = new Vec3d(2, 2, 4);
        var output = new ArrayList<WorldGlowRenderer.Quad>();
        check(GlowTrail.append(output, points, camera, 12, 1, .9, false, t -> 0xFF4FA3,
            t -> 0, 1) == 0, "Expired player trail writes no sprites");
        check(GlowTrail.append(output, points, camera, 12, 1, .9, false, t -> 0xFF4FA3,
            t -> Double.NaN, 1) == 0, "Invalid lifetime envelope rejected");
        int count = GlowTrail.append(output, points, camera, 12, 1, .9, false, t -> 0xFF4FA3,
            t -> t, 1);
        check(count > 0 && count <= 12, "Player trail shares bounded glow geometry");
        check((output.getFirst().color() >>> 24) < (output.getLast().color() >>> 24), "Tail lifetime did not fade along the mesh");
        check(GlowTrail.append(new ArrayList<>(), points, camera, 12, 1, .9, false, t -> -1,
            t -> 1, Double.NaN) == 0, "Invalid player trail size rejected");
    }

    private static void glowTrail() {
        var points = new ArrayList<Vec3d>();
        for (int i = 0; i <= 80; i++) points.add(new Vec3d(i * .15, 1 + i * .05 - i * i * .0008, 0));
        var original = List.copyOf(points);
        for (Vec3d camera : List.of(new Vec3d(0, 2, 5), new Vec3d(0, 1, 0), new Vec3d(0, -4, 0))) {
            for (int capacity : new int[] {0, 1, 16, 32, 64, 1000}) {
                var quads = new ArrayList<WorldGlowRenderer.Quad>();
                int count = GlowTrail.append(quads, points, camera, capacity, .55, .9, true, p -> 0xF35CAD);
                check(count == quads.size() && count <= Math.min(64, capacity), "bounded emitted sprite count");
                if (capacity >= 16) check(count > 0, "visible camera configuration emits glow");
                for (var q : quads) {
                    check(q.texture() == WorldGlowRenderer.Texture.SOFT && q.uStart() == 0 && q.uEnd() == 1,
                        "cached soft image uses complete UVs");
                    check(finite(q.a()) && finite(q.b()) && finite(q.c()) && finite(q.d()), "finite image vertices");
                    Vec3d center = mid(q.a(), q.c());
                    check(onPath(center, points), "stamp centered on unchanged predicted polyline");
                    Vec3d along = q.b().subtract(q.a()), across = q.d().subtract(q.a());
                    Vec3d facing = camera.subtract(center).normalize();
                    check(Math.abs(along.dotProduct(facing)) < 1e-9 && Math.abs(across.dotProduct(facing)) < 1e-9,
                        "image always faces camera without view-plane drift");
                    check(along.lengthSquared() <= 576.000001 && across.lengthSquared() <= 3.240001,
                        "image fill-rate bounds");
                    check(q.color() == q.endColor() && (q.color() >>> 24) > 0 && (q.color() >>> 24) <= 127,
                        "per-stamp light is bounded and normalized");
                }
            }
        }
        check(points.equals(original), "stamps never mutate simulation");
        var empty = new ArrayList<WorldGlowRenderer.Quad>();
        Vec3d camera = new Vec3d(0, 2, 5);
        check(GlowTrail.append(empty, points, camera, 64, 0, 1, false, p -> -1) == 0, "Glow0 removes stamp work");
        check(GlowTrail.append(empty, points, camera, 64, 1, 0, false, p -> -1) == 0, "Opacity0 removes stamp work");
        check(GlowTrail.append(null, points, camera, 64, 1, 1, false, p -> -1) == 0, "null batch safely ignored");
        check(GlowTrail.append(empty, List.of(new Vec3d(0, 0, 0), new Vec3d(100, 0, 0)), camera, 64, 1, 1, false, p -> -1) == 0,
            "never bridge an invalid path discontinuity");
        check(GlowTrail.append(empty, List.of(new Vec3d(0, 0, 0), new Vec3d(Double.NaN, 0, 0)), camera, 64, 1, 1, false, p -> -1) == 0,
            "invalid point rejects complete batch");
        check(GlowTrail.append(empty, List.of(camera, camera), camera, 64, 1, 1, false, p -> -1) == 0,
            "zero-length path emits nothing");
        var straight = List.of(new Vec3d(0, 0, 0), new Vec3d(0, 4, 0));
        check(GlowTrail.append(empty, straight, new Vec3d(0, 8, 0), 64, 1, 1, false, p -> -1) == 0,
            "head-on coincident images cannot create bright white blob");
        var repeated = new ArrayList<>(points);
        repeated.add(20, repeated.get(20));
        check(GlowTrail.append(empty, repeated, camera, 64, 1, 1, false, p -> -1) > 0,
            "duplicate sample points remain safe");
        var longPath = new ArrayList<Vec3d>();
        for (int i = 0; i <= 48; i++) longPath.add(new Vec3d(i * 4 - 96, 0, 0));
        int occupied = empty.size();
        check(GlowTrail.append(empty, longPath, new Vec3d(0, 0, 128), 32, 1, 1, false, p -> -1) == 0
            && empty.size() == occupied, "undersampled long halo is rejected atomically, preserving existing effects");
        check(GlowTrail.append(empty, longPath, new Vec3d(0, 0, 128), 64, 1, 1, false, p -> -1) == 64,
            "normal stamp budget retains smooth long-range halo");
    }

    private static boolean onPath(Vec3d p, List<Vec3d> points) {
        for (int i = 1; i < points.size(); i++) {
            Vec3d a = points.get(i - 1), delta = points.get(i).subtract(a);
            double t = Math.max(0, Math.min(1, p.subtract(a).dotProduct(delta) / delta.lengthSquared()));
            if (p.squaredDistanceTo(a.add(delta.multiply(t))) < 1e-18) return true;
        }
        return false;
    }
    private static Vec3d mid(Vec3d a, Vec3d b) { return a.lerp(b, .5); }
    private static boolean finite(Vec3d v) { return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z); }
    private static void equal(Vec3d a, Vec3d b, String label) { check(a.squaredDistanceTo(b) < 1e-20, label); }
    private static void check(boolean okay, String label) { checks++; if (!okay) throw new AssertionError(label); }
}
