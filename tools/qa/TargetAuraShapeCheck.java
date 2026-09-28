package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.ArrayList;
import java.util.List;

/** Focused checks of the exact production body-fit paths; no Minecraft dependency. */
public final class TargetAuraShapeCheck {
    private static int checks;

    public static void main(String[] args) {
        near(TargetAuraShape.bodyYawRadians(179, -179, 0.5), Math.PI, "forward yaw wrap");
        near(TargetAuraShape.bodyYawRadians(-179, 179, 0.5), -Math.PI, "reverse yaw wrap");
        near(TargetAuraShape.bodyYawRadians(10, 50, 0.25), Math.toRadians(20), "fractional body yaw");
        near(TargetAuraShape.bodyYawRadians(10, 50, -1), Math.toRadians(10), "tick lower bound");
        near(TargetAuraShape.bodyYawRadians(10, 50, 2), Math.toRadians(50), "tick upper bound");
        near(TargetAuraShape.bodyYawRadians(Double.NaN, 50, 0.5), 0, "invalid yaw fallback");

        for (String style : List.of("Orbit", "Helix", "Scan")) {
            for (int layer = 0; layer < 2; layer++) {
                for (double height : new double[]{0.6, 1.5, 1.8, 3.2}) {
                    for (double phase : new double[]{0, 0.4, 1.7, 5.2, 32.0}) {
                        checkShape(style, layer, height, phase);
                    }
                }
            }
        }
        checkGenericCoordinates();
        checkHelixRevolution();
        checkInvalid();
        System.out.println("PASS TargetAuraShape: " + checks + " checks; continuous helix revolution, fixed point counts, unchanged spans, body yaw, pose sizes and bounded mesh budgets");
    }

