package dev.c4lam.celeste.module.impl.visual.expansion;

import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleToIntFunction;
import java.util.function.DoubleUnaryOperator;

/** Repeated cached soft-image stamps; no particles, uploads, clock or projectile state. */
public final class GlowTrail {
    public static final int MAX_SPRITES = 64;
    private GlowTrail() { }

    public static int append(List<WorldGlowRenderer.Quad> output, List<Vec3d> points, Vec3d camera,
                             int maxSprites, double glow, double opacity, boolean fade,
                             DoubleToIntFunction palette) {
        return append(output, points, camera, maxSprites, glow, opacity, fade, palette, progress -> 1, 1);
    }

    /** The same bloom material with a caller-supplied lifetime envelope and width. */
    public static int append(List<WorldGlowRenderer.Quad> output, List<Vec3d> points, Vec3d camera,
                             int maxSprites, double glow, double opacity, boolean fade,
                             DoubleToIntFunction palette, DoubleUnaryOperator envelope, double sizeScale) {
        if (output == null || points == null || points.size() < 2 || points.size() > 512
            || !finite(camera) || palette == null || envelope == null || maxSprites <= 0
            || !Double.isFinite(sizeScale) || sizeScale <= 0 || sizeScale > 3
            || !Double.isFinite(glow) || !Double.isFinite(opacity) || glow <= .01 || opacity <= 0) return 0;
        double[] distance = new double[points.size()];
        if (!finite(points.getFirst())) return 0;
        for (int i = 1; i < points.size(); i++) {
            if (!finite(points.get(i))) return 0;
            double span = points.get(i - 1).distanceTo(points.get(i));
            if (span > 8) return 0; // Never bridge a teleport or malformed trail.
            distance[i] = distance[i - 1] + span;
        }
        double length = distance[distance.length - 1];
        if (length < 1e-6) return 0;
        int count = Math.min(Math.min(MAX_SPRITES, maxSprites), Math.max(1, (int) Math.ceil(length / .12)));
        double step = length / count;
        List<WorldGlowRenderer.Quad> sprites = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double progress = (i + .5) / count;
            Vec3d center = point(points, distance, progress * length);
            Vec3d delta = point(points, distance, (i + 1D) * step)
                .subtract(point(points, distance, i * step));
            Vec3d facing = camera.subtract(center);
            double cameraDistance = Math.sqrt(facing.lengthSquared());
            TrajectoryAppearance.Sample style = TrajectoryAppearance.sample(cameraDistance, progress, opacity, fade);
            if (style.alpha() <= .002 || cameraDistance < 1e-5) continue;
            facing = facing.multiply(1 / cameraDistance);
            Vec3d tangent = delta.subtract(facing.multiply(delta.dotProduct(facing)));
            double projectedStep = Math.sqrt(tangent.lengthSquared());
            // Edge-on samples contribute proportionately less light, so dozens of coincident
            // billboards cannot turn into a white ball when looking straight down the path.
            if (projectedStep < 1e-5) continue;
            tangent = tangent.multiply(1 / projectedStep);
            double halfWidth = clamp(.18 + cameraDistance * .01, .18, .9) * sizeScale;
            // This image's measured overlap is smooth at three sample spacings. Clamping
            // an undersampled long path would turn it into beads: preserve its core instead.
            double halfLength = Math.max(halfWidth, projectedStep * 3);
            if (halfLength > 12) return 0;
            double lifetime = envelope.applyAsDouble(progress);
            if (!Double.isFinite(lifetime)) continue;
            double alpha = style.alpha() * clamp(glow, 0, 1) * clamp(lifetime, 0, 1)
                * Math.min(1, projectedStep / (halfLength * .85));
            if (alpha < 1 / 255D) continue;
            Vec3d along = tangent.multiply(halfLength);
            Vec3d across = facing.crossProduct(tangent).normalize().multiply(halfWidth);
            int color = ((int) Math.round(clamp(alpha, 0, 1) * 255) << 24) | (palette.applyAsInt(progress) & 0xFFFFFF);
            sprites.add(new WorldGlowRenderer.Quad(WorldGlowRenderer.Texture.SOFT,
                center.subtract(along).subtract(across), center.add(along).subtract(across),
                center.add(along).add(across), center.subtract(along).add(across), color, color, 0, 1));
        }
        output.addAll(sprites);
        return sprites.size();
    }

    private static Vec3d point(List<Vec3d> points, double[] distance, double at) {
        int low = 1, high = distance.length - 1;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (distance[middle] < at) low = middle + 1; else high = middle;
        }
        double span = distance[low] - distance[low - 1];
        return span < 1e-9 ? points.get(low) : points.get(low - 1).lerp(points.get(low),
            clamp((at - distance[low - 1]) / span, 0, 1));
    }

    private static boolean finite(Vec3d v) {
        return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }
    private static double clamp(double value, double low, double high) { return Math.max(low, Math.min(high, value)); }
}
