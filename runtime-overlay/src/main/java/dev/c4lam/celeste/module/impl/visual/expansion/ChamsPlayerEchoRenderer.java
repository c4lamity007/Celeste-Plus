package dev.c4lam.celeste.module.impl.visual.expansion;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.entity.model.LoadedEntityModels;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerSkinType;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Render-only player chams and historical poses. Extraction is client-thread only;
 * immutable frames can be consumed without accessing entities during drawing.
 */
public final class ChamsPlayerEchoRenderer {
    public static final int MAX_HISTORY = 100;
    public static final int MAX_DRAWN_MESHES = 48;
    private static final long SAMPLE_INTERVAL_NANOS = 25_000_000L;
    private static final double MAX_DISTANCE_SQUARED = 96D * 96D;
    private static final double TELEPORT_DISTANCE_SQUARED = 8D * 8D;
    private static final double MIN_PATH_SEGMENT_SQUARED = .002D * .002D;
    private final ArrayDeque<Sample> history = new ArrayDeque<>(MAX_HISTORY);
    private final ChamsPlayerModelSnapshot.CaptureBuffer captureBuffer = new ChamsPlayerModelSnapshot.CaptureBuffer();
    private ChamsPlayerPoseTransforms wide;
    private ChamsPlayerPoseTransforms slim;
    private ChamsPlayerPoseTransforms equippedWide;
    private ChamsPlayerPoseTransforms equippedSlim;
    private ChamsPlayerModelSnapshot.CaptureBuffer equipmentBuffer;
    private LoadedEntityModels models;
    private Object world;
    private AbstractClientPlayerEntity trackedPlayer;
    private Vec3d lastObservedPosition;
    private long lastSampleNanos;

    /** A history count is a memory cap; adaptive drawing uses at most 48 meshes. */
    public record Settings(int historyLength, int maxAgeMillis, int color, int accent,
                           float opacity, boolean afterimages) {
        public Settings {
            historyLength = Math.max(1, Math.min(MAX_HISTORY, historyLength));
            maxAgeMillis = Math.max(200, Math.min(5_000, maxAgeMillis));
            color &= 0xFFFFFF;
            accent &= 0xFFFFFF;
            opacity = ChamsPlayerModelSnapshot.clamp(opacity);
        }
    }

    /** age is 0 for the principal ghost and [0,1] for aged echoes. */
    public record Echo(ChamsPlayerModelSnapshot mesh, Vec3d position, float yaw,
                       int bottomColor, int topColor, float alpha, float age) {
        public Echo {
            if (mesh == null || position == null) throw new IllegalArgumentException("Echo needs geometry and position");
            alpha = ChamsPlayerModelSnapshot.clamp(alpha);
            age = ChamsPlayerModelSnapshot.clamp(age);
        }
    }

    public record Frame(List<Echo> echoes) {
        public static final Frame EMPTY = new Frame(List.of());
        public Frame {
            echoes = List.copyOf(echoes.subList(0, Math.min(MAX_DRAWN_MESHES, echoes.size())));
        }
        public boolean isEmpty() { return echoes.isEmpty(); }
    }

    record Sample(ChamsPlayerModelSnapshot mesh, Vec3d position, float yaw, long nanos) { }
    record Anchor(Sample sample, int nextIndex) { }
    private record Segment(Sample from, Sample to, double start, double length) { }

    public Frame extract(WorldExtractionContext context, Vec3d projectionPosition,
                         float projectionYaw, Settings settings) {
        return extract(context, MinecraftClient.getInstance().player, projectionPosition, projectionYaw, settings);
    }

