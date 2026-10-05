package dev.c4lam.celeste.module.impl.movement;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** A detached render camera which never replaces or moves the network player. */
public final class FreecamFeature implements ModuleFeature {
    private static final double DEFAULT_SPEED = 10.0D;
    private static final double MAX_FRAME_SECONDS = 0.10D;
    private static FreecamFeature active;

    private ClientPlayerEntity body;
    private OtherClientPlayerEntity camera;
    private Entity previousCamera;
    private float bodyYaw;
    private float bodyPitch;
    private float bodyHeadYaw;
    private float bodyBodyYaw;
    private long lastFrameNanos;
    private MinecraftClient cullingClient;
    private boolean previousChunkCulling;

    @Override
    public String name() {
        return "Freecam";
    }

    @Override
    public void onEnable(ModuleContext context) {
        activate(context.client());
    }

    @Override
    public void onStartTick(ModuleContext context) {
        MinecraftClient client = context.client();
        if (body != null && client.player != body && client.player != null && client.world != null
            && client.player.isAlive() && !client.player.isRemoved()) {
            deactivate(client);
            activate(client);
        }
        if (!valid(client)) {
            deactivate(client);
            return;
        }
        disableChunkCulling(client);
        adoptExternalBodyLook();
        restoreBodyLook();
        if (client.getCameraEntity() != camera) {
            client.setCameraEntity(camera);
        }
    }

    @Override
    public void onRenderFrame(ModuleContext context) {
        MinecraftClient client = context.client();
        if (!valid(client)) {
            deactivate(client);
            return;
        }
        disableChunkCulling(client);
        adoptExternalBodyLook();
        restoreBodyLook();
        moveCamera(client, context.slider("Speed", DEFAULT_SPEED));
        if (client.getCameraEntity() != camera) {
            client.setCameraEntity(camera);
        }
    }

    @Override
    public void onDisable(ModuleContext context) {
        deactivate(context.client());
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        deactivate(context.client());
    }

    @Override
    public boolean canRun(MinecraftClient client) {
        return ModuleFeature.super.canRun(client) && client.player.isAlive() && !client.player.isRemoved();
    }

    /** Action targeting uses this instead of treating an ordinary spectator camera as Freecam. */
    public static boolean isDetached(MinecraftClient client) {
        FreecamFeature current = active;
        return current != null && current.valid(client) && client.getCameraEntity() == current.camera;
    }

    /**
     * Vanilla gates movement/look packets behind {@code ClientPlayerEntity.isCamera()}.
     * Keep that packet route live for real-origin actions; the keyboard hook separately
     * neutralizes movement before it can drive the body.
     */
    public static boolean keepsLocalPlayerControl(ClientPlayerEntity player) {
        FreecamFeature current = active;
        return current != null && player == current.body
            && current.valid(MinecraftClient.getInstance());
    }

    /** True only for the real network player's Input while its camera is detached. */
    public static boolean blocksPlayerInput(Input input) {
        MinecraftClient client = MinecraftClient.getInstance();
        FreecamFeature current = active;
        return current != null && current.valid(client)
            && current.body.input == input && client.getCameraEntity() == current.camera;
    }

    /**
     * Minecraft 1.21.11 otherwise omits the local ClientPlayerEntity whenever
     * a Camera is focused on another entity. Substitute the real body only at
     * that render comparison; the detached entity remains the actual camera.
     */
    public static Entity renderedCameraFocus(Entity vanillaFocus) {
        FreecamFeature current = active;
        if (current == null || vanillaFocus != current.camera
            || !current.valid(MinecraftClient.getInstance())) {
            return vanillaFocus;
        }
        return current.body;
    }

    /** Receives the exact post-sensitivity vanilla mouse deltas before body pitch clamping. */
    public static boolean redirectPlayerLook(Entity entity, double cursorDeltaX, double cursorDeltaY) {
        MinecraftClient client = MinecraftClient.getInstance();
        FreecamFeature current = active;
        if (current == null || !current.valid(client) || entity != current.body) {
            return false;
        }
        current.adoptExternalBodyLook();
        // Calling vanilla on the detached entity retains its exact 0.15 scale,
        // yaw wrapping and +/-90 pitch clamp. The hook only intercepts body.
        current.camera.changeLookDirection(cursorDeltaX, cursorDeltaY);
        current.camera.setHeadYaw(current.camera.getYaw());
        current.camera.setBodyYaw(current.camera.getYaw());
        settleCameraLook(current.camera);
        current.restoreBodyLook();
        return true;
    }

