package dev.c4lam.celeste.module.impl.visual.expansion;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.c4lam.celeste.auth.AuthBootstrap;
import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;
import dev.c4lam.celeste.compat.RenderCompatibility;
import dev.c4lam.celeste.rotation.RotationManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.SynchronousResourceReloader;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;

import java.util.List;
import java.util.OptionalInt;

/** Short clear-space lenses composed from a private scene copy after entities and fog. */
public final class HitDisplacementRenderer {
    private static final Logger LOGGER = dev.c4lam.celeste.compat.RuntimeLogging.getLogger("Celeste/HitDisplacement");
    private static final RenderPipeline PIPELINE = dev.c4lam.celeste.compat.BetaRenderPipelines.register(
        RenderPipeline.builder().withLocation(id("pipeline/hit_displacement"))
            .withVertexShader(id("core/hit_displacement")).withFragmentShader(id("core/hit_displacement"))
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER).withSampler("SceneColor")
            .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
            .withoutBlend().withCull(false).withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false).withColorWrite(true, false).build());
    private static final HitDisplacementWorld ANIMATION = new HitDisplacementWorld();
    private static final Identifier PHASE = id("hit_displacement/after_world");
    private static GpuTexture scene;
    private static GpuTextureView sceneView;
    private static Object world, player;
    private static boolean initialized, warned;

    private HitDisplacementRenderer() { }

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        // END_MAIN is after terrain/entities but before first-person hands and HUD.
        // Fog must be included in the copied scene; labels remain crisp afterward.
        WorldRenderEvents.END_MAIN.addPhaseOrdering(Event.DEFAULT_PHASE, PHASE);
        WorldRenderEvents.END_MAIN.addPhaseOrdering(id("world_overlays/after_fog"), PHASE);
        WorldRenderEvents.END_MAIN.addPhaseOrdering(PHASE, id("sky_lyrics/after_fog"));
        BetaSubscriptions.register(WorldRenderEvents.END_MAIN, "celeste:hit_displacement/draw",
            WorldRenderEvents.EndMain.class, HitDisplacementRenderer::draw,
            proxy -> WorldRenderEvents.END_MAIN.register(PHASE, proxy));
        BetaSubscriptions.register(ClientLifecycleEvents.CLIENT_STOPPING, "celeste:hit_displacement/stopping",
            ClientLifecycleEvents.ClientStopping.class, client -> reset(), ClientLifecycleEvents.CLIENT_STOPPING::register);
        BetaSubscriptions.register(ClientPlayConnectionEvents.DISCONNECT, "celeste:hit_displacement/disconnect",
            ClientPlayConnectionEvents.Disconnect.class, (handler, client) -> reset(), ClientPlayConnectionEvents.DISCONNECT::register);
        ResourceLoader resources = ResourceLoader.get(ResourceType.CLIENT_RESOURCES);
        BetaSubscriptions.register(resources, "celeste:hit_displacement/reload", SynchronousResourceReloader.class,
            manager -> reset(), proxy -> resources.registerReloader(dev.c4lam.celeste.module.delivery.ModuleResourceNames.reloader(id("hit_displacement")), proxy));
    }

    public static synchronized void pulse(MinecraftClient client, int rgb, double size) {
        if (client != null && client.crosshairTarget instanceof EntityHitResult hit
            && hit.getEntity() instanceof LivingEntity target) pulse(client, target, rgb, size);
    }

    public static synchronized void pulse(MinecraftClient client, LivingEntity target, int rgb, double size) {
        if (!allowed(client) || target == null || target.getEntityWorld() != client.world
            || !target.isAlive() || target.isRemoved()) return;
        Vec3d eye = client.player.getEyePos();
        RotationManager rotation = RotationManager.getInstance();
        Vec3d direction = Vec3d.fromPolar(rotation.serverPitchOr(client.player.getPitch()),
            rotation.serverYawOr(client.player.getYaw()));
        var box = target.getBoundingBox();
        var hit = client.crosshairTarget instanceof EntityHitResult entityHit && entityHit.getEntity() == target
            ? point(entityHit.getPos()) : null;
        var point = HitDisplacementWorld.attackImpact(point(eye), point(direction),
            new HitDisplacementWorld.Point(box.minX, box.minY, box.minZ),
            new HitDisplacementWorld.Point(box.maxX, box.maxY, box.maxZ), hit, rotation.isActive());
        if (point == null) return;
        enqueue(client, new HitDisplacementWorld.Anchor(target.getId(),
            point.subtract(point(target.getEntityPos())), point, target.getUuid()), rgb, size);
    }

    private static void enqueue(MinecraftClient client, HitDisplacementWorld.Anchor anchor, int rgb, double size) {
        if (!allowed(client)) return;
        if (world != client.world || player != client.player) reset();
        world = client.world;
        player = client.player;
        ANIMATION.pulse(System.nanoTime(), anchor, rgb, size);
    }

    private static HitDisplacementWorld.Point point(Vec3d point) {
        return new HitDisplacementWorld.Point(point.x, point.y, point.z);
    }

    private static HitDisplacementWorld.Point viewPosition(MinecraftClient client, WorldRenderContext context,
                                                           HitDisplacementWorld.Anchor anchor) {
        var camera = client.gameRenderer.getCamera();
        if (camera == null || !camera.isReady()) return null;
        var target = client.world.getEntityById(anchor.entityId());
        var at = HitDisplacementWorld.follow(anchor, target == null ? null : target.getUuid(),
            target != null && !target.isRemoved() ? point(target.getLerpedPos(camera.getLastTickProgress())) : null);
        Vec3d eye = context.worldState().cameraRenderState.pos;
        return HitDisplacementProjection.view(at, point(eye), context.matrices().peek().getPositionMatrix(),
            RenderSystem.getModelViewMatrix());
    }

    private static boolean allowed(MinecraftClient client) {
        return client != null && client.world != null && client.player != null && !client.player.isSpectator()
            && client.player.isAlive() && client.currentScreen == null && !client.isPaused()
            && !RenderCompatibility.isShaderPackActive() && !RenderCompatibility.isShadowPass()
            && AuthBootstrap.authorized();
    }

    private static synchronized void draw(WorldRenderContext context) {
        // A shadow pass is not a gameplay context transition; leave queued pulses alone.
        if (RenderCompatibility.isShadowPass()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (!allowed(client) || world != client.world || player != client.player) {
            if (world != null || ANIMATION.size() > 0 || scene != null) reset();
            return;
        }
        if (context == null || context.worldState() == null || context.worldState().cameraRenderState == null
            || context.worldState().cameraRenderState.pos == null || context.matrices() == null
            || RenderSystem.getProjectionMatrixBuffer() == null) return;
        List<HitDisplacementWorld.WorldFrame> pulses = ANIMATION.sampleWorld(System.nanoTime(),
            anchor -> viewPosition(client, context, anchor));
        if (pulses.isEmpty()) {
            if (scene != null) releaseResources();
            return;
        }
        if (context == null || context.worldState() == null) return;
        RenderSystem.assertOnRenderThread();
        var main = client.getFramebuffer();
        if (main == null) return;
        GpuTextureView target = RenderSystem.outputColorTextureOverride != null
            ? RenderSystem.outputColorTextureOverride : main.getColorAttachmentView();
        if (!usable(target) || target.baseMipLevel() != 0 || target.texture().getDepthOrLayers() != 1
            || (target.texture().usage() & GpuTexture.USAGE_COPY_SRC) == 0) return;
        try {
            if (!RenderSystem.getDevice().precompilePipeline(PIPELINE).isValid()) return;
            ensureScene(target);
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            // The active color attachment is never sampled. Every pulse uses the
            // same pristine scene so repeated hits cannot recursively blur it.
            encoder.copyTextureToTexture(target.texture(), scene, 0, 0, 0, 0, 0,
                target.getWidth(0), target.getHeight(0));
            Matrix4f lenses = new Matrix4f().zero(), colors = new Matrix4f().zero();
            Vector4f strengths = new Vector4f();
            for (int i = 0; i < pulses.size(); i++) {
                var pulse = pulses.get(i);
                var point = pulse.position();
                lenses.setColumn(i, new Vector4f((float) point.x(), (float) point.y(), (float) point.z(), pulse.radius()));
                strengths.setComponent(i, pulse.strength());
                int rgb = pulse.color();
                colors.setColumn(i, new Vector4f((rgb >> 16 & 255) / 255F, (rgb >> 8 & 255) / 255F,
                    (rgb & 255) / 255F, pulse.release()));
            }
            var transforms = RenderSystem.getDynamicUniforms().write(lenses, strengths, new Vector3f(), colors);
            // No depth attachment, depth writes or destination-alpha writes.
            try (RenderPass pass = encoder.createRenderPass(() -> "Celeste hit displacement", target, OptionalInt.empty())) {
                pass.setPipeline(PIPELINE);
                pass.setUniform("DynamicTransforms", transforms);
                pass.setUniform("Projection", RenderSystem.getProjectionMatrixBuffer());
                pass.bindTexture("SceneColor", sceneView, RenderSystem.getSamplerCache().get(FilterMode.LINEAR));
                pass.draw(0, 3);
            }
            warned = false;
        } catch (RuntimeException | LinkageError failure) {
            if (!warned) { LOGGER.warn("Hit displacement unavailable; preserving the world render", failure); warned = true; }
            releaseResources();
        }
    }

    private static boolean usable(GpuTextureView view) {
        return view != null && !view.isClosed() && !view.texture().isClosed()
            && view.getWidth(0) > 0 && view.getHeight(0) > 0;
    }

    private static void ensureScene(GpuTextureView source) {
        if (usable(sceneView) && scene.getWidth(0) == source.getWidth(0)
            && scene.getHeight(0) == source.getHeight(0) && scene.getFormat() == source.texture().getFormat()) return;
        releaseResources();
        scene = RenderSystem.getDevice().createTexture("Celeste hit displacement scene",
            GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
            source.texture().getFormat(), source.getWidth(0), source.getHeight(0), 1, 1);
        sceneView = RenderSystem.getDevice().createTextureView(scene);
    }

    /** Disable, option changes, disconnect, reload and payload unload clear all state. */
    public static synchronized void reset() {
        ANIMATION.reset();
        world = null;
        player = null;
        if (RenderSystem.isOnRenderThread()) releaseResources();
        else dev.c4lam.celeste.module.delivery.ModuleScope.executeCurrent(MinecraftClient.getInstance()::execute, HitDisplacementRenderer::releaseResources);
    }

    public static synchronized void releaseResources() {
        RenderSystem.assertOnRenderThread();
        GpuTextureView oldView = sceneView;
        GpuTexture oldScene = scene;
        sceneView = null;
        scene = null;
        try { if (oldView != null && !oldView.isClosed()) oldView.close(); }
        finally { if (oldScene != null && !oldScene.isClosed()) oldScene.close(); }
    }

    private static Identifier id(String path) { return Identifier.of("celeste", path); }
}