    /**
     * One renderer instance tracks one player. The projected main ghost uses the
     * nearest recorded local pose, and the trail retains the true poses traversed
     * since that point. No packet, gameplay or live-entity state is changed.
     */
    public Frame extract(WorldExtractionContext context, AbstractClientPlayerEntity player,
                         Vec3d projectionPosition, float projectionYaw, Settings settings) {
        if (player == null || context.world() == null || context.camera() == null
            || projectionPosition == null || !finite(projectionPosition) || !Float.isFinite(projectionYaw)) {
            clear();
            return Frame.EMPTY;
        }
        ensureModels();
        if (world != context.world() || trackedPlayer != player) {
            clearHistory();
            world = context.world();
            trackedPlayer = player;
        }
        long now = System.nanoTime();
        float tickDelta = context.tickCounter().getTickProgress(!context.world().getTickManager().shouldSkipTick(player));
        // EntityRenderer uses lastRender*, while Entity.getLerpedPos uses last*.
        // Keep trail anchors consistent with the actual vanilla render origin.
        Vec3d position = new Vec3d(MathHelper.lerp((double) tickDelta, player.lastRenderX, player.getX()),
            MathHelper.lerp((double) tickDelta, player.lastRenderY, player.getY()),
            MathHelper.lerp((double) tickDelta, player.lastRenderZ, player.getZ()));
        if (!finite(position)) return Frame.EMPTY;
        if (lastObservedPosition != null
            && position.squaredDistanceTo(lastObservedPosition) > TELEPORT_DISTANCE_SQUARED) clearHistory();
        lastObservedPosition = position;
        long maxAge = settings.maxAgeMillis() * 1_000_000L;
        expire(now, maxAge, settings.historyLength());
        // Temporal sampling bounds expensive vanilla state extraction independently
        // of monitor refresh rate. There is no catch-up loop after a long frame.
        if (lastSampleNanos == 0L || now - lastSampleNanos >= SAMPLE_INTERVAL_NANOS || now < lastSampleNanos) {
            ChamsPlayerModelSnapshot mesh = snapshot(player, tickDelta);
            if (mesh != null) {
                if (history.size() >= settings.historyLength()) history.removeFirst();
                history.addLast(new Sample(mesh, mesh.position(), mesh.playerYaw(), now));
            }
            lastSampleNanos = now;
        }
        if (history.isEmpty() || settings.opacity() <= .001F) return Frame.EMPTY;
        Vec3d camera = context.camera().getCameraPos();
        if (camera == null || !finite(camera)) return Frame.EMPTY;
        Sample[] samples = history.toArray(Sample[]::new);
        Anchor anchor = findAnchor(samples, projectionPosition);
        Sample principal = anchor.sample();
        List<Echo> drawn = new ArrayList<>(MAX_DRAWN_MESHES);
        int bright = ChamsPlayerModelSnapshot.mix(settings.color(), 0xFFFFFF, .40F);
        float principalAlpha = settings.opacity() * nearCameraFade(camera, projectionPosition);
        Echo main = new Echo(principal.mesh(), projectionPosition, projectionYaw,
            settings.color(), bright, principalAlpha, 0F);
        boolean mainVisible = visible(context.frustum(), camera, main);
        int budget = drawBudget(MinecraftClient.getInstance().getCurrentFps(),
            camera.squaredDistanceTo(projectionPosition));
        int remaining = budget - (mainVisible ? 1 : 0);
        if (settings.afterimages() && remaining > 0 && samples.length > 1) {
            Sample projected = new Sample(principal.mesh(), projectionPosition, projectionYaw, principal.nanos());
            appendTrail(samples, anchor.nextIndex(), projected, remaining, settings, now, maxAge,
                camera, position, context.frustum(), drawn);
        }
        if (mainVisible) drawn.add(main);
        if (drawn.isEmpty()) return Frame.EMPTY;
        // Coarse back-to-front ordering remains useful for a translucent material;
        // the same immutable list also works with the root's emissive material.
        drawn.sort(Comparator.comparingDouble((Echo echo) -> camera.squaredDistanceTo(echo.position())).reversed());
        return new Frame(drawn);
    }

    /**
     * Capture a current player mesh for other chams callers, without using history.
     * Reuse one helper for a bounded list of players, then assemble Echo/Frame.
     */
    public ChamsPlayerModelSnapshot snapshot(AbstractClientPlayerEntity player, float tickDelta) {
        if (player == null || !Float.isFinite(tickDelta)) return null;
        ensureModels();
        boolean useSlim = player.getSkin().model() == PlayerSkinType.SLIM;
        if (useSlim && slim == null) slim = ChamsPlayerPoseTransforms.create(MinecraftClient.getInstance(), true);
        if (!useSlim && wide == null) wide = ChamsPlayerPoseTransforms.create(MinecraftClient.getInstance(), false);
        return ChamsPlayerModelSnapshot.capture(useSlim ? slim : wide, player, tickDelta, captureBuffer);
    }

    /** Equipment is opt-in; historical Fake Lag captures remain body-only. */
    public ChamsPlayerModelSnapshot snapshot(AbstractClientPlayerEntity player, float tickDelta, boolean equipment) {
        if (!equipment) return snapshot(player, tickDelta);
        if (player == null || !Float.isFinite(tickDelta)) return null;
        ensureModels();
        MinecraftClient client = MinecraftClient.getInstance();
        boolean useSlim = player.getSkin().model() == PlayerSkinType.SLIM;
        if (useSlim && equippedSlim == null) equippedSlim = ChamsPlayerPoseTransforms.create(client, true, true);
        if (!useSlim && equippedWide == null) equippedWide = ChamsPlayerPoseTransforms.create(client, false, true);
        if (equipmentBuffer == null) equipmentBuffer = new ChamsPlayerModelSnapshot.CaptureBuffer(ChamsPlayerModelSnapshot.MAX_EQUIPPED_VERTICES);
        return ChamsPlayerModelSnapshot.capture(useSlim ? equippedSlim : equippedWide, player, tickDelta, equipmentBuffer, true);
    }

