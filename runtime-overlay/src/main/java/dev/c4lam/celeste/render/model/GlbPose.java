package dev.c4lam.celeste.render.model;

import java.util.Arrays;
import java.util.Objects;

/**
 * Reusable glTF pose and optional CPU skinning in right-handed model space.
 * Returned matrices are read-only views, valid until the next sample. This
 * object and caller-provided output buffers are intended for one render thread.
 */
public final class GlbPose {
    private final GlbModel model;
    private final int[] parents;
    private final float[] translations, rotations, scales;
    private final float[][] local, world, palettes;
    private final float[] blended = new float[16];

    /** Expects the importer's validated forest and all-node, parent-first order. */
    public GlbPose(GlbModel model) {
        this.model = Objects.requireNonNull(model, "model");
        int count = model.nodes().size();
        parents = new int[count]; Arrays.fill(parents, -1);
        translations = new float[Math.multiplyExact(count, 3)];
        rotations = new float[Math.multiplyExact(count, 4)];
        scales = new float[Math.multiplyExact(count, 3)];
        local = new float[count][16]; world = new float[count][16];
        for (int parent = 0; parent < count; parent++) {
            GlbModel.Node node = model.nodes().get(parent);
            for (int child : node.children()) {
                if (child < 0 || child >= count || child == parent || parents[child] != -1) throw invalid("Invalid node forest");
                parents[child] = parent;
            }
            if (node.matrix() == null) {
                vector(node.translation(), 3); vector(node.rotation(), 4); vector(node.scale(), 3);
            } else vector(node.matrix(), 16);
        }
        boolean[] visited = new boolean[count];
        if (model.topologicalOrder().length != count) throw invalid("Topological order must include every node");
        for (int node : model.topologicalOrder()) {
            if (node < 0 || node >= count || visited[node] || parents[node] >= 0 && !visited[parents[node]]) throw invalid("Invalid parent-first node order");
            visited[node] = true;
        }
        palettes = new float[model.skins().size()][];
        for (int index = 0; index < palettes.length; index++) {
            GlbModel.Skin skin = model.skins().get(index);
            int size = Math.multiplyExact(skin.joints().length, 16);
            vector(skin.inverseBindMatrices(), size);
            for (int joint : skin.joints()) if (joint < 0 || joint >= count) throw invalid("Invalid skin joint node");
            palettes[index] = new float[size];
        }
        sample(-1, 0, false);
    }

    /** -1 resets to the bind pose. Looping also supports negative finite time. */
    public GlbPose sample(int clipIndex, double seconds, boolean loop) {
        if (!Double.isFinite(seconds) || clipIndex < -1 || clipIndex >= model.animations().size()) throw invalid("Invalid animation clip or time");
        GlbModel.Animation animation = clipIndex < 0 ? null : model.animations().get(clipIndex);
        double time = 0;
        if (animation != null) {
            double duration = animation.duration();
            if (!Double.isFinite(duration) || duration < 0) throw invalid("Invalid animation duration");
            if (duration > 0) {
                time = loop ? seconds % duration : Math.max(0, Math.min(duration, seconds));
                if (time < 0) time += duration;
            }
        }
        for (int index = 0; index < local.length; index++) {
            GlbModel.Node node = model.nodes().get(index);
            if (node.matrix() != null) {
                System.arraycopy(node.matrix(), 0, local[index], 0, 16);
            } else {
                System.arraycopy(node.translation(), 0, translations, index * 3, 3);
                System.arraycopy(node.rotation(), 0, rotations, index * 4, 4);
                System.arraycopy(node.scale(), 0, scales, index * 3, 3);
            }
        }
        if (animation != null) {
            for (int index = 0; index < animation.channels().size(); index++) sampleChannel(animation.channels().get(index), time);
        }
        return resolveTransforms();
    }

