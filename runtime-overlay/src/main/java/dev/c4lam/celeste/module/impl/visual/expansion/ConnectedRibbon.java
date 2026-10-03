package dev.c4lam.celeste.module.impl.visual.expansion;

import net.minecraft.util.math.Vec3d;
import java.util.ArrayList;
import java.util.List;

/** A single continuous strip: shared edge positions, shared colors and distance-based UVs. */
public final class ConnectedRibbon {
    private static final int MAX_INPUT_POINTS = 512;
    private static final int MAX_SEGMENTS = 128;
    private static final double EPSILON = 1.0E-8D;
    public record Node(Vec3d position, double width, int color) { }

    private ConnectedRibbon() { }

    /** width is full world-space width. Non-null fixedAxis bypasses camera facing. */
    public static int append(List<WorldGlowRenderer.Quad> output, List<Node> input,
                             Vec3d camera, Vec3d fixedAxis, int maxSegments, boolean closed) {
        return append(output, input, camera, fixedAxis, maxSegments, closed, WorldGlowRenderer.Texture.RIBBON, Float.NaN);
    }

    /** A fixed U can carry a material parameter for strips with no longitudinal texture pattern. */
    public static int append(List<WorldGlowRenderer.Quad> output, List<Node> input,
                             Vec3d camera, Vec3d fixedAxis, int maxSegments, boolean closed,
                             WorldGlowRenderer.Texture material, float fixedU) {
        if (output == null || input == null || input.size() < 2 || input.size() > MAX_INPUT_POINTS
            || material == null || Float.isInfinite(fixedU)
            || maxSegments < 1 || (fixedAxis == null && !finite(camera))) return 0;
        int budget = Math.min(MAX_SEGMENTS, maxSegments);
        Vec3d axis = fixedAxis == null ? null : unit(fixedAxis);
        if (fixedAxis != null && axis == null) return 0;
        List<Node> nodes = new ArrayList<>(input.size() + 1);
        for (Node node : input) {
            if (node == null || !finite(node.position()) || !Double.isFinite(node.width())
                || node.width() < 0D || node.width() > 8D) return 0;
            if (!nodes.isEmpty()) {
                double distance = nodes.getLast().position().squaredDistanceTo(node.position());
                // The caller must split discontinuities, never draw across a teleport.
                if (distance > 64D) return 0;
                if (distance < EPSILON) continue;
            }
            nodes.add(node);
        }
        if (nodes.size() < 2) return 0;
        if (closed) {
            Node first = nodes.getFirst();
            if (nodes.getLast().position().squaredDistanceTo(first.position()) < EPSILON) {
                nodes.set(nodes.size() - 1, first);
            } else {
                if (nodes.getLast().position().squaredDistanceTo(first.position()) > 64D) return 0;
                nodes.add(first);
            }
        }
        double[] lengths = new double[nodes.size()];
        for (int i = 1; i < nodes.size(); i++) {
            lengths[i] = lengths[i - 1] + nodes.get(i - 1).position().distanceTo(nodes.get(i).position());
        }
        double total = lengths[lengths.length - 1];
        if (!Double.isFinite(total) || total < .0001D) return 0;

        // Keep every original corner if possible. Add extra cross-sections by
        // longest-edge subdivision, rather than cutting across those corners.
        List<Double> distances = new ArrayList<>(budget + 1);
        if (nodes.size() - 1 <= budget) {
            for (double length : lengths) distances.add(length);
            while (distances.size() <= budget) {
                int longest = 0;
                double gap = 0D;
                for (int i = 0; i < distances.size() - 1; i++) {
                    double size = distances.get(i + 1) - distances.get(i);
                    if (size > gap) { gap = size; longest = i; }
                }
                if (gap < .025D) break;
                distances.add(longest + 1, (distances.get(longest) + distances.get(longest + 1)) * .5D);
            }
        } else {
            for (int i = 0; i <= budget; i++) distances.add(total * i / budget);
        }
        int count = distances.size();
        Node[] sampled = new Node[count];
        int edge = 0;
        for (int i = 0; i < count; i++) {
            double at = distances.get(i);
            while (edge + 1 < lengths.length - 1 && at > lengths[edge + 1]) edge++;
            double t = Math.max(0D, Math.min(1D, (at - lengths[edge]) / (lengths[edge + 1] - lengths[edge])));
            Node a = nodes.get(edge), b = nodes.get(edge + 1);
            sampled[i] = new Node(a.position().lerp(b.position(), t),
                a.width() + (b.width() - a.width()) * t, mix(a.color(), b.color(), t));
        }
        if (closed) sampled[count - 1] = sampled[0];
        Vec3d[] left = new Vec3d[count], right = new Vec3d[count];
        Vec3d previousSide = null;
        for (int i = 0; i < count; i++) {
            Vec3d side = axis;
            if (side == null) {
                int previous = i == 0 ? (closed ? count - 2 : 0) : i - 1;
                int next = i == count - 1 ? (closed ? 1 : i) : i + 1;
                Vec3d tangent = sampled[next].position().subtract(sampled[previous].position());
                if (tangent.lengthSquared() < EPSILON) {
                    tangent = sampled[Math.min(count - 1, i + 1)].position().subtract(sampled[Math.max(0, i - 1)].position());
                }
                side = unit(tangent.crossProduct(camera.subtract(sampled[i].position())));
                if (side == null && previousSide != null) side = previousSide;
                if (side == null) side = unit(tangent.crossProduct(new Vec3d(0D, 1D, 0D)));
                if (side == null) side = unit(tangent.crossProduct(new Vec3d(1D, 0D, 0D)));
                if (side == null) side = new Vec3d(0D, 0D, 1D);
                if (previousSide != null && side.dotProduct(previousSide) < 0D) side = side.multiply(-1D);
            }
            previousSide = side;
            Vec3d half = side.multiply(sampled[i].width() * .5D);
            left[i] = sampled[i].position().subtract(half);
            right[i] = sampled[i].position().add(half);
        }
        if (closed) {
            left[count - 1] = left[0];
            right[count - 1] = right[0];
        }
        int written = 0;
        for (int i = 0; i < count - 1; i++) {
            if (sampled[i].position().squaredDistanceTo(sampled[i + 1].position()) < EPSILON) continue;
            output.add(new WorldGlowRenderer.Quad(material,
                left[i], left[i + 1], right[i + 1], right[i], sampled[i].color(), sampled[i + 1].color(),
                Float.isNaN(fixedU) ? (float) (distances.get(i) / total) : fixedU,
                Float.isNaN(fixedU) ? (float) (distances.get(i + 1) / total) : fixedU));
            written++;
        }
        return written;
    }

    private static Vec3d unit(Vec3d vector) {
        return !finite(vector) || vector.lengthSquared() < EPSILON ? null : vector.normalize();
    }
    private static boolean finite(Vec3d vector) {
        return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
    private static int mix(int a, int b, double t) {
        int result = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int channel = (int) Math.round((a >>> shift & 255) * (1D - t) + (b >>> shift & 255) * t);
            result |= channel << shift;
        }
        return result;
    }
}
