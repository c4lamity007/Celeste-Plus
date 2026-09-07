package dev.c4lam.celeste.render.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Small local visual spring layer. This does not evaluate Unity/VRChat components. */
public final class ModelSecondaryMotion {
    private static final int MAX_JOINTS = 8, MAX_PLAYERS = 12;
    private static final double MIN_DT = 1e-5, MAX_DT = .25, OMEGA = 12, DAMPING = 4.2;
    private static final Pattern SIDE_FIRST = Pattern.compile("^(left|right|l|r)(breast|bust|chest)([1-4]?)$");
    private static final Pattern SIDE_LAST = Pattern.compile("^(breast|bust|chest)(left|right|l|r)([1-4]?)$");
    private final GlbModel model;
    private final int[] joints, parents;
    private final float centerX, baseY, centerZ;
    private final Map<UUID, State> players = new LinkedHashMap<>(16, .75f, true);
    private final float[] offsets;

    public ModelSecondaryMotion(GlbModel model) { this(model, 0, 0, 0); }

    public ModelSecondaryMotion(GlbModel model, float centerX, float baseY, float centerZ) {
        this.model = model;
        this.centerX = centerX; this.baseY = baseY; this.centerZ = centerZ;
        parents = new int[model.nodes().size()]; Arrays.fill(parents, -1);
        for (int i = 0; i < parents.length; i++) for (int child : model.nodes().get(i).children()) parents[child] = i;
        joints = select(model, parents);
        offsets = new float[joints.length * 4];
    }

    public boolean available() { return joints.length > 0; }
    public int jointCount() { return joints.length; }
    public int playerCount() { return players.size(); }
    public void clear() { players.clear(); }
    public void forget(UUID player) { players.remove(player); }

    /** Call once after pose.sample; the pose is scratch, while all history belongs to the UUID. */
    public void apply(GlbPose pose, Frame frame) {
        if (frame.id == null || !available()) return;
        if (!frame.enabled || !valid(frame) || frame.strength <= 0) { players.remove(frame.id); return; }
        State state = players.get(frame.id);
        if (state == null) {
            if (players.size() >= MAX_PLAYERS) players.remove(players.keySet().iterator().next());
            state = new State(joints.length);
            players.put(frame.id, state);
            reset(state, pose, frame);
            return;
        }
        double dt = frame.seconds - state.previous.seconds;
        double dx = frame.x - state.previous.x, dy = frame.y - state.previous.y, dz = frame.z - state.previous.z;
        double turn = Math.IEEEremainder(frame.yaw - state.previous.yaw, 360);
        if (dt < 0 || dt > MAX_DT || dx * dx + dy * dy + dz * dz > 9 || Math.abs(turn) > 120
            || Math.abs(frame.scale - state.previous.scale) > 1e-4) {
            reset(state, pose, frame); return;
        }
        if (dt >= MIN_DT) {
            for (int i = 0; i < joints.length; i++) {
                point(pose.nodeMatrix(joints[i]), frame, state.point);
                int v = i * 3;
                for (int axis = 0; axis < 3; axis++) {
                    double velocity = (state.point[axis] - state.anchor[v + axis]) / dt;
                    state.acceleration[axis] = state.warmed ? -(velocity - state.velocity[v + axis]) / dt : 0;
                    state.velocity[v + axis] = velocity;
                    state.anchor[v + axis] = state.point[axis];
                }
                limit(state.acceleration, 30);
                direction(pose, joints[i], frame, state.axis);
                double ax = state.axis[0], ay = state.axis[1], az = state.axis[2];
                double fx = state.acceleration[0], fy = state.acceleration[1], fz = state.acceleration[2];
                state.force[0] = ay * fz - az * fy;
                state.force[1] = az * fx - ax * fz;
                state.force[2] = ax * fy - ay * fx;
                for (int axis = 0; axis < 3; axis++) integrate(state.angle, state.angularVelocity, v + axis,
                    state.force[axis] * .9 * frame.strength, dt);
                double length = Math.hypot(Math.hypot(state.angle[v], state.angle[v + 1]), state.angle[v + 2]);
                double maximum = .35 * frame.strength;
                if (length > maximum) {
                    for (int axis = 0; axis < 3; axis++) { state.angle[v + axis] *= maximum / length; state.angularVelocity[v + axis] = 0; }
                }
            }
            state.warmed = true;
            state.previous = frame;
        }
        for (int i = 0; i < joints.length; i++) quaternion(pose.nodeMatrix(joints[i]), frame.yaw, state.angle, i * 3, offsets, i * 4);
        pose.applyLocalRotations(joints, offsets);
    }

