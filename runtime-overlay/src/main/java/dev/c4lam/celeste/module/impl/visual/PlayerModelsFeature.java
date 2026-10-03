package dev.c4lam.celeste.module.impl.visual;

import dev.c4lam.celeste.CelesteClient;
import dev.c4lam.celeste.gui.PlayerModelSelection;
import dev.c4lam.celeste.compat.RenderCompatibility;
import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.ModuleManager;
import dev.c4lam.celeste.render.model.GlbImporter;
import dev.c4lam.celeste.render.model.BundledPlayerModel;
import dev.c4lam.celeste.render.model.GlbModel;
import dev.c4lam.celeste.render.model.ModelAnimations;
import dev.c4lam.celeste.render.model.ModelKillTracker;
import dev.c4lam.celeste.render.model.PlayerModelMotion;
import dev.c4lam.celeste.render.model.PlayerMeshRenderer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import dev.c4lam.celeste.module.delivery.ModuleFrame;
import dev.c4lam.celeste.module.delivery.ModuleScope;
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
    private static final ModuleFrame<Frame> FRAME = ModuleFrame.create();
    private static final ModuleScope MODULE_SCOPE = ModuleScope.current();
    private static final Map<LivingEntityRenderState, Captured> CAPTURED = new WeakHashMap<>();
    private static final Map<UUID, ModelAnimations> ANIMATIONS = new HashMap<>();
    private static Set<LivingEntityRenderState> replacements = Collections.newSetFromMap(new IdentityHashMap<>());
    private static PlayerMeshRenderer mesh;
    private static GlbModel imported;
    private static boolean enabled, closed, loading, gpuReady;
    private static long generation, selectedRequest = -1;
    private static String message = "Choose a self-contained .glb model with embedded textures.";
    private static String scope = "Self";
    private static float scale = 1;
    private static boolean celebrate = true, secondaryMotion = true;
    private static float motionStrength = .5f;
    private static final ModelKillTracker<LivingEntity> KILLS = new ModelKillTracker<>();

    public PlayerModelsFeature() {
        if (MODULE_SCOPE != null) MODULE_SCOPE.releaseAfterCallbacks(PlayerModelsFeature::shutdown);
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
                    ModelAnimations.Playback playback = controller.frame(state.age / 20.0, capture.stationary, celebrate, capture.walkRate);
                    instances.add(new PlayerMeshRenderer.Instance(new Vec3d(state.x, state.y, state.z), state.bodyYaw,
                        scale, capture.light, playback, capture.uuid, state.age / 20.0, secondaryMotion, motionStrength));
                    if (gpuReady) replacements.add(state);
                    if (instances.size() >= max) break;
                }
                FRAME.set(context.worldState(), new Frame(mesh, List.copyOf(instances)));
                if (ANIMATIONS.size() > 128) ANIMATIONS.keySet().removeIf(uuid -> context.world().getPlayerByUuid(uuid) == null);
            }, WorldRenderEvents.END_EXTRACTION::register);
        BetaSubscriptions.register(WorldRenderEvents.AFTER_ENTITIES, "celeste:player_models/draw",
            WorldRenderEvents.AfterEntities.class, context -> {
                if (RenderCompatibility.isShadowPass() || !active() || context.worldState() == null) return;
                Frame frame = FRAME.get(context.worldState());
                if (frame == null || frame.renderer != mesh || frame.instances.isEmpty()) return;
                try {
                    gpuReady = mesh.draw(context, frame.instances);
                    if (!gpuReady) replacements.clear();
                } catch (RuntimeException | LinkageError exception) {
                    releaseGpu();
                    setMessage("Model rendering failed; normal players restored. " + error(exception));
                    enabled = false;
                    disableModule();
                }
            }, WorldRenderEvents.AFTER_ENTITIES::register);
        BetaSubscriptions.register(ClientLifecycleEvents.CLIENT_STOPPING, "celeste:player_models/stopping",
            ClientLifecycleEvents.ClientStopping.class, client -> shutdown(), ClientLifecycleEvents.CLIENT_STOPPING::register);
        ResourceLoader resources = ResourceLoader.get(ResourceType.CLIENT_RESOURCES);
        BetaSubscriptions.register(resources, "celeste:player_models/reload", SynchronousResourceReloader.class,
            manager -> {
                MinecraftClient client = MinecraftClient.getInstance();
                if (MODULE_SCOPE == null) client.execute(PlayerModelsFeature::releaseGpu);
                else MODULE_SCOPE.execute(client::execute, PlayerModelsFeature::releaseGpu);
            },
            proxy -> resources.registerReloader(Identifier.of("celeste", "player_models"), proxy));
    }

    @Override public String name() { return "Player Models"; }
    @Override public void onEnable(ModuleContext context) {
        enabled = true;
        update(context);
        if (PlayerModelSelection.selected() == null && BundledPlayerModel.available()) PlayerModelSelection.loadSahur();
        consumeSelection();
    }
    @Override public void onStartTick(ModuleContext context) {
        update(context);
        consumeSelection();
        MinecraftClient client = context.client();
        if (client.player == null || client.world == null || !client.player.isAlive()) {
            resetWorld();
            return;
        }
        if (enabled && imported != null && mesh == null) {
            try { mesh = new PlayerMeshRenderer(imported); }
            catch (RuntimeException exception) { enabled = false; disableModule(); setMessage("Cannot render model: " + error(exception)); }
        }
        if (!celebrate) KILLS.clear();
        else if (KILLS.confirmedDeath(client.player.age, target -> target.getEntityWorld() == client.world,
            target -> target.deathTime > 0 || target.isDead()) && imported != null) {
            var controller = ANIMATIONS.computeIfAbsent(client.player.getUuid(), key -> new ModelAnimations(imported));
            controller.celebrate(client.player.age / 20.0, ThreadLocalRandom.current());
        }
    }
    @Override public void onDisable(ModuleContext context) {
        enabled = false;
        if (loading) { ++generation; loading = false; setMessage("Import cancelled because Player Models was disabled."); }
        selectedRequest = -1;
        resetWorld();
        if (MODULE_SCOPE == null) releaseGpu();
        PlayerModelSelection.status(message, false);
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
        Vec3d moved = player.getEntityPos().subtract(player.getLerpedPos(0));
        CAPTURED.put(state, new Captured(player.getUuid(), player == client.player,
            player.isAlive() && !player.isRemoved() && !player.isSpectator(), stationary(player),
            .18f + .82f * Math.max(0, Math.min(15, light)) / 15f,
            PlayerModelMotion.walkRate(player.isOnGround(), player.hasVehicle(), player.isGliding(), moved.x, moved.z, state.bodyYaw)));
    }

    public static boolean replaceVanilla(LivingEntityRenderState state) {
        return !RenderCompatibility.isShadowPass() && active() && gpuReady && replacements.contains(state);
    }

    public static void recordAttack(MinecraftClient client, Entity target) {
        if (!active() || !celebrate || client.player == null || !(target instanceof LivingEntity living) || !living.isAlive()) return;
        KILLS.record(living, client.player.age);
    }

    public static void importModel(String path) { PlayerModelSelection.importModel(path); }
    public static void loadSahur() { PlayerModelSelection.loadSahur(); }

    private static void consumeSelection() {
        var request = PlayerModelSelection.selected();
        if (!enabled || closed || loading || request == null || request.version() == selectedRequest) return;
        selectedRequest = request.version();
        if (request.bundled()) loadModel("Tung Tung Tung Sahur", BundledPlayerModel::load);
        else {
            try {
                Path selected = Path.of(request.path());
                loadModel(selected.getFileName().toString(), () -> GlbImporter.load(selected));
            } catch (RuntimeException failure) { setMessage("Invalid model path: " + error(failure)); }
        }
    }

    private static void loadModel(String label, ModelSource source) {
        MinecraftClient client = MinecraftClient.getInstance();
        long request = ++generation;
        loading = true; setMessage("Loading model…");
        dev.c4lam.celeste.beta.lifecycle.BetaModelImports.submit(() -> {
            try { return source.load(); }
            catch (Exception exception) { throw new java.util.concurrent.CompletionException(exception); }
        }, client::send, (model, failure) -> {
            if (request != generation || closed) return;
            loading = false;
            if (failure != null) { setMessage("Import failed: " + error(failure)); return; }
            PlayerMeshRenderer replacement;
            try { replacement = new PlayerMeshRenderer(model); }
            catch (RuntimeException exception) { setMessage("Import failed: " + error(exception)); return; }
            releaseGpu(); resetWorld();
            imported = model; mesh = replacement;
            setMessage("Loaded " + label + " · " + model.animations().size() + " animation clips");
            var module = ModuleManager.getInstance().module("Player Models");
            if (module != null) module.setEnabled(true);
        });
    }

    private static void setMessage(String value) { message = value; PlayerModelSelection.status(value, loading); }
    public static String status() { return message; }
    public static int secondaryJointCount() { return mesh == null ? 0 : mesh.secondaryJointCount(); }
    public static boolean importing() { return loading; }
    public static void clearModel() { ++generation; loading = false; imported = null; resetWorld(); releaseGpu(); setMessage("Model cleared. Normal players restored."); }
    public static void shutdown() { closed = true; enabled = false; clearModel(); }
    public static java.util.concurrent.CompletionStage<Void> importsStopped() {
        return dev.c4lam.celeste.beta.lifecycle.BetaModelImports.drained();
    }
    private static boolean active() { return !closed && enabled && imported != null && CelesteClient.active(); }
    private static boolean stationary(AbstractClientPlayerEntity player) {
        Vec3d moved = player.getEntityPos().subtract(player.getLerpedPos(0));
        return PlayerModelMotion.stationary(player.isOnGround(), player.hasVehicle(), player.isGliding(), moved.x, moved.y, moved.z);
    }
    private static void disableModule() {
        var module = ModuleManager.getInstance().module("Player Models");
        if (module != null) module.setEnabled(false);
    }
    private static void resetWorld() { FRAME.clear(); CAPTURED.clear(); replacements.clear(); ANIMATIONS.clear(); KILLS.clear(); }
    private static void releaseGpu() { gpuReady = false; replacements.clear(); if (mesh != null) { var previous = mesh; mesh = null; previous.close(); } }
    private static String error(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        String text = failure.getMessage();
        return text == null ? failure.getClass().getSimpleName() : text.substring(0, Math.min(180, text.length()));
    }
    private record Captured(UUID uuid, boolean self, boolean visible, boolean stationary, float light, double walkRate) {}
    private record Frame(PlayerMeshRenderer renderer, List<PlayerMeshRenderer.Instance> instances) {}
    @FunctionalInterface private interface ModelSource { GlbModel load() throws java.io.IOException; }
}
