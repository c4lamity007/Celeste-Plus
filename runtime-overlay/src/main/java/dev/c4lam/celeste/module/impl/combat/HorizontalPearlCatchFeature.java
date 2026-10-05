package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.action.GameplayFoundation;
import dev.c4lam.celeste.action.VanillaInputGuard;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.rotation.MoveFixMode;
import dev.c4lam.celeste.rotation.ElytraSilentAim;
import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.rotation.RotationTask;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.AbstractWindChargeEntity;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

import static dev.c4lam.celeste.module.impl.combat.HorizontalPearlCatchHorizontalPearlTrajectory.*;

/** Two ordinary item uses, with a deterministic trajectory solve between them. */
public final class HorizontalPearlCatchFeature implements ModuleFeature {
    private static final int PRIORITY = 235;
    private enum Stage { IDLE, PEARL, CHARGE, RESTORE }
    private Stage stage = Stage.IDLE;
    private ClientPlayerEntity player;
    private ClientWorld world;
    private boolean keyDown, silentAim, silentSwap, returnSlot, glidingSequence;
    private int originalSlot = -1, visibleSlot = -1;
    private long startedTick, pearlTick, finishedTick, frameNanos;
    private State pearl;
    private Rotation aim, pearlAim;
    private Solution solution;

    @Override public String name() { return "Horizontal Pearl Catch"; }

    @Override public void onEnable(ModuleContext context) {
        clear(context.client(), false);
        keyDown = actionDown(context);
    }

    @Override public void onStartTick(ModuleContext context) {
        MinecraftClient client = context.client();
        long tick = ActionScheduler.getInstance().tickNumber();
        boolean down = actionDown(context), pressed = down && !keyDown;
        keyDown = down;
        if (!ready(client)) { clear(client, true); return; }
        if (stage != Stage.IDLE && (client.player != player || client.world != world)) {
            clear(client, false);
            return;
        }
        if (stage != Stage.IDLE && player.isGliding() != glidingSequence) {
            clear(client, true);
            return; // Changing pose changes eye height and travel; retry with a fresh pearl.
        }
        if (stage != Stage.IDLE && client.player.getInventory().getSelectedSlot() != visibleSlot) {
            clear(client, false); // A genuine scroll/hotbar key always wins, including silent mode.
            return;
        }
        if (stage == Stage.RESTORE && tick > finishedTick) { clear(client, true); return; }
        if (stage == Stage.IDLE && pressed) begin(context, tick);
        if (stage == Stage.IDLE || stage == Stage.RESTORE) return;
        if (VanillaInputGuard.hasInteractionConflict(client) || slotsContended()) {
            clear(client, true);
            return;
        }
        if (stage == Stage.PEARL && (glidingSequence
            ? !ElytraSilentAim.withinAimWindow(startedTick, tick) : tick - startedTick > 6)) {
            clear(client, true);
            return;
        }
        if (glidingSequence && (!ElytraSilentAim.canAim(client) || usableSlot(client, stage == Stage.PEARL) < 0)) {
            clear(client, true);
            return;
        }
        if (stage == Stage.CHARGE) {
            long delay = tick - pearlTick;
            if (!throwWindow(delay)) {
                if (delay > 2) { note(client, "Catch cancelled: the two-tick throw window passed."); clear(client, true); }
                return;
            }
            solution = solveCharge(pearl, (int) delay, vec(player.getEyePos()), inherited(vec(player.getMovement()), player.isOnGround()));
            if (solution == null || !clearPaths(client, pearl, (int) delay, vec(player.getEyePos()), solution)) {
                solution = null;
                // One more tick can yield a valid route, but never send a late blind charge.
                if (delay == 2) { note(client, "Catch cancelled: no clear wind-charge intercept."); clear(client, true); }
                return;
            }
            aim = new Rotation(solution.yaw(), solution.pitch());
        }
        if (aim != null) {
            if (glidingSequence) ElytraSilentAim.submit(this, aim, PRIORITY);
            else RotationManager.getInstance().submit(RotationTask.builder(this, aim)
                .priority(PRIORITY).lifetimeTicks(1).smoothing(180, 90)
                .sensitivityQuantized(false).moveFix(MoveFixMode.STRICT).build());
        }
    }

