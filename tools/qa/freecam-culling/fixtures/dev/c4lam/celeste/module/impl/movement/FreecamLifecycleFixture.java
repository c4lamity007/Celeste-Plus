package dev.c4lam.celeste.module.impl.movement;

import dev.c4lam.celeste.module.ModuleContext;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

/** Behavioral tests of the actual supplied production class, using narrow MC fixtures. */
public final class FreecamLifecycleFixture {
    private static int passed, failed;

    public static void main(String[] args) {
        run("enable, idle callbacks, and disable restore true without update spam", () -> {
            try (Rig r = new Rig(true)) {
                r.enable();
                off(r.client, "enable must turn culling off");
                r.updates(false);
                for (int i = 0; i < 20; i++) { r.tick(); r.frame(); }
                off(r.client, "idle callbacks must retain culling off");
                r.updates(false);
                r.disable();
                on(r.client, "disable must restore original true");
                same(r.body, r.client.getCameraEntity(), "body camera must be restored");
                r.updates(false, true);
                r.disable(); r.worldChanged(); r.tick(); r.frame();
                r.updates(false, true);
                check(!FreecamFeature.isDetached(r.client), "detached marker must be cleared");
            }
        });
        run("tick and render frame reassert culling after external writes", () -> {
            try (Rig r = new Rig(true)) {
                r.enable();
                r.client.chunkCullingEnabled = true;
                r.tick();
                off(r.client, "tick must turn culling off again");
                r.updates(false, false);
                r.client.chunkCullingEnabled = true;
                r.frame();
                off(r.client, "frame must turn culling off again");
                r.updates(false, false, false);
                r.tick(); r.frame();
                r.updates(false, false, false);
                r.disable(); r.updates(false, false, false, true);
            }
        });
        run("preexisting false survives enable, callbacks, disable and world change", () -> {
            try (Rig r = new Rig(false)) {
                r.enable(); r.tick(); r.frame(); r.disable();
                off(r.client, "disable must preserve original false");
                r.updates();
                r.enable(); r.worldChanged();
                off(r.client, "world change must preserve original false");
                r.updates();
                r.enable();
                r.client.chunkCullingEnabled = true;
                r.disable();
                off(r.client, "saved false must be restored after external true");
                r.updates(false);
            }
        });
        run("disable avoids redundant update when external state already equals saved state", () -> {
            try (Rig r = new Rig(true)) {
                r.enable();
                r.client.chunkCullingEnabled = true;
                r.disable();
                on(r.client, "saved true must remain true");
                r.updates(false);
            }
        });
        run("death at tick cleans up culling and local input control", () -> {
            try (Rig r = new Rig(true)) {
                r.enable(); r.body.setAlive(false); r.tick();
                r.assertEnded(true);
                check(!FreecamFeature.keepsLocalPlayerControl(r.body), "dead body must lose freecam control marker");
                check(!FreecamFeature.blocksPlayerInput(r.body.input), "dead body input must stop being blocked");
                r.updates(false, true);
                r.frame(); r.disable(); r.updates(false, true);
            }
        });
        run("removed body at render frame cleans up", () -> {
            try (Rig r = new Rig(true)) {
                r.enable(); r.body.setRemoved(true); r.frame();
                r.assertEnded(true); r.updates(false, true);
            }
        });
        run("disconnect with no player restores culling", () -> {
            try (Rig r = new Rig(true)) {
                r.enable(); r.client.player = null; r.tick();
                r.assertEnded(true); r.updates(false, true);
            }
        });
        run("disconnect with no world restores culling and camera fallback", () -> {
            try (Rig r = new Rig(true)) {
                r.enable(); r.client.player = null; r.client.world = null; r.frame();
                r.assertEnded(true); r.updates(false, true);
                same(null, r.client.getCameraEntity(), "disconnect must clear detached camera");
            }
        });
        run("world change callback restores saved state once", () -> {
            try (Rig r = new Rig(true)) {
                r.enable();
                r.client.world = new ClientWorld();
                r.client.player = new ClientPlayerEntity(r.client.world);
                r.worldChanged();
                r.assertEnded(true); r.updates(false, true);
                same(r.client.player, r.client.getCameraEntity(), "cross-world previous camera must fall back to player");
                r.worldChanged(); r.tick(); r.frame(); r.updates(false, true);
            }
        });
        run("world mismatch detected during frame restores culling", () -> {
            try (Rig r = new Rig(true)) {
                r.enable(); r.client.world = new ClientWorld(); r.frame();
                r.assertEnded(true); r.updates(false, true);
            }
        });
        run("valid previous camera is restored and external camera is respected", () -> {
            try (Rig r = new Rig(true)) {
                Entity spectator = new Entity(r.client.world);
                r.client.setCameraEntity(spectator);
                r.enable(); r.disable();
                same(spectator, r.client.getCameraEntity(), "previous spectator camera must return");
                on(r.client, "spectator restoration must restore culling");
                r.enable();
                Entity external = new Entity(r.client.world);
                r.client.setCameraEntity(external);
                r.disable();
                same(external, r.client.getCameraEntity(), "disable must preserve camera changed externally");
                on(r.client, "external camera path must still restore culling");
                r.updates(false, true, false, true);
            }
        });
        run("tick and frame recover detached camera without redundant terrain updates", () -> {
            try (Rig r = new Rig(true)) {
                r.enable();
                Entity camera = r.client.getCameraEntity();
                r.client.setCameraEntity(r.body); r.tick();
                same(camera, r.client.getCameraEntity(), "tick must recover detached camera");
                r.client.setCameraEntity(r.body); r.frame();
                same(camera, r.client.getCameraEntity(), "frame must recover detached camera");
                off(r.client, "camera recovery must retain disabled culling");
                r.updates(false);
            }
        });
        run("reenable captures current culling value instead of stale saved state", () -> {
            try (Rig r = new Rig(true)) {
                r.enable(); r.disable();
                r.client.chunkCullingEnabled = false;
                r.enable(); r.disable();
                off(r.client, "second activation must preserve its own initial false");
                r.updates(false, true);
                r.client.chunkCullingEnabled = true;
                r.enable(); r.disable();
                on(r.client, "third activation must preserve its own initial true");
                r.updates(false, true, false, true);
            }
        });
        run("respawn tick creates a new detached camera and transfers input ownership", () -> {
            try (Rig r = new Rig(true)) {
                r.enable();
                Entity oldCamera = r.client.getCameraEntity();
                ClientPlayerEntity respawn = new ClientPlayerEntity(r.client.world);
                respawn.setPosition(new Vec3d(100, 80, -30));
                r.body.setRemoved(true);
                r.client.player = respawn;
                r.tick();
                off(r.client, "respawn camera must keep culling disabled");
                check(FreecamFeature.isDetached(r.client), "respawn must reactivate detached camera");
                check(r.client.getCameraEntity() != oldCamera, "respawn must allocate new camera");
                near(100, r.client.getCameraEntity().getX(), "new camera must begin at respawn position");
                check(FreecamFeature.blocksPlayerInput(respawn.input), "new body's input must be blocked");
                check(!FreecamFeature.blocksPlayerInput(r.body.input), "old body's input must be released");
                r.updates(false, true, false);
                r.disable();
                same(respawn, r.client.getCameraEntity(), "respawn body camera must restore");
                on(r.client, "respawn disable must restore original true");
                r.updates(false, true, false, true);
            }
        });
        run("replacement seen in frame deactivates and supports explicit reenable", () -> {
            try (Rig r = new Rig(true)) {
                r.enable(); r.body.setRemoved(true);
                r.client.player = new ClientPlayerEntity(r.client.world);
                r.frame(); r.assertEnded(true); r.updates(false, true);
                r.enable();
                off(r.client, "reenable after replacement must disable culling");
                check(FreecamFeature.isDetached(r.client), "reenable must detach replacement player");
                r.disable(); r.updates(false, true, false, true);
            }
        });
        run("activation after death captures a fresh player and fresh culling state", () -> {
            try (Rig r = new Rig(true)) {
                r.enable(); r.body.setAlive(false); r.tick();
                on(r.client, "death cleanup must restore true before reenable");
                r.client.player = new ClientPlayerEntity(r.client.world);
                r.client.setCameraEntity(r.client.player);
                r.client.chunkCullingEnabled = false;
                r.enable(); r.frame(); r.disable();
                off(r.client, "post-death activation must retain its original false");
                r.updates(false, true);
            }
        });
        run("competing Freecam instance restores prior ownership before activation", () -> {
            try (Rig r = new Rig(true)) {
                r.enable();
                FreecamFeature replacement = new FreecamFeature();
                try {
                    replacement.onEnable(r.context);
                    off(r.client, "replacement feature must retain detached culling");
                    r.updates(false, true, false);
                    r.disable();
                    off(r.client, "old owner's disable must not overwrite new owner");
                    r.updates(false, true, false);
                } finally { replacement.onDisable(r.context); }
                on(r.client, "new owner's disable must restore original true");
                r.updates(false, true, false, true);
            }
        });
        run("failed activation without player or world leaves culling untouched", () -> {
            try (Rig r = new Rig(true)) {
                r.client.player = null; r.enable(); r.disable();
                on(r.client, "missing player must not acquire culling override"); r.updates();
                r.client.player = r.body; r.client.world = null;
                r.enable(); r.frame(); r.disable();
                on(r.client, "missing world must not acquire culling override"); r.updates();
            }
        });
        run("camera motion, body input isolation, look redirect and interpolation remain intact", () -> {
            try (Rig r = new Rig(true)) {
                r.enable();
                Entity camera = r.client.getCameraEntity();
                check(camera instanceof OtherClientPlayerEntity, "camera must be separate player entity");
                check(camera.noClip, "detached camera must keep noclip");
                same(r.body, r.client.player, "network player must remain body");
                same(r.body, FreecamFeature.renderedCameraFocus(camera), "real body must remain visible");
                check(FreecamFeature.keepsLocalPlayerControl(r.body), "real-origin action control must remain");
                check(FreecamFeature.blocksPlayerInput(r.body.input), "network body input must be blocked");
                check(!FreecamFeature.blocksPlayerInput(new Input()), "unrelated input must not be blocked");
                r.client.options.forwardKey.setPressed(true);
                r.client.options.rightKey.setPressed(true);
                r.client.options.jumpKey.setPressed(true);
                r.context.speed = 100; // Exercises the production speed cap and frame-delta cap.
                Field lastFrame = FreecamFeature.class.getDeclaredField("lastFrameNanos");
                lastFrame.setAccessible(true);
                lastFrame.setLong(r.feature, System.nanoTime() - 1_000_000_000L);
                r.frame();
                double distance = Math.sqrt(Math.pow(camera.getX()-12, 2)
                    + Math.pow(camera.getY()-64, 2) + Math.pow(camera.getZ()+9, 2));
                near(3, distance, "diagonal flight must normalize, cap speed at 30, cap frame at 0.1s");
                check(camera.getX() < 12 && camera.getY() > 64 && camera.getZ() > -9,
                    "forward/right/jump at zero yaw must move detached camera in expected axes");
                near(12, r.body.getX(), "body X must never move");
                near(64, r.body.getY(), "body Y must never move");
                near(-9, r.body.getZ(), "body Z must never move");
                near(camera.getX(), camera.lastRenderX, "camera interpolation position must settle");
                check(FreecamFeature.redirectPlayerLook(r.body, 20, 1000), "body mouse look must redirect");
                near(3, camera.getYaw(), "redirect must preserve vanilla look scale");
                near(90, camera.getPitch(), "redirect must preserve pitch clamp");
                near(0, r.body.getYaw(), "mouse look must not turn network body");
                near(camera.getYaw(), camera.lastYaw, "camera interpolation yaw must settle");
                check(!FreecamFeature.redirectPlayerLook(camera, 1, 1), "camera look must not recurse");
                r.body.setYaw(35); r.body.setPitch(-12); r.body.setHeadYaw(36); r.body.setBodyYaw(34);
                r.tick();
                near(35, r.body.getYaw(), "authoritative body yaw must be retained");
                near(-12, r.body.getPitch(), "authoritative body pitch must be retained");
                r.client.currentScreen = new Object();
                double beforeX = camera.getX(), beforeY = camera.getY(), beforeZ = camera.getZ();
                lastFrame.setLong(r.feature, System.nanoTime() - 1_000_000_000L);
                r.frame();
                near(beforeX, camera.getX(), "screen must suspend flight X");
                near(beforeY, camera.getY(), "screen must suspend flight Y");
                near(beforeZ, camera.getZ(), "screen must suspend flight Z");
                r.disable();
                check(!FreecamFeature.blocksPlayerInput(r.body.input), "disable must restore body input");
                near(35, r.body.getYaw(), "disable must preserve authoritative body yaw");
            }
        });
        System.out.println("RESULT " + passed + " passed, " + failed + " failed; fixture-only, no in-game claim");
        if (failed != 0) System.exit(1);
    }

