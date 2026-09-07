package dev.c4lam.celeste.module.impl.visual;

import dev.c4lam.celeste.CelesteClient;
import dev.c4lam.celeste.compat.RenderCompatibility;
import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.ModuleManager;
import dev.c4lam.celeste.render.model.GlbImporter;
import dev.c4lam.celeste.render.model.BundledPlayerModel;
import dev.c4lam.celeste.render.model.GlbModel;
import dev.c4lam.celeste.render.model.ModelAnimations;
import dev.c4lam.celeste.render.model.PlayerMeshRenderer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.SynchronousResourceReloader;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/** Imported meshes replace visual submissions only; no entities, input or packets are created. */
public final class PlayerModelsFeature implements ModuleFeature {
    private static final RenderStateDataKey<Frame> FRAME = RenderStateDataKey.create(() -> "celeste_player_models");
    private static final Map<LivingEntityRenderState, Captured> CAPTURED = new WeakHashMap<>();
    private static final Map<UUID, ModelAnimations> ANIMATIONS = new HashMap<>();
    private static Set<LivingEntityRenderState> replacements = Collections.newSetFromMap(new IdentityHashMap<>());
    private static PlayerMeshRenderer mesh;
    private static GlbModel imported;
    private static boolean enabled, closed, loading, gpuReady;
    private static long generation;
    private static String message = "Choose a self-contained .glb model with embedded textures.";
    private static String scope = "Self";
    private static float scale = 1;
    private static boolean celebrate = true, secondaryMotion = true;
    private static float motionStrength = .5f;
    private static UUID attackedUuid;
    private static int attackedId = -1, attackedUntil;