    private static void checkShape(String style, int layer, double height, double phase) {
        double radius = 0.672;
        List<TargetAuraShape.Point> points = TargetAuraShape.path(style, layer, radius, height, phase, 0, true);
        List<TargetAuraShape.Point> rotated = TargetAuraShape.path(style, layer, radius, height, phase, Math.PI / 2, true);
        List<TargetAuraShape.Point> next = TargetAuraShape.path(style, layer, radius, height, phase + 0.03, 0, true);
        int expected = style.equals("Helix") ? 21 : layer == 0 ? 24 : 16;
        require(points.size() == expected, "fixed bounded point count");
        double r = radius * (style.equals("Scan") && layer == 1 ? 1.06 : 1);
        double depth = style.equals("Orbit") && layer == 1 ? 1.04 : 1;
        List<SolidVisualMesh.Node> nodes = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            TargetAuraShape.Point p = points.get(i);
            require(Double.isFinite(p.x()) && Double.isFinite(p.y()) && Double.isFinite(p.z()), "finite coordinates");
            require(Math.abs(p.x()) <= r + 1.0E-9, "full width envelope retained");
            require(Math.abs(p.z()) <= r * depth * 0.82 + 1.0E-9, "flatter front/back envelope");
            require(p.y() >= -1.0E-9 && p.y() <= height + 1.0E-9, "pose-sized vertical envelope");
            near(rotated.get(i).x(), -p.z(), "body yaw rotates x");
            near(rotated.get(i).z(), p.x(), "body yaw rotates z");
            near(rotated.get(i).y(), p.y(), "yaw keeps height");
            require(distance(p, next.get(i)) < 0.06, "fractional animation has no jumps");
            nodes.add(new SolidVisualMesh.Node(new SolidVisualMesh.Point(p.x(), p.y(), p.z()), 0.16, 0.12, 0xCC3399FF));
        }
        if (!style.equals("Helix")) {
            near(points.get(0).x(), r, "positive side radius is not shrunk");
            near(points.get(points.size() / 2).x(), -r, "negative side radius is not shrunk");
        }
        if (style.equals("Helix")) {
            near(points.get(0).y(), 0.10, "unchanged helix base");
            near(points.get(20).y(), height - 0.10, "unchanged helix top");
        }
        boolean closed = !style.equals("Helix");
        for (int budget : new int[]{0, 17, 18, 36, 60, 84, 120, 144}) {
            List<SolidVisualMesh.Face> faces = SolidVisualMesh.tube(nodes, new SolidVisualMesh.Point(0, 1, 0), 6, budget, closed);
            require(faces.size() <= budget, "existing facet budget stays hard bounded");
            require(faces.size() % 6 == 0, "whole cross-sections only");
        }
    }

    private static void checkGenericCoordinates() {
        double phase = 1.2;
        double radius = 0.672;
        double height = 1.8;
        for (int layer = 0; layer < 2; layer++) {
            List<TargetAuraShape.Point> generic = TargetAuraShape.path("Orbit", layer, radius, height, phase, 0, false);
            for (int i = 0; i < generic.size(); i++) {
                double angle = (layer == 0 ? phase : -phase * 0.73) + Math.PI * 2 * i / generic.size();
                double lift = layer == 0 ? Math.sin(angle + phase * 0.60) * 0.29 : Math.cos(angle - phase * 0.47 + 0.65) * 0.24;
                near(generic.get(i).x(), Math.cos(angle) * radius, "generic entity radius unchanged");
                near(generic.get(i).y(), height * (0.5 + lift), "generic entity vertical span unchanged");
                near(generic.get(i).z(), Math.sin(angle) * radius * (layer == 0 ? 1 : 1.04), "generic entity depth unchanged");
            }
        }
        List<TargetAuraShape.Point> before = TargetAuraShape.path("Orbit", 0, radius, height, phase,
            TargetAuraShape.bodyYawRadians(179, -179, 0.49), true);
        List<TargetAuraShape.Point> after = TargetAuraShape.path("Orbit", 0, radius, height, phase,
            TargetAuraShape.bodyYawRadians(179, -179, 0.51), true);
        for (int i = 0; i < before.size(); i++) require(distance(before.get(i), after.get(i)) < 0.001, "wrapped turn is continuous");
    }

    private static void checkHelixRevolution() {
        double radius = 0.672;
        double height = 1.8;
        double turn = Math.PI * 2;
        for (boolean fitted : new boolean[]{false, true}) {
            List<TargetAuraShape.Point> start = TargetAuraShape.path("Helix", 0, radius, height, 0, 0, fitted);
            for (double phase : new double[]{0, Math.PI / 2, Math.PI, turn}) {
                List<TargetAuraShape.Point> first = TargetAuraShape.path("Helix", 0, radius, height, phase, 0, fitted);
                List<TargetAuraShape.Point> second = TargetAuraShape.path("Helix", 1, radius, height, phase, 0, fitted);
                near(first.get(0).x(), Math.cos(phase) * radius, "helix reaches each quarter-turn axis");
                require(Math.signum(first.get(0).z()) == Math.signum(Math.sin(phase)), "helix follows the revolution direction");
                for (int i = 0; i < first.size(); i++) {
                    near(second.get(i).x(), -first.get(i).x(), "DNA strands stay opposite in x");
                    near(second.get(i).z(), -first.get(i).z(), "DNA strands stay opposite in z");
                    near(second.get(i).y(), first.get(i).y(), "opposite strands retain height");
                    if (phase == turn) require(distance(start.get(i), first.get(i)) < 1.0E-9, "full revolution closes exactly");
                }
                for (String unchanged : List.of("Orbit", "Scan")) {
                    TargetAuraShape.Point anchor = TargetAuraShape.path(unchanged, 0, radius, height, phase, 0, true).get(0);
                    near(anchor.x(), radius, "non-helix side anchor remains body-aligned");
                    near(anchor.z(), 0, "non-helix does not inherit spinning phase");
                }
            }
            List<TargetAuraShape.Point> before = TargetAuraShape.path("Helix", 0, radius, height, turn - 0.0001, 0.73, fitted);
            List<TargetAuraShape.Point> after = TargetAuraShape.path("Helix", 0, radius, height, 0.0001, 0.73, fitted);
            for (int i = 0; i < before.size(); i++) require(distance(before.get(i), after.get(i)) < 0.0002, "phase wrap has no seam jump");
            List<TargetAuraShape.Point> frame = TargetAuraShape.path("Helix", 0, radius, height, 0.03, 0, fitted);
            require(distance(start.get(0), frame.get(0)) > 0.01, "fractional frame advances instead of nearly static sway");
            for (int i = 0; i < frame.size(); i++) require(distance(start.get(i), frame.get(i)) < 0.03, "fractional full-speed rotation stays smooth");
        }
    }

    private static void checkInvalid() {
        require(TargetAuraShape.path(null, 0, 1, 2, 0, 0, true).isEmpty(), "null style");
        require(TargetAuraShape.path("Orbit", 2, 1, 2, 0, 0, true).isEmpty(), "invalid layer");
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1, 0, Double.MAX_VALUE}) {
            require(TargetAuraShape.path("Orbit", 0, invalid, 2, 0, 0, true).isEmpty(), "invalid radius");
            require(TargetAuraShape.path("Orbit", 0, 1, invalid, 0, 0, true).isEmpty(), "invalid height");
        }
        require(TargetAuraShape.path("Orbit", 0, 1, 2, Double.NaN, 0, true).isEmpty(), "invalid phase");
        require(TargetAuraShape.path("Orbit", 0, 1, 2, 0, Double.NaN, true).isEmpty(), "invalid body yaw");
    }

    private static double distance(TargetAuraShape.Point a, TargetAuraShape.Point b) {
        return Math.sqrt(Math.pow(a.x() - b.x(), 2) + Math.pow(a.y() - b.y(), 2) + Math.pow(a.z() - b.z(), 2));
    }

    private static void near(double actual, double expected, String label) {
        require(Double.isFinite(actual) && Math.abs(actual - expected) < 1.0E-9, label);
    }

    private static void require(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
}