    private static final class Rig implements AutoCloseable {
        final MinecraftClient client = new MinecraftClient();
        final ClientPlayerEntity body;
        final ModuleContext context = new ModuleContext(client);
        final FreecamFeature feature = new FreecamFeature();
        Rig(boolean initialCulling) {
            client.world = new ClientWorld();
            body = new ClientPlayerEntity(client.world);
            body.setPosition(new Vec3d(12, 64, -9));
            client.player = body;
            client.chunkCullingEnabled = initialCulling;
            client.setCameraEntity(body);
        }
        void enable() { feature.onEnable(context); }
        void tick() { feature.onStartTick(context); }
        void frame() { feature.onRenderFrame(context); }
        void disable() { feature.onDisable(context); }
        void worldChanged() { feature.onWorldChanged(context); }
        void updates(Boolean... values) {
            check(client.worldRenderer.updates.equals(List.of(values)),
                "terrain update states expected " + List.of(values) + " but got " + client.worldRenderer.updates);
        }
        void assertEnded(boolean culling) {
            check(client.chunkCullingEnabled == culling, "ended lifecycle must restore initial culling " + culling);
            check(!FreecamFeature.isDetached(client), "ended lifecycle must clear detached marker");
        }
        public void close() { disable(); }
    }
    @FunctionalInterface private interface Test { void run() throws Exception; }
    private static void run(String name, Test test) {
        try { test.run(); passed++; System.out.println("PASS " + name); }
        catch (Throwable problem) { failed++; System.out.println("FAIL " + name + ": " + problem); }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void same(Object expected, Object actual, String message) {
        check(expected == actual, message);
    }
    private static void near(double expected, double actual, String message) {
        check(Math.abs(expected - actual) < 1.0E-6, message + "; expected " + expected + ", got " + actual);
    }
    private static void off(MinecraftClient client, String message) { check(!client.chunkCullingEnabled, message); }
    private static void on(MinecraftClient client, String message) { check(client.chunkCullingEnabled, message); }
}