    private void activate(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            return;
        }
        if (active != null && active != this) {
            active.deactivate(client);
        }
        body = client.player;
        previousCamera = client.getCameraEntity();
        bodyYaw = body.getYaw();
        bodyPitch = body.getPitch();
        bodyHeadYaw = body.getHeadYaw();
        bodyBodyYaw = body.getBodyYaw();
        camera = new OtherClientPlayerEntity(client.world, body.getGameProfile());
        camera.noClip = true;
        camera.setPose(body.getPose());
        setCameraTransform(position(body), bodyYaw, bodyPitch);
        active = this;
        lastFrameNanos = System.nanoTime();
        restoreBodyLook();
        disableChunkCulling(client);
        client.setCameraEntity(camera);
    }

    /**
     * Both vanilla and Sodium honor this flag for section-to-section occlusion.
     * A detached camera can see enclosed caves without entering their visibility
     * graph first. Keep ordinary frustum and render-distance limits intact.
     */
    private void disableChunkCulling(MinecraftClient client) {
        if (cullingClient != client) {
            restoreChunkCulling();
            cullingClient = client;
            previousChunkCulling = client.chunkCullingEnabled;
        }
        setChunkCulling(client, false);
    }

    private void restoreChunkCulling() {
        if (cullingClient == null) {
            return;
        }
        MinecraftClient client = cullingClient;
        cullingClient = null;
        setChunkCulling(client, previousChunkCulling);
    }

    private static void setChunkCulling(MinecraftClient client, boolean enabled) {
        if (client.chunkCullingEnabled != enabled) {
            client.chunkCullingEnabled = enabled;
            // Invalidate visibility only when it changes, not every frame.
            client.worldRenderer.scheduleTerrainUpdate();
        }
    }

    private boolean valid(MinecraftClient client) {
        return active == this && body != null && camera != null
            && client.player == body && body.isAlive() && !body.isRemoved()
            && client.world != null && camera.getEntityWorld() == client.world;
    }

    private void moveCamera(MinecraftClient client, double configuredSpeed) {
        long now = System.nanoTime();
        double seconds = Math.min(MAX_FRAME_SECONDS, Math.max(0.0D, (now - lastFrameNanos) / 1_000_000_000.0D));
        lastFrameNanos = now;
        if (seconds == 0.0D || client.currentScreen != null) {
            settleCameraLook(camera);
            return;
        }

        double forward = pressed(client.options.forwardKey) - pressed(client.options.backKey);
        double sideways = pressed(client.options.rightKey) - pressed(client.options.leftKey);
        double vertical = pressed(client.options.jumpKey) - pressed(client.options.sneakKey);
        Vec3d motion = localMotion(camera.getYaw(), forward, sideways, vertical);
        if (motion.lengthSquared() < 1.0E-12D) {
            settleCameraLook(camera);
            return;
        }
        double speed = MathHelper.clamp(configuredSpeed, 1.0D, 30.0D);
        setCameraTransform(position(camera).add(motion.multiply(speed * seconds)), camera.getYaw(), camera.getPitch());
    }

    static void settleCameraLook(OtherClientPlayerEntity camera) {
        // This camera is deliberately not added to the world, so it has no entity
        // tick to advance its previous look. Keep the interpolation endpoints
        // together even when no movement key is held; otherwise a stale previous
        // look can be re-interpolated every tick and appear as idle shaking.
        camera.lastYaw = camera.getYaw();
        camera.lastPitch = camera.getPitch();
        camera.lastHeadYaw = camera.getHeadYaw();
        camera.lastBodyYaw = camera.getBodyYaw();
    }

    static Vec3d localMotion(float yaw, double forward, double sideways, double vertical) {
        double radians = Math.toRadians(yaw);
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);
        Vec3d result = new Vec3d(
            -sin * forward - cos * sideways,
            vertical,
            cos * forward - sin * sideways
        );
        return result.lengthSquared() > 1.0D ? result.normalize() : result;
    }

    private static int pressed(net.minecraft.client.option.KeyBinding binding) {
        return binding.isPressed() ? 1 : 0;
    }

    private static Vec3d position(Entity entity) {
        return new Vec3d(entity.getX(), entity.getY(), entity.getZ());
    }

    private void setCameraTransform(Vec3d position, float yaw, float pitch) {
        camera.setPosition(position);
        camera.lastX = position.x;
        camera.lastY = position.y;
        camera.lastZ = position.z;
        camera.lastRenderX = position.x;
        camera.lastRenderY = position.y;
        camera.lastRenderZ = position.z;
        camera.setYaw(yaw);
        camera.setPitch(pitch);
        camera.setHeadYaw(yaw);
        camera.setBodyYaw(yaw);
        camera.lastYaw = yaw;
        camera.lastPitch = pitch;
        camera.lastHeadYaw = yaw;
        camera.lastBodyYaw = yaw;
    }

    private void restoreBodyLook() {
        if (body == null) {
            return;
        }
        body.setYaw(bodyYaw);
        body.setPitch(bodyPitch);
        body.setHeadYaw(bodyHeadYaw);
        body.setBodyYaw(bodyBodyYaw);
        body.lastYaw = bodyYaw;
        body.lastPitch = bodyPitch;
        body.lastHeadYaw = bodyHeadYaw;
        body.lastBodyYaw = bodyBodyYaw;
    }

    private void adoptExternalBodyLook() {
        if (body == null) {
            return;
        }
        // Mouse deltas are intercepted before they touch the body, so a real
        // yaw/pitch change here came from vanilla/server state (for example a
        // teleport). Keep that authoritative look instead of overwriting it.
        if (Math.abs(MathHelper.wrapDegrees(body.getYaw() - bodyYaw)) > 1.0E-3F
            || Math.abs(body.getPitch() - bodyPitch) > 1.0E-3F) {
            bodyYaw = body.getYaw();
            bodyPitch = body.getPitch();
            bodyHeadYaw = body.getHeadYaw();
            bodyBodyYaw = body.getBodyYaw();
        }
    }

    private void deactivate(MinecraftClient client) {
        adoptExternalBodyLook();
        restoreBodyLook();
        if (client != null && camera != null && client.getCameraEntity() == camera) {
            Entity restore = previousCamera;
            if (restore == null || restore.getEntityWorld() != client.world || restore.isRemoved()) {
                restore = client.player;
            }
            client.setCameraEntity(restore);
        }
        if (active == this) {
            active = null;
        }
        restoreChunkCulling();
        body = null;
        camera = null;
        previousCamera = null;
        lastFrameNanos = 0L;
    }
}