    private void reset(State state, GlbPose pose, Frame frame) {
        Arrays.fill(state.angle, 0); Arrays.fill(state.angularVelocity, 0); Arrays.fill(state.velocity, 0);
        for (int i = 0; i < joints.length; i++) {
            point(pose.nodeMatrix(joints[i]), frame, state.point);
            System.arraycopy(state.point, 0, state.anchor, i * 3, 3);
        }
        state.previous = frame; state.warmed = false;
    }

    private void point(float[] matrix, Frame frame, double[] out) {
        double angle = Math.toRadians(-frame.yaw), c = Math.cos(angle), s = Math.sin(angle);
        double x = (matrix[12] - centerX) * frame.scale, y = (matrix[13] - baseY) * frame.scale, z = (matrix[14] - centerZ) * frame.scale;
        out[0] = frame.x + c * x + s * z; out[1] = frame.y + y; out[2] = frame.z - s * x + c * z;
    }

    private void direction(GlbPose pose, int node, Frame frame, double[] out) {
        float[] current = pose.nodeMatrix(node);
        int[] children = model.nodes().get(node).children();
        float[] endpoint = pose.nodeMatrix(children.length == 0 ? parents[node] : children[0]);
        double sign = children.length == 0 ? -1 : 1;
        double x = sign * (endpoint[12] - current[12]), y = sign * (endpoint[13] - current[13]), z = sign * (endpoint[14] - current[14]);
        double length = Math.hypot(Math.hypot(x, y), z);
        if (length < 1e-8) { x = current[4]; y = current[5]; z = current[6]; length = Math.hypot(Math.hypot(x, y), z); }
        if (length < 1e-8) { out[0] = 0; out[1] = 1; out[2] = 0; return; }
        double angle = Math.toRadians(-frame.yaw), c = Math.cos(angle), s = Math.sin(angle);
        out[0] = (c * x + s * z) / length; out[1] = y / length; out[2] = (-s * x + c * z) / length;
    }

    /** Analytic damped spring update for a constant force over this bounded interval. */
    private static void integrate(double[] angles, double[] velocities, int index, double force, double dt) {
        double frequency = Math.sqrt(OMEGA * OMEGA - DAMPING * DAMPING);
        double decay = Math.exp(-DAMPING * dt), cosine = Math.cos(frequency * dt), sine = Math.sin(frequency * dt);
        double equilibrium = force / (OMEGA * OMEGA), x = angles[index] - equilibrium, v = velocities[index];
        angles[index] = equilibrium + decay * (x * cosine + (v + DAMPING * x) / frequency * sine);
        velocities[index] = decay * (v * cosine - (DAMPING * v + OMEGA * OMEGA * x) / frequency * sine);
    }

    private static void quaternion(float[] matrix, float yaw, double[] worldAngle, int v, float[] output, int q) {
        output[q] = output[q + 1] = output[q + 2] = 0; output[q + 3] = 1;
        double length = Math.hypot(Math.hypot(worldAngle[v], worldAngle[v + 1]), worldAngle[v + 2]);
        if (length < 1e-10) return;
        double angle = Math.toRadians(yaw), c = Math.cos(angle), s = Math.sin(angle);
        double x = c * worldAngle[v] + s * worldAngle[v + 2], y = worldAngle[v + 1], z = -s * worldAngle[v] + c * worldAngle[v + 2];
        double a = matrix[0], b = matrix[4], d = matrix[1], e = matrix[5], g = matrix[2], h = matrix[6];
        double m02 = matrix[8], f = matrix[9], j = matrix[10];
        double determinant = a * (e * j - f * h) - b * (d * j - f * g) + m02 * (d * h - e * g);
        if (Math.abs(determinant) < 1e-12) return;
        double lx = ((e * j - f * h) * x + (m02 * h - b * j) * y + (b * f - m02 * e) * z) / determinant;
        double ly = ((f * g - d * j) * x + (a * j - m02 * g) * y + (m02 * d - a * f) * z) / determinant;
        double lz = ((d * h - e * g) * x + (b * g - a * h) * y + (a * e - b * d) * z) / determinant;
        double localLength = Math.hypot(Math.hypot(lx, ly), lz);
        if (!(localLength > 1e-10) || !Double.isFinite(localLength)) return;
        double factor = Math.sin(length / 2) / localLength;
        output[q] = (float) (lx * factor); output[q + 1] = (float) (ly * factor); output[q + 2] = (float) (lz * factor); output[q + 3] = (float) Math.cos(length / 2);
    }

