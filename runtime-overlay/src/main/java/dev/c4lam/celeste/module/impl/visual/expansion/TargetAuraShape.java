package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.ArrayList;
import java.util.List;

/** Pure, bounded target paths in feet-centered coordinates, shared with visual QA. */
public final class TargetAuraShape {
    private static final double TAU = Math.PI * 2.0D;

    private TargetAuraShape() { }

    public record Point(double x, double y, double z) { }

    /** The wrapped body-yaw interpolation used by the vanilla living renderer. */
    public static double bodyYawRadians(double previousDegrees, double currentDegrees, double tickProgress) {
        if (!Double.isFinite(previousDegrees) || !Double.isFinite(currentDegrees)
            || !Double.isFinite(tickProgress)) return 0.0D;
        double delta = ((currentDegrees % 360.0D - previousDegrees % 360.0D) % 360.0D + 540.0D) % 360.0D - 180.0D;
        return Math.toRadians(previousDegrees % 360.0D + clamp(tickProgress, 0.0D, 1.0D) * delta);
    }

    /**
     * Orbit/Scan return 24 or 16 closed-path points; Helix returns 21 open-path points.
     * Player fitting changes horizontal shape only: height, radius and tube thickness
     * remain owned by their existing settings. Other poses/entities keep a generic path.
     */
    public static List<Point> path(String style, int layer, double radius, double height,
                                   double phase, double bodyYawRadians, boolean playerFit) {
        if (style == null || layer < 0 || layer > 1 || !Double.isFinite(radius) || radius <= 0.0D || radius > 64.0D
            || !Double.isFinite(height) || height <= 0.0D || height > 128.0D
            || !Double.isFinite(phase) || !Double.isFinite(bodyYawRadians)) return List.of();
        boolean helix = "Helix".equals(style);
        boolean scan = "Scan".equals(style);
        int count = helix ? 21 : layer == 0 ? 24 : 16;
        List<Point> points = new ArrayList<>(count);
        double scanY = 0.14D + (0.5D + Math.sin(phase) * 0.5D) * Math.max(0.08D, height - 0.28D);
        for (int i = 0; i < count; i++) {
            double angle;
            double y;
            double r = radius;
            double depth = 1.0D;
            if (helix) {
                double progress = i / 20.0D;
                // Spin both strands together; the body-relative profile below
                // keeps the wrap fitted while the existing Speed drives a full turn.
                angle = phase + layer * Math.PI + progress * TAU * 1.4D;
                y = 0.10D + progress * Math.max(0.08D, height - 0.20D);
            } else if (scan) {
                angle = (playerFit ? 0.0D : layer == 0 ? -phase * 0.38D : phase * 0.70D) + TAU * i / count;
                y = layer == 0 ? scanY : Math.max(0.07D, scanY - 0.21D);
                r *= layer == 0 ? 1.0D : 1.06D;
            } else {
                angle = (playerFit ? 0.0D : layer == 0 ? phase : -phase * 0.73D) + TAU * i / count;
                // A small body-relative sway keeps the wrap alive without orbiting
                // independent tilted planes around the player's silhouette.
                double tilt = playerFit
                    ? (layer == 0 ? 0.14D * Math.sin(phase * 0.28D) : -0.12D * Math.sin(phase * 0.24D))
                    : (layer == 0 ? phase * 0.60D : -phase * 0.47D);
                double lift = layer == 0 ? Math.sin(angle + tilt) * 0.29D : Math.cos(angle + tilt + 0.65D) * 0.24D;
                y = height * (0.5D + lift);
                depth = layer == 0 ? 1.0D : 1.04D;
            }
            double sine = Math.sin(angle);
            double x = Math.cos(angle) * r;
            double z = sine * r * depth;
            if (playerFit) {
                double waistDistance = (y / height - 0.50D) / 0.19D;
                double waist = Math.exp(-waistDistance * waistDistance);
                // Preserve the full sideways radius. Only the diagonal waist and
                // front/back profile tuck inward; shoulder clearance stays broad.
                x *= 1.0D - 0.075D * waist * sine * sine;
                z *= 0.82D - 0.04D * waist;
            }
            double cosineYaw = Math.cos(bodyYawRadians);
            double sineYaw = Math.sin(bodyYawRadians);
            points.add(new Point(cosineYaw * x - sineYaw * z, y, sineYaw * x + cosineYaw * z));
        }
        return List.copyOf(points);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