    private void begin(ModuleContext context, long tick) {
        MinecraftClient client = context.client();
        if (VanillaInputGuard.hasInteractionConflict(client) || slotsContended()) return;
        if (usableSlot(client, true) < 0 || usableSlot(client, false) < 0) {
            note(client, "Catch needs a ready ender pearl and wind charge in your hotbar.");
            return;
        }
        player = client.player;
        world = client.world;
        glidingSequence = player.isGliding();
        silentAim = context.toggle("Silent aim", false);
        silentSwap = context.toggle("Silent swap", true);
        returnSlot = context.toggle("Return slot", true);
        originalSlot = visibleSlot = player.getInventory().getSelectedSlot();
        aim = pearlAim = new Rotation(player.getYaw(), player.getPitch());
        State predicted = launch(vec(player.getEyePos()), vec(player.getMovement()), player.isOnGround(), aim.yaw(), aim.pitch());
        if (preflight(client, predicted) == null) {
            note(client, "No clear catch trajectory. Try more open space or aim slightly higher.");
            clear(client, false);
            return;
        }
        stage = Stage.PEARL;
        startedTick = tick;
        frameNanos = System.nanoTime();
    }

    @Override public void onActionTick(ModuleContext context) {
        if (stage != Stage.PEARL && stage != Stage.CHARGE) return;
        MinecraftClient client = context.client();
        RotationManager rotations = RotationManager.getInstance();
        if (!ready(client) || client.player != player || client.world != world || player.isGliding() != glidingSequence
            || !rotations.isOwnedBy(this) || aim == null
            || rotations.serverRotation().angularDistanceTo(aim) > .01) return;
        if (stage == Stage.CHARGE && solution == null) return;
        if (!silentAim && new Rotation(player.getYaw(), player.getPitch()).angularDistanceTo(aim) > .75) return;
        Stage expected = stage;
        ActionScheduler.getInstance().submit(this, PRIORITY, actionClient -> {
            if (stage != expected || !ready(actionClient) || actionClient.player != player || actionClient.world != world
                || player.isGliding() != glidingSequence
                || !rotations.isOwnedBy(this) || slotsContended()
                || player.getInventory().getSelectedSlot() != visibleSlot) return;
            int slot = usableSlot(actionClient, expected == Stage.PEARL);
            if (slot < 0) { clear(actionClient, true); return; }
            long tick = ActionScheduler.getInstance().tickNumber();
            State launched = null;
            if (expected == Stage.PEARL) {
                launched = launch(vec(player.getEyePos()), vec(player.getMovement()), player.isOnGround(), aim.yaw(), aim.pitch());
                if (preflight(actionClient, launched) == null) { clear(actionClient, true); return; }
            } else {
                if (!throwWindow(tick - pearlTick)) { clear(actionClient, true); return; }
                // Other START hooks may have changed motion since our planning hook. Item use
                // inherits the movement at execution, so never send an angle for an old state.
                Solution fresh = solveCharge(pearl, (int) (tick - pearlTick), vec(player.getEyePos()),
                    inherited(vec(player.getMovement()), player.isOnGround()));
                if (fresh == null || !clearPaths(actionClient, pearl, (int) (tick - pearlTick), vec(player.getEyePos()), fresh)) return;
                Rotation freshAim = new Rotation(fresh.yaw(), fresh.pitch());
                if (rotations.serverRotation().angularDistanceTo(freshAim) > .01) return;
                solution = fresh;
                aim = freshAim;
            }
            // Frame-based camera easing leaves only a sub-degree terminal correction.
            if (!silentAim) {
                player.setYaw(player.getYaw() + MathHelper.wrapDegrees(aim.yaw() - player.getYaw()));
                player.setPitch(aim.pitch());
            }
            if (!useSlot(actionClient, slot)) {
                if (glidingSequence) {
                    // A failed use must not renew flight steering; retain this tick's coherent look,
                    // then release at the next START just like a successful final throw.
                    stage = Stage.RESTORE;
                    finishedTick = tick;
                }
                return;
            }
            if (expected == Stage.PEARL) {
                pearl = launched;
                pearlTick = tick;
                stage = Stage.CHARGE;
                // Start the visible transition immediately, before the next tick's exact re-solve.
                Solution forecast = preflight(actionClient, pearl);
                solution = null;
                if (forecast != null) aim = new Rotation(forecast.yaw(), forecast.pitch());
            } else {
                stage = Stage.RESTORE;
                finishedTick = tick;
                if (returnSlot && !silentSwap) {
                    SilentSlotManager.getInstance().restoreVisibleNextTick(player, originalSlot, visibleSlot);
                }
            }
        });
    }