    private static boolean valid(Frame frame) {
        return Double.isFinite(frame.seconds) && Double.isFinite(frame.x) && Double.isFinite(frame.y) && Double.isFinite(frame.z)
            && Float.isFinite(frame.yaw) && Float.isFinite(frame.scale) && frame.scale > 0 && frame.scale < 1e6
            && Float.isFinite(frame.strength) && frame.strength <= 1;
    }

    private static void limit(double[] vector, double maximum) {
        double length = Math.hypot(Math.hypot(vector[0], vector[1]), vector[2]);
        if (!Double.isFinite(length)) { Arrays.fill(vector, 0); return; }
        if (length > maximum) for (int i = 0; i < 3; i++) vector[i] *= maximum / length;
    }

    private static int[] select(GlbModel model, int[] parents) {
        boolean[] weighted = new boolean[model.nodes().size()];
        for (GlbModel.Node node : model.nodes()) if (node.skin() >= 0 && node.mesh() >= 0) {
            int[] palette = model.skins().get(node.skin()).joints();
            for (GlbModel.Primitive primitive : model.meshes().get(node.mesh()).primitives()) if (primitive.joints() != null)
                for (int i = 0; i < primitive.joints().length; i++) if (primitive.weights()[i] > .01f) weighted[palette[primitive.joints()[i]]] = true;
        }
        Map<String, int[]> pairs = new java.util.TreeMap<>();
        for (int i = 0; i < model.nodes().size(); i++) {
            GlbModel.Node node = model.nodes().get(i);
            Named named = named(node.name());
            if (named == null || parents[i] < 0 || node.mesh() >= 0 || node.matrix() != null || !weighted[i] || !safeSubtree(model, i)) continue;
            int[] pair = pairs.computeIfAbsent(named.key, ignored -> new int[]{-1, -1});
            pair[named.side] = pair[named.side] == -1 ? i : -2;
        }
        List<Integer> selected = new ArrayList<>();
        for (int[] pair : pairs.values()) if (pair[0] >= 0 && pair[1] >= 0 && parents[pair[0]] == parents[pair[1]]) {
            if (selected.size() + 2 > MAX_JOINTS) break;
            selected.add(pair[0]); selected.add(pair[1]);
        }
        return selected.stream().mapToInt(Integer::intValue).toArray();
    }

    private static boolean safeSubtree(GlbModel model, int root) {
        List<Integer> pending = new ArrayList<>(); pending.add(root);
        for (int cursor = 0; cursor < pending.size(); cursor++) {
            if (pending.size() > 16) return false;
            int node = pending.get(cursor);
            if (node != root) {
                String name = normalized(model.nodes().get(node).name());
                if (named(name) == null && (name.contains("spine") || name.contains("chest") || name.contains("torso")
                    || name.contains("pelvis") || name.contains("hips") || name.contains("neck") || name.contains("head")
                    || name.contains("arm") || name.contains("shoulder") || name.contains("hand") || name.contains("leg") || name.contains("foot"))) return false;
            }
            for (int child : model.nodes().get(node).children()) pending.add(child);
        }
        return true;
    }

    private static String normalized(String name) {
        String value = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (value.startsWith("jsec")) value = value.substring(4);
        if (value.startsWith("mixamorig")) value = value.substring(9);
        return value;
    }

    private static Named named(String name) {
        String value = normalized(name);
        var matcher = SIDE_FIRST.matcher(value);
        if (matcher.matches()) return new Named(matcher.group(2) + matcher.group(3), matcher.group(1).startsWith("l") ? 0 : 1);
        matcher = SIDE_LAST.matcher(value);
        return matcher.matches() ? new Named(matcher.group(1) + matcher.group(3), matcher.group(2).startsWith("l") ? 0 : 1) : null;
    }

    public record Frame(UUID id, double seconds, double x, double y, double z, float yaw, float scale, boolean enabled, float strength) {}
    private record Named(String key, int side) {}
    private static final class State {
        private final double[] anchor, velocity, angle, angularVelocity;
        private final double[] point = new double[3], acceleration = new double[3], axis = new double[3], force = new double[3];
        private Frame previous;
        private boolean warmed;
        private State(int count) { anchor = new double[count * 3]; velocity = new double[count * 3]; angle = new double[count * 3]; angularVelocity = new double[count * 3]; }
    }
}