    public PlayerModelsFeature() {
        BetaSubscriptions.register(WorldRenderEvents.END_EXTRACTION, "celeste:player_models/extract",
            WorldRenderEvents.EndExtraction.class, context -> {
                if (RenderCompatibility.isShadowPass()) return;
                replacements.clear();
                if (RenderCompatibility.isShadowPass() || !active() || context.worldState() == null) return;
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player == null || mesh == null) return;
                var states = context.worldState().entityRenderStates.stream()
                    .filter(state -> state instanceof PlayerEntityRenderState && CAPTURED.containsKey(state))
                    .map(state -> (LivingEntityRenderState) state)
                    .sorted(Comparator.comparingDouble(state -> new Vec3d(state.x, state.y, state.z)
                        .squaredDistanceTo(client.player.getEntityPos()))).toList();
                List<PlayerMeshRenderer.Instance> instances = new ArrayList<>();
                int max = Math.max(1, Math.min(12, Math.min(200_000 / mesh.vertexCount(), 1_200_000 / mesh.indexCount())));
                for (LivingEntityRenderState state : states) {
                    Captured capture = CAPTURED.get(state);
                    if (capture == null || !capture.visible || state.invisible || state.invisibleToPlayer
                        || capture.self && "Others".equals(scope) || !capture.self && "Self".equals(scope)
                        || new Vec3d(state.x, state.y, state.z).squaredDistanceTo(client.player.getEntityPos()) > 48 * 48)
                        continue;
                    ModelAnimations controller = ANIMATIONS.computeIfAbsent(capture.uuid, key -> new ModelAnimations(imported));
                    ModelAnimations.Playback playback = controller.frame(state.age / 20.0, capture.stationary, celebrate);
                    instances.add(new PlayerMeshRenderer.Instance(new Vec3d(state.x, state.y, state.z), state.bodyYaw,
                        scale, capture.light, playback, capture.uuid, state.age / 20.0, secondaryMotion, motionStrength));
                    if (gpuReady) replacements.add(state);
                    if (instances.size() >= max) break;
                }
                context.worldState().setData(FRAME, new Frame(mesh, List.copyOf(instances)));
                if (ANIMATIONS.size() > 128) ANIMATIONS.keySet().removeIf(uuid -> context.world().getPlayerByUuid(uuid) == null);
            }, WorldRenderEvents.END_EXTRACTION::register);
        BetaSubscriptions.register(WorldRenderEvents.AFTER_ENTITIES, "celeste:player_models/draw",
            WorldRenderEvents.AfterEntities.class, context -> {
                if (RenderCompatibility.isShadowPass() || !active() || context.worldState() == null) return;
                Frame frame = context.worldState().getData(FRAME);
                if (frame == null || frame.renderer != mesh || frame.instances.isEmpty()) return;
                try {
                    gpuReady = mesh.draw(context, frame.instances);
                    if (!gpuReady) replacements.clear();
                } catch (RuntimeException | LinkageError exception) {
                    releaseGpu();
                    message = "Model rendering failed; normal players restored. " + error(exception);
                    enabled = false;
                    disableModule();
                }
            }, WorldRenderEvents.AFTER_ENTITIES::register);
        BetaSubscriptions.register(ClientLifecycleEvents.CLIENT_STOPPING, "celeste:player_models/stopping",
            ClientLifecycleEvents.ClientStopping.class, client -> shutdown(), ClientLifecycleEvents.CLIENT_STOPPING::register);
        ResourceLoader resources = ResourceLoader.get(ResourceType.CLIENT_RESOURCES);
        BetaSubscriptions.register(resources, "celeste:player_models/reload", SynchronousResourceReloader.class,
            manager -> MinecraftClient.getInstance().execute(PlayerModelsFeature::releaseGpu),
            proxy -> resources.registerReloader(Identifier.of("celeste", "player_models"), proxy));
    }

    @Override public String name() { return "Player Models"; }
    @Override public void onEnable(ModuleContext context) {
        enabled = true;
        update(context);
        if (imported == null && BundledPlayerModel.available()) loadSahur();
    }
    @Override public void onStartTick(ModuleContext context) {
        update(context);
        if (enabled && imported != null && mesh == null) {
            try { mesh = new PlayerMeshRenderer(imported); }
            catch (RuntimeException exception) { enabled = false; disableModule(); message = "Cannot render model: " + error(exception); }
        }
        if (attackedUuid != null) {
            Entity target = context.client().world.getEntityById(attackedId);
            if (context.client().player.age > attackedUntil || target == null || !attackedUuid.equals(target.getUuid())) clearAttack();
            else if (target instanceof LivingEntity living && (living.deathTime > 0 || living.isDead())) {
                if (celebrate && imported != null && stationary(context.client().player)) {
                    var controller = ANIMATIONS.computeIfAbsent(context.client().player.getUuid(), key -> new ModelAnimations(imported));
                    controller.celebrate(context.client().player.age / 20.0, true, ThreadLocalRandom.current());
                }
                clearAttack();
            }
        }
    }
    @Override public void onDisable(ModuleContext context) {
        enabled = false;
        if (loading) { ++generation; loading = false; message = "Import cancelled because Player Models was disabled."; }
        resetWorld(); releaseGpu();
    }
    @Override public void onWorldChanged(ModuleContext context) { resetWorld(); releaseGpu(); }

    private static void update(ModuleContext context) {
        scope = context.mode("Apply to", "Self");
        scale = (float) context.slider("Size", 1);
        celebrate = context.toggle("Kill animations", true);
        secondaryMotion = context.toggle("Secondary motion", true);
        motionStrength = (float) context.slider("Motion strength", .5);
    }

    /** Only these exact extracted world states can suppress a vanilla submission. GUI previews are unaffected. */
    public static void capture(LivingEntity entity, LivingEntityRenderState state) {
        if (RenderCompatibility.isShadowPass() || !active() || !(entity instanceof AbstractClientPlayerEntity player)) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (player.getEntityWorld() != client.world) return;
        int light = client.world.getLightLevel(player.getBlockPos(), client.world.getAmbientDarkness());
        CAPTURED.put(state, new Captured(player.getUuid(), player == client.player,
            player.isAlive() && !player.isRemoved() && !player.isSpectator(), stationary(player),
            .18f + .82f * Math.max(0, Math.min(15, light)) / 15f));
    }

    public static boolean replaceVanilla(LivingEntityRenderState state) {
        return !RenderCompatibility.isShadowPass() && active() && gpuReady && replacements.contains(state);
    }

    public static void recordAttack(MinecraftClient client, Entity target) {
        if (!active() || !celebrate || client.player == null || !(target instanceof LivingEntity living) || !living.isAlive()) return;
        attackedUuid = target.getUuid(); attackedId = target.getId(); attackedUntil = client.player.age + 100;
    }

    public static void importModel(String path) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) { client.execute(() -> importModel(path)); return; }
        if (closed || loading) return;
        final Path selected;
        try {
            String value = path.trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) value = value.substring(1, value.length() - 1);
            selected = Path.of(value).toAbsolutePath().normalize();
            if (!selected.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".glb")) throw new IllegalArgumentException("Select a .glb file");
        } catch (RuntimeException exception) { message = "Invalid model path: " + error(exception); return; }
        loadModel(selected.getFileName().toString(), () -> GlbImporter.load(selected));
    }

    public static void loadSahur() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) { client.execute(PlayerModelsFeature::loadSahur); return; }
        if (!closed && !loading) loadModel("Tung Tung Tung Sahur", BundledPlayerModel::load);
    }

    private static void loadModel(String label, ModelSource source) {
        MinecraftClient client = MinecraftClient.getInstance();
        long request = ++generation;
        loading = true; message = "Loading model…";
        dev.c4lam.celeste.beta.lifecycle.BetaModelImports.submit(() -> {
            try { return source.load(); }
            catch (Exception exception) { throw new java.util.concurrent.CompletionException(exception); }
        }, client::send, (model, failure) -> {
            if (request != generation || closed) return;
            loading = false;
            if (failure != null) { message = "Import failed: " + error(failure); return; }
            PlayerMeshRenderer replacement;
            try { replacement = new PlayerMeshRenderer(model); }
            catch (RuntimeException exception) { message = "Import failed: " + error(exception); return; }
            releaseGpu(); resetWorld();
            imported = model; mesh = replacement;
            message = "Loaded " + label + " · " + model.animations().size() + " animation clips";
            var module = ModuleManager.getInstance().module("Player Models");
            if (module != null) module.setEnabled(true);
        });
    }

    public static String status() { return message; }
    public static int secondaryJointCount() { return mesh == null ? 0 : mesh.secondaryJointCount(); }
    public static boolean importing() { return loading; }
    public static void clearModel() { ++generation; loading = false; imported = null; resetWorld(); releaseGpu(); message = "Model cleared. Normal players restored."; }
    public static void shutdown() { closed = true; enabled = false; clearModel(); }
    public static java.util.concurrent.CompletionStage<Void> importsStopped() {
        return dev.c4lam.celeste.beta.lifecycle.BetaModelImports.drained();
    }
    private static boolean active() { return !closed && enabled && imported != null && CelesteClient.active(); }
    private static boolean stationary(AbstractClientPlayerEntity player) {
        return player.isOnGround() && !player.hasVehicle() && !player.isGliding()
            && player.getVelocity().lengthSquared() < .0025
            && player.getEntityPos().squaredDistanceTo(player.getLerpedPos(0)) < .0004;
    }
    private static void clearAttack() { attackedUuid = null; attackedId = -1; }
    private static void disableModule() {
        var module = ModuleManager.getInstance().module("Player Models");
        if (module != null) module.setEnabled(false);
    }
    private static void resetWorld() { CAPTURED.clear(); replacements.clear(); ANIMATIONS.clear(); clearAttack(); }
    private static void releaseGpu() { gpuReady = false; replacements.clear(); if (mesh != null) { var previous = mesh; mesh = null; previous.close(); } }
    private static String error(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        String text = failure.getMessage();
        return text == null ? failure.getClass().getSimpleName() : text.substring(0, Math.min(180, text.length()));
    }
    private record Captured(UUID uuid, boolean self, boolean visible, boolean stationary, float light) {}
    private record Frame(PlayerMeshRenderer renderer, List<PlayerMeshRenderer.Instance> instances) {}
    @FunctionalInterface private interface ModelSource { GlbModel load() throws java.io.IOException; }
}