    @Override public void onRenderFrame(ModuleContext context) {
        MinecraftClient client = context.client();
        long now = System.nanoTime();
        double seconds = frameNanos == 0 ? 0 : Math.min(.1, Math.max(0, (now - frameNanos) / 1e9));
        frameNanos = now;
        if (silentAim || stage != Stage.CHARGE || aim == null || client.player != player || client.world != world
            || player.isGliding() != glidingSequence
            || !ready(client) || !RotationManager.getInstance().isOwnedBy(this)) return;
        float blend = (float) -Math.expm1(-75 * seconds);
        player.setYaw(player.getYaw() + MathHelper.wrapDegrees(aim.yaw() - player.getYaw()) * blend);
        player.setPitch(player.getPitch() + (aim.pitch() - player.getPitch()) * blend);
    }

    /** Forecast only; each real charge use is solved again from that tick's actual position/movement. */
    private Solution preflight(MinecraftClient client, State predicted) {
        Vec eyes = vec(player.getEyePos()), movement = vec(player.getMovement());
        Vec inherited = inherited(movement, player.isOnGround());
        Vec flightOrigin = eyes, flightMovement = movement;
        boolean slowFalling = player.hasStatusEffect(StatusEffects.SLOW_FALLING);
        RotationManager rotations = RotationManager.getInstance();
        // The pearl action precedes travel. Its selected look also drives that first flight step.
        // Before our request is resolved, the initial pearl look is simply the camera look.
        boolean selectedFlight = rotations.isOwnedBy(this) && rotations.glidingPhysics();
        float flightYaw = selectedFlight ? rotations.outgoingYawOr(player.getYaw()) : player.getYaw();
        float flightPitch = selectedFlight ? rotations.serverPitchOr(player.getPitch()) : player.getPitch();
        for (int delay = 1; delay <= 2; delay++) {
            Vec origin, chargeMovement;
            if (glidingSequence) {
                // If tick one has no charge solution, its request is not renewed: normal flight resumes.
                flightMovement = glideVelocity(flightMovement, delay == 1 ? flightYaw : player.getYaw(),
                    delay == 1 ? flightPitch : player.getPitch(),
                    player.getFinalGravity(), slowFalling);
                if (flightMovement == null) return null;
                flightOrigin = flightOrigin.add(flightMovement);
                origin = flightOrigin;
                chargeMovement = flightMovement; // Elytra inherits all three axes, including lift/dive speed.
            } else {
                origin = eyes.add(movement.scale(delay));
                chargeMovement = inherited;
            }
            Solution forecast = solveCharge(predicted, delay, origin, chargeMovement);
            if (forecast != null && clearPaths(client, predicted, delay, origin, forecast)) return forecast;
        }
        return null;
    }

    private Solution solveCharge(State thrown, int delay, Vec origin, Vec movement) {
        return solve(thrown, delay, origin, movement, pearlAim.yaw(), pearlAim.pitch());
    }

    private boolean useSlot(MinecraftClient client, int slot) {
        boolean[] accepted = {false};
        Runnable action = () -> {
            if (client.interactionManager.interactItem(player, Hand.MAIN_HAND).isAccepted()) {
                accepted[0] = true;
                player.swingHand(Hand.MAIN_HAND);
                client.gameRenderer.firstPersonRenderer.resetEquipProgress(Hand.MAIN_HAND);
            }
        };
        if (silentSwap) {
            SilentSlotManager.getInstance().runWithSlot(this, slot, PRIORITY, true, action);
        } else if (SilentSlotManager.getInstance().selectVisibleSlot(client, slot)) {
            visibleSlot = slot;
            action.run();
        }
        return accepted[0];
    }