    /** Must be called by the owner on disable, disconnect or inactive visual state. */
    public void clear() {
        clearHistory();
        world = null;
        trackedPlayer = null;
    }

    public int historySize() { return history.size(); }

    private void ensureModels() {
        LoadedEntityModels current = MinecraftClient.getInstance().getLoadedEntityModels();
        if (models != current) {
            clear();
            models = current;
            wide = null;
            slim = null;
            equippedWide = null;
            equippedSlim = null;
            equipmentBuffer = null;
        }
    }

    private void clearHistory() {
        history.clear();
        lastObservedPosition = null;
        lastSampleNanos = 0L;
    }

    private void expire(long now, long maxAge, int limit) {
        while (!history.isEmpty() && (history.size() > limit || now < history.peekFirst().nanos()
            || now - history.peekFirst().nanos() > maxAge)) history.removeFirst();
    }

    /** Closest point on the compatible path, preserving the latest stationary pose. */
    static Anchor findAnchor(Sample[] samples, Vec3d position) {
        int first = 0, second = 0;
        float blend = 0F;
        double distance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < samples.length; i++) {
            double candidate = samples[i].position().squaredDistanceTo(position);
            if (candidate <= distance) { first = i; second = i; blend = 0F; distance = candidate; }
        }
        for (int i = 0; i + 1 < samples.length; i++) {
            Sample a = samples[i], b = samples[i + 1];
            Vec3d delta = b.position().subtract(a.position());
            double squared = delta.lengthSquared();
            if (squared <= MIN_PATH_SEGMENT_SQUARED || squared > TELEPORT_DISTANCE_SQUARED
                || !a.mesh().compatibleWith(b.mesh())) continue;
            float t = ChamsPlayerModelSnapshot.clamp((float) (position.subtract(a.position()).dotProduct(delta) / squared));
            double candidate = a.position().lerp(b.position(), t).squaredDistanceTo(position);
            // An interior point must actually be closer. Equal endpoint distances
            // keep the latest vertex chosen above, including stationary tails.
            if (candidate + 1.0E-10D < distance) {
                first = i; second = i + 1; blend = t; distance = candidate;
            }
        }
        if (first == second || blend <= .00001F) return new Anchor(samples[first], first + 1);
        if (blend >= .99999F) return new Anchor(samples[second], second + 1);
        return new Anchor(blend(samples[first], samples[second], blend), second);
    }

    /** Uniform arc-length sampling; capture frequency and speed do not create gaps. */
    static void appendTrail(Sample[] samples, int first, Sample projected, int budget, Settings settings,
                            long now, long maxAge, Vec3d camera, Vec3d currentPosition, Frustum frustum,
                            List<Echo> output) {
        int available = Math.min(Math.max(0, budget), MAX_DRAWN_MESHES - output.size());
        if (available == 0) return;
        List<Segment> segments = new ArrayList<>(Math.min(MAX_HISTORY, samples.length));
        Sample previous = projected;
        double length = 0D;
        for (int index = Math.max(0, first); index < samples.length; index++) {
            Sample next = samples[index];
            double squared = previous.position().squaredDistanceTo(next.position());
            if (squared > MIN_PATH_SEGMENT_SQUARED && squared <= TELEPORT_DISTANCE_SQUARED
                && previous.mesh().compatibleWith(next.mesh())) {
                double segmentLength = Math.sqrt(squared);
                segments.add(new Segment(previous, next, length, segmentLength));
                length += segmentLength;
            }
            // Zero motion contributes no arc length. A topology/teleport boundary
            // starts a fresh segment, never a blend between unrelated body parts.
            previous = next;
        }
        if (segments.isEmpty()) return;
        double requestedSpacing = targetSpacing(camera.squaredDistanceTo(projected.position()));
        int count = Math.min(available, Math.max(0, (int) Math.floor(length / requestedSpacing) - 1));
        if (count == 0) return;
        double spacing = length / (count + 1D);
        // The silhouette union applies alpha once regardless of how many poses
        // overlap. Do not reduce coverage when adding interpolation samples.
        float coverage = coverageOpacity();
        int segmentIndex = 0;
        for (int sampleIndex = 0; sampleIndex < count; sampleIndex++) {
            double along = (sampleIndex + 1D) * spacing;
            while (segmentIndex + 1 < segments.size()
                && along > segments.get(segmentIndex).start() + segments.get(segmentIndex).length()) segmentIndex++;
            Segment segment = segments.get(segmentIndex);
            float t = ChamsPlayerModelSnapshot.clamp((float) ((along - segment.start()) / segment.length()));
            Vec3d origin = segment.from().position().lerp(segment.to().position(), t);
            // Check cheap spatial/alpha conditions before interpolating packed mesh arrays.
            if (origin.squaredDistanceTo(currentPosition) < .055D * .055D
                || origin.squaredDistanceTo(camera) > MAX_DISTANCE_SQUARED) continue;
            float path = (float) (along / length);
            long nanos = segment.from().nanos() + (long) ((segment.to().nanos() - segment.from().nanos()) * (double) t);
            float age = ChamsPlayerModelSnapshot.clamp((now - nanos) / (float) Math.max(1L, maxAge));
            float envelope = (float) Math.sin(Math.PI * path);
            float alpha = settings.opacity() * coverage * (1F - age) * (1F - age)
                * envelope * nearCameraFade(camera, origin);
            if (alpha <= .003F) continue;
            Sample blended = blend(segment.from(), segment.to(), t);
            int bottom = ChamsPlayerModelSnapshot.mix(settings.color(), settings.accent(), path);
            Echo echo = new Echo(blended.mesh(), origin, blended.yaw(), bottom,
                ChamsPlayerModelSnapshot.mix(bottom, 0xFFFFFF, .24F), alpha, age);
            if (visible(frustum, camera, echo)) output.add(echo);
        }
    }

    private static Sample blend(Sample first, Sample second, float t) {
        ChamsPlayerModelSnapshot mesh = first.mesh().interpolate(second.mesh(), t);
        return new Sample(mesh, first.position().lerp(second.position(), t), mixYaw(first.yaw(), second.yaw(), t),
            first.nanos() + (long) ((second.nanos() - first.nanos()) * (double) t));
    }

    static float mixYaw(float from, float to, float t) {
        return from + MathHelper.wrapDegrees(to - from) * ChamsPlayerModelSnapshot.clamp(t);
    }

    static double targetSpacing(double distanceSquared) {
        return distanceSquared > 48D * 48D ? .22D : distanceSquared > 24D * 24D ? .16D
            : distanceSquared > 12D * 12D ? .10D : .065D;
    }

    /** One uniform silhouette composite: density must not change its coverage. */
    static float coverageOpacity() { return .30F; }

    static int drawBudget(int fps, double distanceSquared) {
        int budget = fps <= 0 ? 18 : fps < 30 ? 6 : fps < 45 ? 12 : fps < 60 ? 18 : fps < 90 ? 28 : fps < 120 ? 36 : 48;
        if (distanceSquared > 48D * 48D) budget = Math.min(budget, 6);
        else if (distanceSquared > 24D * 24D) budget = Math.min(budget, 12);
        else if (distanceSquared > 12D * 12D) budget = Math.min(budget, 24);
        return budget;
    }

    private static float nearCameraFade(Vec3d camera, Vec3d origin) {
        // Avoid filling the camera with additive geometry when a trail catches up.
        double distance = camera.squaredDistanceTo(origin.add(0D, .9D, 0D));
        return ChamsPlayerModelSnapshot.clamp((float) ((Math.sqrt(distance) - .55D) / .75D));
    }

    public static boolean visible(Frustum frustum, Vec3d camera, Echo echo) {
        return echo.alpha() > .003F && Float.isFinite(echo.yaw()) && finite(echo.position())
            && camera.squaredDistanceTo(echo.position()) <= MAX_DISTANCE_SQUARED
            && (frustum == null || frustum.isVisible(echo.mesh().worldBounds(echo.position(), echo.yaw())));
    }

    private static boolean finite(Vec3d position) {
        return Double.isFinite(position.x) && Double.isFinite(position.y) && Double.isFinite(position.z);
    }

    /** One layer acquisition and one vertex batch, independent of the history count. */
    public static void draw(WorldRenderContext context, Frame frame, RenderLayer layer) {
        if (frame == null || frame.isEmpty() || layer == null || context.consumers() == null
            || context.matrices() == null || context.worldState() == null
            || context.worldState().cameraRenderState == null) return;
        Vec3d camera = context.worldState().cameraRenderState.pos;
        if (camera == null || !finite(camera)) return;
        MatrixStack.Entry matrix = context.matrices().peek();
        VertexConsumer consumer = context.consumers().getBuffer(layer);
        for (Echo echo : frame.echoes()) {
            echo.mesh().emit(consumer, matrix, camera, echo.position(), echo.yaw(),
                echo.bottomColor(), echo.topColor(), echo.alpha(), echo.age());
        }
    }
}
