package dev.c4lam.celeste.module.impl.visual.expansion;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Immutable, posed vanilla player geometry. Holds no entity, model, item or render-state references. */
public final class PlayerModelSnapshot {
    // Vanilla wide/slim players including all six clothing parts have 288 vertices.
    // Keep a fixed ceiling even if another mod adds parts to the private player model.
    public static final int MAX_VERTICES = 512;
    public static final int MAX_EQUIPPED_VERTICES = 8192;
    public static final int MAX_TEXTURE_RANGES = 32;
    private static final int STRIDE = 9;
    private static final byte[] NO_PART_FLAGS = new byte[0];
    private static final AtomicLong NEXT_MODEL_GENERATION = new AtomicLong();
    private final float[] vertices;
    private final Box bounds;
    private final float playerYaw;
    private final Vec3d position;
    private final long modelGeneration;
    private final byte[] partFlags;
    private final Identifier skinTexture;
    private final List<TextureRange> textureRanges;

    public record TextureRange(int firstVertex, int vertices, Identifier texture) { }
    public List<TextureRange> textureRanges() { return textureRanges; }
    public boolean hasEquipment() { return !textureRanges.isEmpty(); }

    private PlayerModelSnapshot(float[] vertices, Box bounds, float playerYaw, Vec3d position,
                                long modelGeneration, byte[] partFlags) {
        this(vertices, bounds, playerYaw, position, modelGeneration, partFlags, null);
    }

    private PlayerModelSnapshot(float[] vertices, Box bounds, float playerYaw, Vec3d position,
                                long modelGeneration, byte[] partFlags, Identifier skinTexture) {
        this(vertices, bounds, playerYaw, position, modelGeneration, partFlags, skinTexture, List.of());
    }

    private PlayerModelSnapshot(float[] vertices, Box bounds, float playerYaw, Vec3d position,
                                long modelGeneration, byte[] partFlags, Identifier skinTexture,
                                List<TextureRange> textureRanges) {
        this.textureRanges = List.copyOf(textureRanges);
        this.skinTexture = skinTexture;
        this.vertices = vertices;
        this.bounds = bounds;
        this.playerYaw = playerYaw;
        this.position = position;
        this.modelGeneration = modelGeneration;
        this.partFlags = partFlags;
    }

    static PlayerModelSnapshot capture(PlayerPoseTransforms renderer, AbstractClientPlayerEntity player,
                                       float tickDelta, CaptureBuffer buffer) {
        return capture(renderer, player, tickDelta, buffer, false);
    }

    static PlayerModelSnapshot capture(PlayerPoseTransforms renderer, AbstractClientPlayerEntity player,
                                       float tickDelta, CaptureBuffer buffer, boolean equipment) {
        // State is private and transient: never retain vanilla's mutable extracted world state.
        PlayerEntityRenderState state = renderer.createRenderState();
        renderer.updateRenderState(player, state, tickDelta);
        MatrixStack matrices = new MatrixStack();
        renderer.applyTransforms(state, matrices);
        renderer.getModel().setAngles(state);
        buffer.reset();
        buffer.captureTopology(renderer.getModel());
        if (equipment) buffer.textureRange(player.getSkin().body().texturePath());
        // Body-only callers never submit features. Equipment uses the owned
        // vanilla armor/hand/head models, with their pose and item transforms.
        renderer.getModel().render(matrices, buffer, 0x00F000F0, 0, 0xFFFFFFFF);
        if (equipment) {
            buffer.preserveBody();
            renderer.captureEquipment(state, matrices, new EquipmentCaptureQueue(buffer));
        }
        PlayerModelSnapshot result = buffer.finish(player.getLerpedYaw(tickDelta), new Vec3d(state.x, state.y, state.z));
        return result == null ? null : result.withSkinTexture(player.getSkin().body().texturePath());
    }