    private static boolean clearPaths(MinecraftClient client, State pearl, int delay, Vec origin, Solution solution) {
        int lastPearlTick = delay + solution.windTicks();
        for (int tick = 0; tick <= lastPearlTick; tick++) {
            State next = step(pearl);
            Vec end = tick == lastPearlTick ? solution.catchPosition() : next.position();
            if (!clearSegment(client, pearl.position(), end, true)) return false;
            pearl = next;
        }
        Vec wind = origin;
        for (int tick = 0; tick < solution.windTicks(); tick++) {
            Vec next = wind.add(solution.windVelocity());
            if (!clearSegment(client, wind, next, false)) return false;
            wind = next;
        }
        return true;
    }

    private static boolean clearSegment(MinecraftClient client, Vec start, Vec end, boolean pearl) {
        Vec3d from = mc(start), to = mc(end);
        double length = from.distanceTo(to);
        if (!Double.isFinite(length) || length > 16) return false;
        int samples = Math.max(1, (int) Math.ceil(length / 4));
        for (int sample = 0; sample <= samples; sample++) {
            if (!client.world.isChunkLoaded(BlockPos.ofFloored(from.lerp(to, (double) sample / samples)))) return false;
        }
        if (client.world.getCollisionsIncludingWorldBorder(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.ANY, client.player)).getType() != HitResult.Type.MISS) return false;
        // Avoid throwing through an already-present entity/projectile. No crosshair data is used.
        for (Entity entity : client.world.getOtherEntities(client.player, new Box(from, to).expand(.3),
            entity -> entity.canBeHitByProjectile() && !entity.isSpectator())) {
            if (!pearl && (entity instanceof AbstractWindChargeEntity || entity instanceof EndCrystalEntity)) continue;
            if (entity.getBoundingBox().expand(.3).contains(from)
                || entity.getBoundingBox().expand(.3).raycast(from, to).isPresent()) return false;
        }
        return true;
    }

    @Override public void onDisable(ModuleContext context) { clear(context.client(), true); keyDown = false; }
    @Override public void onWorldChanged(ModuleContext context) { clear(context.client(), false); keyDown = false; }

    private void clear(MinecraftClient client, boolean restore) {
        if (restore && returnSlot && !silentSwap && client.player == player && player != null
            && player.getInventory().getSelectedSlot() == visibleSlot) {
            // Never put a restore after a use packet in the same tick, even on disable.
            SilentSlotManager.getInstance().restoreVisibleNextTick(player, originalSlot, visibleSlot);
        }
        GameplayFoundation.releaseOwner(this);
        stage = Stage.IDLE;
        player = null;
        world = null;
        originalSlot = visibleSlot = -1;
        pearl = null;
        solution = null;
        aim = pearlAim = null;
        frameNanos = 0;
        glidingSequence = false;
    }

    private static int usableSlot(MinecraftClient client, boolean pearl) {
        int slot = HorizontalPearlCatchCombatLoadout.findItem(client, pearl ? Items.ENDER_PEARL : Items.WIND_CHARGE);
        return slot >= 0 && !client.player.getItemCooldownManager().isCoolingDown(client.player.getInventory().getStack(slot)) ? slot : -1;
    }
    private boolean slotsContended() {
        return SilentSlotManager.getInstance().isActive() && !SilentSlotManager.getInstance().isOwnedBy(this);
    }
    private static boolean ready(MinecraftClient client) {
        return client != null && client.player != null && client.world != null && client.interactionManager != null
            && client.getNetworkHandler() != null && client.player.isAlive() && !client.player.isSpectator()
            && !client.player.hasVehicle() && !client.player.isTouchingWater() && !client.player.isInLava()
            && client.currentScreen == null && client.getOverlay() == null && client.isWindowFocused();
    }
    private static boolean actionDown(ModuleContext context) {
        int key = context.keybind("Action");
        return key != GLFW.GLFW_KEY_UNKNOWN && context.client().getWindow() != null
            && InputUtil.isKeyPressed(context.client().getWindow(), key);
    }
    private static void note(MinecraftClient client, String message) {
        if (client.player != null) client.player.sendMessage(Text.literal(message), true);
    }
    private static Vec vec(Vec3d v) { return new Vec(v.x, v.y, v.z); }
    private static Vec3d mc(Vec v) { return new Vec3d(v.x(), v.y(), v.z()); }
}