    /** Applies at most eight post-animation local rotation offsets without mutating the model. */
    public GlbPose applyLocalRotations(int[] nodes, float[] quaternions) {
        Objects.requireNonNull(nodes, "nodes");
        if (nodes.length > 8) throw invalid("Secondary motion exceeds eight joints");
        vector(quaternions, nodes.length * 4);
        for (int i = 0; i < nodes.length; i++) {
            int node = Objects.checkIndex(nodes[i], model.nodes().size());
            if (model.nodes().get(node).matrix() != null) throw invalid("Secondary motion requires TRS joints");
            for (int j = 0; j < i; j++) if (nodes[j] == node) throw invalid("Duplicate secondary motion joint");
            double length = 0;
            for (int axis = 0; axis < 4; axis++) length += (double) quaternions[i * 4 + axis] * quaternions[i * 4 + axis];
            if (!(length > 0)) throw invalid("Invalid secondary rotation quaternion");
        }
        for (int i = 0; i < nodes.length; i++) {
            int q = nodes[i] * 4, offset = i * 4;
            double ax = rotations[q], ay = rotations[q + 1], az = rotations[q + 2], aw = rotations[q + 3];
            double bx = quaternions[offset], by = quaternions[offset + 1], bz = quaternions[offset + 2], bw = quaternions[offset + 3];
            double norm = Math.sqrt(bx * bx + by * by + bz * bz + bw * bw);
            bx /= norm; by /= norm; bz /= norm; bw /= norm;
            rotations[q] = finite(aw * bx + ax * bw + ay * bz - az * by);
            rotations[q + 1] = finite(aw * by - ax * bz + ay * bw + az * bx);
            rotations[q + 2] = finite(aw * bz + ax * by - ay * bx + az * bw);
            rotations[q + 3] = finite(aw * bw - ax * bx - ay * by - az * bz);
        }
        return resolveTransforms();
    }

    private GlbPose resolveTransforms() {
        for (int node : model.topologicalOrder()) {
            if (model.nodes().get(node).matrix() == null) trs(node, local[node]);
            if (parents[node] < 0) System.arraycopy(local[node], 0, world[node], 0, 16);
            else multiply(world[parents[node]], 0, local[node], 0, world[node], 0);
        }
        for (int index = 0; index < palettes.length; index++) {
            GlbModel.Skin skin = model.skins().get(index);
            for (int joint = 0; joint < skin.joints().length; joint++) {
                multiply(world[skin.joints()[joint]], 0, skin.inverseBindMatrices(), joint * 16, palettes[index], joint * 16);
            }
        }
        return this;
    }

    public float[] nodeMatrix(int node) { return world[Objects.checkIndex(node, world.length)]; }

    /** Model-space palettes deliberately do not apply the mesh node transform again. */
    public float[] skinMatrices(int meshNode) {
        GlbModel.Node node = model.nodes().get(Objects.checkIndex(meshNode, world.length));
        return node.skin() < 0 ? null : palettes[Objects.checkIndex(node.skin(), palettes.length)];
    }

    /** Resolves a mesh primitive by index, then uses {@link #deform}. */
    public void skin(int nodeIndex, int primitiveIndex, float[] outPositions, float[] outNormals) {
        GlbModel.Node node = model.nodes().get(Objects.checkIndex(nodeIndex, world.length));
        if (node.mesh() < 0) throw invalid("Node has no mesh");
        deform(nodeIndex, model.meshes().get(node.mesh()).primitives().get(primitiveIndex), outPositions, outNormals);
    }