    /** Immutable texture identity; the render thread resolves its current GPU view. */
    public Identifier skinTexture() { return skinTexture; }

    private PlayerModelSnapshot withSkinTexture(Identifier texture) {
        return new PlayerModelSnapshot(vertices, bounds, playerYaw, position, modelGeneration, partFlags, texture, textureRanges);
    }

    public int vertexCount() { return vertices.length / STRIDE; }

    /** Pass this to Echo.yaw for zero extra rotation of the captured vanilla pose. */
    public float playerYaw() { return playerYaw; }

    /** Vanilla's extracted render origin, before the model's already captured position offset. */
    public Vec3d position() { return position; }

    public Box localBounds() { return bounds; }

    /** Requires the same captured model generation, part visibility and exact vertex UV/order. */
    public boolean compatibleWith(PlayerModelSnapshot other) {
        if (other == null) return false;
        if (other == this) return true;
        if (modelGeneration != other.modelGeneration || vertices.length != other.vertices.length
                || !Arrays.equals(partFlags, other.partFlags) || !textureRanges.equals(other.textureRanges)) return false;
        for (int i = 0; i < vertices.length; i += STRIDE) {
            if (Float.floatToRawIntBits(vertices[i + 7]) != Float.floatToRawIntBits(other.vertices[i + 7])
                    || Float.floatToRawIntBits(vertices[i + 8]) != Float.floatToRawIntBits(other.vertices[i + 8])) return false;
        }
        return true;
    }

    /**
     * Interpolates adjacent compatible poses in a common yaw orientation. The
     * result owns a flat vertex array and retains neither endpoint. Incompatible
     * topology selects the nearer endpoint (this at the midpoint); callers that
     * connect a path should split that segment instead. Endpoints preserve identity.
     */
    public PlayerModelSnapshot interpolate(PlayerModelSnapshot other, float amount) {
        float t = clamp(amount);
        if (other == null || t <= 0F || other == this) return this;
        if (t >= 1F) return other;
        PlayerModelSnapshot nearer = t <= .5F ? this : other;
        if (!compatibleWith(other)) return nearer;

        double yawDelta = wrapDegrees((double) other.playerYaw - playerYaw);
        float yaw = (float) (wrapDegrees(playerYaw) + yawDelta * t);
        // Captured vertices already include vanilla body yaw. Align both endpoint
        // meshes to the interpolated yaw before blending, avoiding shrinkage when
        // the player turns through a large angle or crosses the +/-180 boundary.
        double a = Math.toRadians(wrapDegrees((double) playerYaw - yaw));
        double b = Math.toRadians(wrapDegrees((double) other.playerYaw - yaw));
        double ac = Math.cos(a), as = Math.sin(a), bc = Math.cos(b), bs = Math.sin(b);
        float[] blended = new float[vertices.length];
        for (int i = 0; i < blended.length; i += STRIDE) {
            double ax = ac * vertices[i] + as * vertices[i + 2];
            double az = -as * vertices[i] + ac * vertices[i + 2];
            double bx = bc * other.vertices[i] + bs * other.vertices[i + 2];
            double bz = -bs * other.vertices[i] + bc * other.vertices[i + 2];
            blended[i] = (float) (ax + (bx - ax) * t);
            blended[i + 1] = (float) ((double) vertices[i + 1] + ((double) other.vertices[i + 1] - vertices[i + 1]) * t);
            blended[i + 2] = (float) (az + (bz - az) * t);
            if (!Float.isFinite(blended[i]) || !Float.isFinite(blended[i + 1]) || !Float.isFinite(blended[i + 2])) return nearer;

            double anx = ac * vertices[i + 3] + as * vertices[i + 5];
            double any = vertices[i + 4];
            double anz = -as * vertices[i + 3] + ac * vertices[i + 5];
            double bnx = bc * other.vertices[i + 3] + bs * other.vertices[i + 5];
            double bny = other.vertices[i + 4];
            double bnz = -bs * other.vertices[i + 3] + bc * other.vertices[i + 5];
            double nx = anx + (bnx - anx) * t;
            double ny = any + (bny - any) * t;
            double nz = anz + (bnz - anz) * t;
            double lengthSquared = nx * nx + ny * ny + nz * nz;
            if (lengthSquared < 1.0E-12D) {
                // Opposite normals can cancel exactly; keep a valid endpoint
                // direction instead of emitting NaNs or a zero lighting normal.
                nx = t <= .5F ? anx : bnx;
                ny = t <= .5F ? any : bny;
                nz = t <= .5F ? anz : bnz;
                lengthSquared = nx * nx + ny * ny + nz * nz;
            }
            if (!Double.isFinite(lengthSquared) || lengthSquared <= 0D) return nearer;
            double inverseLength = 1D / Math.sqrt(lengthSquared);
            blended[i + 3] = (float) (nx * inverseLength);
            blended[i + 4] = (float) (ny * inverseLength);
            blended[i + 5] = (float) (nz * inverseLength);
            blended[i + 7] = vertices[i + 7];
            blended[i + 8] = vertices[i + 8];
        }
        Vec3d origin = position.lerp(other.position, t);
        if (!Double.isFinite(origin.x) || !Double.isFinite(origin.y) || !Double.isFinite(origin.z)) return nearer;
        return finishVertices(blended, yaw, origin, modelGeneration, partFlags)
            .withSkinTexture(nearer.skinTexture).withTextureRanges(textureRanges);
    }