    /**
     * Writes model-space vertex data into reusable buffers (at least positions.length).
     * Normals are optional; singular effective transforms or absent/zero normals
     * produce a finite zero vector. Buffers must not alias this primitive's input
     * arrays or each other. The primitive must belong to the given mesh node.
     * Geometry/index/material arrays are never modified.
     */
    public void deform(int nodeIndex, GlbModel.Primitive primitive, float[] outPositions, float[] outNormals) {
        GlbModel.Node node = model.nodes().get(Objects.checkIndex(nodeIndex, world.length));
        if (node.mesh() < 0) throw invalid("Node has no mesh");
        Objects.requireNonNull(primitive, "primitive");
        float[] positions = primitive.positions(), normals = primitive.normals();
        if (positions == null || positions.length % 3 != 0 || normals != null && normals.length != positions.length) throw invalid("Invalid primitive vertex shape");
        if (outPositions == null || outPositions.length < positions.length || outNormals != null && outNormals.length < positions.length
            || outPositions == outNormals || aliases(primitive, outPositions) || outNormals != null && aliases(primitive, outNormals)) {
            throw invalid("Invalid or aliased pose output buffers");
        }
        float[] palette = skinMatrices(nodeIndex);
        if (palette != null && (primitive.joints() == null || primitive.weights() == null
            || primitive.joints().length != primitive.vertexCount() * 4 || primitive.weights().length != primitive.vertexCount() * 4)) throw invalid("Skinned mesh lacks matching joints or weights");
        for (int vertex = 0; vertex < primitive.vertexCount(); vertex++) {
            float[] matrix = world[nodeIndex];
            if (palette != null) {
                int offset = vertex * 4;
                double total = 0;
                for (int influence = 0; influence < 4; influence++) {
                    float weight = primitive.weights()[offset + influence];
                    if (!Float.isFinite(weight) || weight < 0) throw invalid("Invalid skin weight");
                    total += weight;
                }
                if (!(total > 0)) throw invalid("Skin weights have no influence");
                for (int element = 0; element < 16; element++) {
                    double value = 0;
                    for (int influence = 0; influence < 4; influence++) {
                        float weight = primitive.weights()[offset + influence];
                        if (weight == 0) continue;
                        int joint = primitive.joints()[offset + influence];
                        if (joint < 0 || joint >= palette.length / 16) throw invalid("Invalid vertex joint index");
                        value += palette[joint * 16 + element] * (weight / total);
                    }
                    blended[element] = finite(value);
                }
                matrix = blended;
            }
            int offset = vertex * 3;
            double x = positions[offset], y = positions[offset + 1], z = positions[offset + 2];
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw invalid("Nonfinite vertex position");
            outPositions[offset] = finite(matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12]);
            outPositions[offset + 1] = finite(matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13]);
            outPositions[offset + 2] = finite(matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14]);
            if (outNormals != null) normal(matrix, normals, offset, outNormals);
        }
    }

    private void sampleChannel(GlbModel.Channel channel, double time) {
        int node = Objects.checkIndex(channel.node(), world.length);
        if (model.nodes().get(node).matrix() != null) throw invalid("Matrix node cannot have TRS animation");
        boolean quaternion = channel.target() == GlbModel.Target.ROTATION;
        int width = quaternion ? 4 : 3, offset = node * width;
        float[] destination = switch (channel.target()) {
            case TRANSLATION -> translations; case ROTATION -> rotations; case SCALE -> scales;
        };
        float[] times = channel.times(), values = channel.values();
        int last = times.length - 1;
        if (last < 0 || values.length != times.length * width) throw invalid("Invalid animation sample shape");
        if (time <= times[0] || last == 0) { key(values, 0, destination, offset, width); return; }
        if (time >= times[last]) { key(values, last * width, destination, offset, width); return; }
        int low = 0, high = last;
        while (low + 1 < high) {
            int middle = (low + high) >>> 1;
            if (times[middle] <= time) low = middle; else high = middle;
        }
        if (channel.interpolation() == GlbModel.Interpolation.STEP) {
            key(values, low * width, destination, offset, width); return;
        }
        double fraction = (time - times[low]) / ((double) times[high] - times[low]);
        if (quaternion) slerp(values, low * 4, high * 4, fraction, destination, offset);
        else for (int axis = 0; axis < 3; axis++) {
            destination[offset + axis] = finite(values[low * 3 + axis] * (1 - fraction) + values[high * 3 + axis] * fraction);
        }
    }

    private static void key(float[] from, int start, float[] to, int offset, int width) {
        if (width == 4) slerp(from, start, start, 0, to, offset);
        else for (int axis = 0; axis < 3; axis++) to[offset + axis] = finite(from[start + axis]);
    }

    private static void slerp(float[] values, int first, int second, double fraction, float[] output, int offset) {
        double ax = values[first], ay = values[first + 1], az = values[first + 2], aw = values[first + 3];
        double bx = values[second], by = values[second + 1], bz = values[second + 2], bw = values[second + 3];
        double aLength = Math.sqrt(ax * ax + ay * ay + az * az + aw * aw), bLength = Math.sqrt(bx * bx + by * by + bz * bz + bw * bw);
        if (!(aLength > 0) || !(bLength > 0) || !Double.isFinite(aLength) || !Double.isFinite(bLength)) throw invalid("Invalid rotation quaternion");
        ax /= aLength; ay /= aLength; az /= aLength; aw /= aLength;
        bx /= bLength; by /= bLength; bz /= bLength; bw /= bLength;
        double dot = ax * bx + ay * by + az * bz + aw * bw;
        if (dot < 0) { bx = -bx; by = -by; bz = -bz; bw = -bw; dot = -dot; }
        dot = Math.max(-1, Math.min(1, dot));
        double left = 1 - fraction, right = fraction;
        if (dot < 0.9995) {
            double angle = Math.acos(dot), sine = Math.sin(angle);
            left = Math.sin((1 - fraction) * angle) / sine; right = Math.sin(fraction * angle) / sine;
        }
        double x = ax * left + bx * right, y = ay * left + by * right, z = az * left + bz * right, w = aw * left + bw * right;
        double length = Math.sqrt(x * x + y * y + z * z + w * w);
        if (!(length > 0)) throw invalid("Invalid interpolated quaternion");
        output[offset] = finite(x / length); output[offset + 1] = finite(y / length);
        output[offset + 2] = finite(z / length); output[offset + 3] = finite(w / length);
    }

    private void trs(int node, float[] output) {
        int q = node * 4, v = node * 3;
        double x = rotations[q], y = rotations[q + 1], z = rotations[q + 2], w = rotations[q + 3];
        double length = Math.sqrt(x * x + y * y + z * z + w * w);
        if (!(length > 0) || !Double.isFinite(length)) throw invalid("Invalid base rotation quaternion");
        x /= length; y /= length; z /= length; w /= length;
        double sx = scales[v], sy = scales[v + 1], sz = scales[v + 2];
        output[0] = finite((1 - 2 * (y * y + z * z)) * sx); output[1] = finite(2 * (x * y + z * w) * sx); output[2] = finite(2 * (x * z - y * w) * sx); output[3] = 0;
        output[4] = finite(2 * (x * y - z * w) * sy); output[5] = finite((1 - 2 * (x * x + z * z)) * sy); output[6] = finite(2 * (y * z + x * w) * sy); output[7] = 0;
        output[8] = finite(2 * (x * z + y * w) * sz); output[9] = finite(2 * (y * z - x * w) * sz); output[10] = finite((1 - 2 * (x * x + y * y)) * sz); output[11] = 0;
        output[12] = finite(translations[v]); output[13] = finite(translations[v + 1]); output[14] = finite(translations[v + 2]); output[15] = 1;
    }

    private static void multiply(float[] left, int a, float[] right, int b, float[] output, int offset) {
        for (int column = 0; column < 4; column++) for (int row = 0; row < 4; row++) {
            double value = 0;
            for (int inner = 0; inner < 4; inner++) value += (double) left[a + inner * 4 + row] * right[b + column * 4 + inner];
            output[offset + column * 4 + row] = finite(value);
        }
    }

    private static void normal(float[] matrix, float[] source, int offset, float[] output) {
        double x = source == null ? 0 : source[offset], y = source == null ? 0 : source[offset + 1], z = source == null ? 0 : source[offset + 2];
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw invalid("Nonfinite vertex normal");
        double a = matrix[0], b = matrix[4], c = matrix[8], d = matrix[1], e = matrix[5], f = matrix[9], g = matrix[2], h = matrix[6], i = matrix[10];
        double c00 = e * i - f * h, c01 = f * g - d * i, c02 = d * h - e * g;
        double determinant = a * c00 + b * c01 + c * c02;
        double nx = c00 * x + c01 * y + c02 * z;
        double ny = (c * h - b * i) * x + (a * i - c * g) * y + (b * g - a * h) * z;
        double nz = (b * f - c * e) * x + (c * d - a * f) * y + (a * e - b * d) * z;
        double length = Math.hypot(Math.hypot(nx, ny), nz);
        if (determinant == 0 || length == 0) {
            output[offset] = output[offset + 1] = output[offset + 2] = 0; return;
        }
        // Normalization cancels |determinant|, but reflection keeps its sign.
        double factor = Math.copySign(1 / length, determinant);
        output[offset] = finite(nx * factor); output[offset + 1] = finite(ny * factor); output[offset + 2] = finite(nz * factor);
    }

    private static boolean aliases(GlbModel.Primitive primitive, float[] output) {
        return output == primitive.positions() || output == primitive.normals() || output == primitive.texCoords() || output == primitive.weights();
    }
    private static void vector(float[] values, int length) {
        if (values == null || values.length != length) throw invalid("Invalid model transform shape");
        for (float value : values) if (!Float.isFinite(value)) throw invalid("Nonfinite model transform");
    }
    private static float finite(double value) {
        if (!Double.isFinite(value) || value > Float.MAX_VALUE || value < -Float.MAX_VALUE) throw invalid("Nonfinite or overflowing pose result");
        return (float) value;
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