    private PlayerModelSnapshot withTextureRanges(List<TextureRange> ranges) {
        return new PlayerModelSnapshot(vertices, bounds, playerYaw, position, modelGeneration, partFlags, skinTexture, ranges);
    }

    private static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360D;
        if (wrapped >= 180D) wrapped -= 360D;
        if (wrapped < -180D) wrapped += 360D;
        return wrapped;
    }

    private static PlayerModelSnapshot finishVertices(float[] packed, float yaw, Vec3d origin,
                                                       long generation, byte[] flags) {
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int i = 0; i < packed.length; i += STRIDE) {
            minX = Math.min(minX, packed[i]); maxX = Math.max(maxX, packed[i]);
            minY = Math.min(minY, packed[i + 1]); maxY = Math.max(maxY, packed[i + 1]);
            minZ = Math.min(minZ, packed[i + 2]); maxZ = Math.max(maxZ, packed[i + 2]);
        }
        double height = Math.max(.001D, maxY - minY);
        for (int i = 0; i < packed.length; i += STRIDE) packed[i + 6] = clamp((float) ((packed[i + 1] - minY) / height));
        return new PlayerModelSnapshot(packed, new Box(minX, minY, minZ, maxX, maxY, maxZ), yaw, origin, generation, flags);
    }

    /** Exact rotated AABB used by the extraction frustum, including prone poses. */
    public Box worldBounds(Vec3d origin, float yaw) {
        double angle = Math.toRadians(playerYaw - yaw);
        double c = Math.cos(angle), s = Math.sin(angle);
        double minX = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 4; corner++) {
            double x = (corner & 1) == 0 ? bounds.minX : bounds.maxX;
            double z = (corner & 2) == 0 ? bounds.minZ : bounds.maxZ;
            double rx = c * x + s * z, rz = -s * x + c * z;
            minX = Math.min(minX, rx); maxX = Math.max(maxX, rx);
            minZ = Math.min(minZ, rz); maxZ = Math.max(maxZ, rz);
        }
        return new Box(origin.x + minX, origin.y + bounds.minY, origin.z + minZ,
            origin.x + maxX, origin.y + bounds.maxY, origin.z + maxZ).expand(.025D);
    }

    /**
     * Writes POSITION_TEXTURE_COLOR_NORMAL quads. Position and Normal share the
     * supplied camera transform; UV0 is (normalized posed height, normalized age).
     * Original skin UVs remain captured internally for future material variants.
     */
    public void emit(VertexConsumer out, MatrixStack.Entry matrix, Vec3d camera, Vec3d origin,
                     float yaw, int bottomColor, int topColor, float alpha, float age) {
        emit(out, matrix, camera, origin, yaw, bottomColor, topColor, alpha, age, false);
    }

    /** Keep vanilla skin alpha holes out of the exterior glow's coverage mask. */
    public void emitSkinMask(VertexConsumer out, MatrixStack.Entry matrix, Vec3d camera, Vec3d origin,
                             float yaw, int color, float alpha) {
        emit(out, matrix, camera, origin, yaw, color, color, alpha, 0F, true);
    }

    private void emit(VertexConsumer out, MatrixStack.Entry matrix, Vec3d camera, Vec3d origin,
                      float yaw, int bottomColor, int topColor, float alpha, float age, boolean skinMask) {
        float opacity = clamp(alpha);
        if (opacity <= .001F) return;
        float radians = (float) Math.toRadians(playerYaw - yaw);
        float c = (float) Math.cos(radians), s = (float) Math.sin(radians);
        float ox = (float) (origin.x - camera.x), oy = (float) (origin.y - camera.y);
        float oz = (float) (origin.z - camera.z);
        float normalizedAge = clamp(age);
        for (int index = 0; index < vertices.length; index += STRIDE) {
            float x = vertices[index], y = vertices[index + 1], z = vertices[index + 2];
            float nx = vertices[index + 3], ny = vertices[index + 4], nz = vertices[index + 5];
            float height = vertices[index + 6];
            int color = argb(opacity, mix(bottomColor, topColor, smooth(height)));
            out.vertex(matrix, ox + c * x + s * z, oy + y, oz - s * x + c * z)
                .texture(skinMask ? vertices[index + 7] : height, skinMask ? vertices[index + 8] : normalizedAge).color(color)
                .normal(matrix, c * nx + s * nz, ny, -s * nx + c * nz);
        }
    }

    private static float smooth(float value) { return value * value * (3F - 2F * value); }

    static float clamp(float value) { return Float.isFinite(value) ? Math.max(0F, Math.min(1F, value)) : 0F; }

    static int argb(float alpha, int rgb) { return Math.round(clamp(alpha) * 255F) << 24 | rgb & 0xFFFFFF; }

    static int mix(int first, int second, float amount) {
        float t = clamp(amount);
        int r = Math.round((first >>> 16 & 255) * (1F - t) + (second >>> 16 & 255) * t);
        int g = Math.round((first >>> 8 & 255) * (1F - t) + (second >>> 8 & 255) * t);
        int b = Math.round((first & 255) * (1F - t) + (second & 255) * t);
        return r << 16 | g << 8 | b;
    }

    /** Reused extraction scratch space; each finished snapshot owns its packed array. */
    static final class CaptureBuffer implements VertexConsumer {
        private final float[] data;
        private final ArrayList<TextureRange> ranges = new ArrayList<>();
        private Identifier texture;
        private int rangeStart;
        private int bodyVertices;
        private Identifier bodyTexture;

        CaptureBuffer() { this(MAX_VERTICES); }
        CaptureBuffer(int maxVertices) {
            if (maxVertices < 4 || maxVertices > MAX_EQUIPPED_VERTICES || (maxVertices & 3) != 0)
                throw new IllegalArgumentException("Invalid snapshot vertex budget");
            data = new float[maxVertices * STRIDE];
        }

        void textureRange(Identifier next) {
            if (next == null || invalid) { invalid = true; return; }
            if (next.equals(texture)) return;
            finishRange();
            texture = next;
            rangeStart = count;
        }

        /** Keep complete body geometry when an oversized resource-pack item exceeds the cap. */
        void preserveBody() {
            if (!invalid && count > 0 && (count & 3) == 0) {
                bodyVertices = count;
                bodyTexture = texture;
            }
        }

        private void finishRange() {
            if (texture == null || count == rangeStart) return;
            if (ranges.size() >= MAX_TEXTURE_RANGES || ((count - rangeStart) & 3) != 0) { invalid = true; return; }
            ranges.add(new TextureRange(rangeStart, count - rangeStart, texture));
            rangeStart = count;
        }
        private int count;
        private boolean invalid;
        // Only this bounded scratch object retains its last owned model. Immutable
        // snapshots store the unique primitive generation, never a model reference.
        private PlayerEntityModel lastModel;
        private long lastModelGeneration;
        private long capturedGeneration;
        private byte[] capturedPartFlags = NO_PART_FLAGS;

        void reset() {
            count = 0; invalid = false;
            ranges.clear(); texture = null; rangeStart = 0;
            bodyVertices = 0; bodyTexture = null;
            capturedGeneration = 0L;
            capturedPartFlags = NO_PART_FLAGS;
        }

        void captureTopology(PlayerEntityModel model) {
            if (model != lastModel) {
                lastModel = model;
                lastModelGeneration = NEXT_MODEL_GENERATION.incrementAndGet();
            }
            var parts = model.getParts();
            if (parts.size() > MAX_VERTICES) { invalid = true; return; }
            capturedGeneration = lastModelGeneration;
            capturedPartFlags = new byte[parts.size()];
            for (int i = 0; i < parts.size(); i++) {
                var part = parts.get(i);
                capturedPartFlags[i] = (byte) ((part.visible ? 1 : 0) | (part.hidden ? 2 : 0));
            }
        }

        PlayerModelSnapshot finish(float yaw, Vec3d position) {
            finishRange();
            if (invalid && bodyVertices > 0) {
                count = bodyVertices;
                invalid = false;
                ranges.clear();
                texture = bodyTexture;
                rangeStart = 0;
                finishRange();
            }
            if (invalid || count == 0 || (count & 3) != 0 || !Float.isFinite(yaw)) return null;
            if (position == null || !Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)) return null;
            return finishVertices(Arrays.copyOf(data, count * STRIDE), yaw, position,
                    capturedGeneration, capturedPartFlags.clone()).withTextureRanges(ranges);
        }

        @Override
        public void vertex(float x, float y, float z, int color, float u, float v,
                           int overlay, int light, float nx, float ny, float nz) {
            vertex(x, y, z); texture(u, v); normal(nx, ny, nz);
        }

        @Override
        public VertexConsumer vertex(float x, float y, float z) {
            if (invalid) return this;
            if (count >= data.length / STRIDE || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                invalid = true;
                return this;
            }
            int i = count++ * STRIDE;
            data[i] = x; data[i + 1] = y; data[i + 2] = z;
            data[i + 3] = 0F; data[i + 4] = 1F; data[i + 5] = 0F;
            data[i + 7] = 0F; data[i + 8] = 0F;
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            if (invalid || count == 0) return this;
            float length = (float) Math.sqrt(x * x + y * y + z * z);
            if (!Float.isFinite(length) || length < .00001F) { invalid = true; return this; }
            int i = (count - 1) * STRIDE;
            data[i + 3] = x / length; data[i + 4] = y / length; data[i + 5] = z / length;
            return this;
        }

        @Override
        public VertexConsumer texture(float u, float v) {
            if (invalid || count == 0) return this;
            if (!Float.isFinite(u) || !Float.isFinite(v)) { invalid = true; return this; }
            int i = (count - 1) * STRIDE;
            data[i + 7] = u; data[i + 8] = v;
            return this;
        }

        @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer color(int argb) { return this; }
        @Override public VertexConsumer overlay(int u, int v) { return this; }
        @Override public VertexConsumer light(int u, int v) { return this; }
        @Override public VertexConsumer lineWidth(float width) { return this; }
    }
}
