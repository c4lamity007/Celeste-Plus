package dev.c4lam.celeste.rotation;

import dev.c4lam.celeste.action.VanillaInputGuard;
import dev.c4lam.celeste.module.delivery.ModuleHooks;
import dev.c4lam.celeste.module.delivery.ScopedActionOwners;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.MathHelper;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Client-thread rotation arbiter. It never mutates the player's camera rotation;
 * hooks substitute the selected look for packets and the request's opted-in movement physics.
 */
public final class RotationManager {
    private static final RotationManager INSTANCE = new RotationManager();

    private final Map<Object, ActiveTask> tasks = new HashMap<>();
    private final ScopedActionOwners owners = new ScopedActionOwners();
    private long tick;
    private long sequence;
    private ClientWorld world;
    private ClientPlayerEntity playerInstance;
    private volatile Rotation serverRotation = Rotation.ZERO;
    private Rotation previousRenderRotation = Rotation.ZERO;
    private volatile Object activeOwner;
    private volatile MoveFixMode moveFixMode = MoveFixMode.OFF;
    private volatile boolean glidingPhysics;
    private volatile boolean active;
    private float outgoingYaw;
    private boolean outgoingYawInitialized;
    private Object releaseAfterActionOwner;
    private boolean tickEnded = true;

    private RotationManager() {
    }

    public static RotationManager getInstance() {
        return INSTANCE;
    }

    public boolean submit(RotationTask task) {
        Objects.requireNonNull(task, "task");
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) {
            throw new IllegalStateException("Rotation requests must run on the Minecraft client thread");
        }
        // Mounted look/movement packets have a separate vanilla lifecycle. Do
        // not silently substitute their rotation unless a future API models a
        // concrete mounted action explicitly.
        if (client.player != null && client.player.hasVehicle()) {
            tasks.remove(task.owner());
            owners.release(task.owner());
            if (Objects.equals(activeOwner, task.owner())) {
                deactivateToCamera(client.player);
            }
            return false;
        }
        if(!owners.track(task.owner(), () -> retireOwner(task.owner()))) return false;
        long now = System.nanoTime();
        long deadline;
        try {
            deadline = Math.addExact(now, task.lifetimeNanos());
        } catch (ArithmeticException ignored) {
            deadline = Long.MAX_VALUE;
        }

        ActiveTask activeTask = new ActiveTask(task, tick, deadline, ++sequence);
        tasks.put(task.owner(), activeTask);
        ActiveTask winner = selectWinner(now);
        return winner == activeTask;
    }

    public void release(Object owner) {
        if (owner == null) {
            return;
        }
        if (Objects.equals(releaseAfterActionOwner, owner)) {
            return;
        }

        tasks.remove(owner);
        owners.release(owner);
        if (Objects.equals(activeOwner, owner)) {
            deactivateToCamera(MinecraftClient.getInstance().player);
        }
    }

    private void retireOwner(Object owner) {
        if(Objects.equals(releaseAfterActionOwner,owner)) {
            tasks.remove(owner);
            Object retired=new Object();
            releaseAfterActionOwner=retired;activeOwner=retired;
        } else release(owner);
    }

    /** Retains an emitted interaction's look through the following vanilla movement packet. */
    public void releaseAfterAction(Object owner) {
        if (!tickEnded && owner != null && isOwnedBy(owner)) {
            releaseAfterActionOwner = owner;
        }
    }

    /** Global cleanup also covers owners disabled before their own END callback. */
    public void endTick() {
        tickEnded = true;
        Object owner = releaseAfterActionOwner;
        releaseAfterActionOwner = null;
        if (owner != null) {
            release(owner);
        }
    }

    public void tick(MinecraftClient client) {
        prepare(client);
        tickEnded = false;
        previousRenderRotation = active ? serverRotation : client.player == null ? serverRotation
            : new Rotation(client.player.lastYaw, client.player.lastPitch);
        tick++;
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return;
        }
        if (player.hasVehicle()) {
            tasks.clear(); owners.clear();
            deactivateToCamera(player);
            return;
        }
        // Manual clicks now use RealActionTarget's scoped resolved look. Keep
        // that look selected for them; only inventory/held-item mutations
        // relinquish it. ActionScheduler still excludes duplicate automated
        // interactions when a physical click is being processed this tick.
        if (VanillaInputGuard.hasRotationConflict(client)) {
            tasks.clear(); owners.clear();
            deactivateToCamera(player);
            return;
        }

        Rotation camera = new Rotation(player.getYaw(), player.getPitch());
        long now = System.nanoTime();
        tasks.values().removeIf(task -> {
            if(task.expired(tick,now) || !owners.allowed(task.task.owner())) {
                owners.release(task.task.owner());return true;
            }return false;
        });
        ActiveTask winner = selectWinner(now);
        if (winner == null) {
            active = false;
            activeOwner = null;
            moveFixMode = MoveFixMode.OFF;
            glidingPhysics = false;
            serverRotation = camera;
            return;
        }

        Rotation base = active ? serverRotation : camera;
        RotationTask task = winner.task;
        Rotation stepped = base.stepToward(
            task.target(),
            task.maximumYawStep(),
            task.maximumPitchStep()
        );
        if (task.sensitivityQuantized() && (boolean) ModuleHooks.invoke("sword_aura", "rotation.quantize", true)) {
            double sensitivity = client.options.getMouseSensitivity().getValue();
            stepped = RotationQuantizer.quantize(base, stepped, sensitivity);
        }

        serverRotation = stepped;
        activeOwner = task.owner();
        moveFixMode = task.moveFixMode().packetRepresentable();
        glidingPhysics = task.glidingPhysics();
        active = true;
    }

    /** Clears stale leases before modules submit requests for a new connection/player. */
    public void prepare(MinecraftClient client) {
        // Recover a deferred release if the prior client END callback was absent.
        endTick();
        ClientPlayerEntity player = client.player;
        ClientWorld nextWorld = client.world;
        if (player == null || nextWorld == null) {
            if (playerInstance != null || world != null || !tasks.isEmpty()) {
                clear();
            }
            playerInstance = null;
            world = null;
            return;
        }
        if (player != playerInstance || nextWorld != world) {
            clear();
            playerInstance = player;
            world = nextWorld;
            serverRotation = new Rotation(player.getYaw(), player.getPitch());
            previousRenderRotation = serverRotation;
            outgoingYaw = player.getYaw();
            outgoingYawInitialized = true;
        }
    }

    public boolean isActive() {
        return active && owners.allowed(activeOwner);
    }

    public boolean isOwnedBy(Object owner) {
        return isActive() && Objects.equals(activeOwner, owner);
    }

    public Object activeOwner() {
        return activeOwner;
    }

    /**
     * An owner that requested a live rotation may act only when that request is
     * the selected server rotation. Owners without a rotation request are not
     * coupled to unrelated low-priority visual/aim assistance.
     */
    public boolean permitsAction(Object owner) {
        ActiveTask requested = tasks.get(owner);
        long now = System.nanoTime();
        return requested == null || (!requested.expired(tick, now) && isOwnedBy(owner));
    }

    /**
     * Pins the outgoing look to the selected action. If that action did not
     * request a rotation, fall back to the camera so an unrelated aim task can
     * never leak into its vanilla interaction packet.
     */
    public boolean alignForAction(Object owner) {
        ActiveTask requested = tasks.get(owner);
        long now = System.nanoTime();
        if (requested != null) {
            return !requested.expired(tick, now) && isOwnedBy(owner);
        }
        if (active && !Objects.equals(activeOwner, owner)) {
            deactivateToCamera(MinecraftClient.getInstance().player);
        }
        return true;
    }

    public Rotation serverRotation() {
        return serverRotation;
    }

    /** Render-only interpolation; never substituted for the exact packet/action aim. */
    public Rotation renderRotation(float tickDelta) {
        Rotation current = serverRotation;
        return new Rotation(ElytraRotationMath.yaw(previousRenderRotation.yaw(), current.yaw(), tickDelta),
            ElytraRotationMath.pitch(previousRenderRotation.pitch(), current.pitch(), tickDelta));
    }

    /**
     * Lets the owning module refine the already-selected silent look between
     * ticks without touching the camera. Packets still go out on the tick.
     */
    public void hold(Object owner, Rotation rotation, MoveFixMode mode) {
        if (rotation == null || !isOwnedBy(owner) || Objects.equals(releaseAfterActionOwner, owner)) {
            return;
        }
        serverRotation = rotation;
        moveFixMode = mode.packetRepresentable();
        active = true;
    }

    public MoveFixMode moveFixMode() {
        return isActive() ? moveFixMode : MoveFixMode.OFF;
    }

    /** Owned by the selected lease; release, expiration and preemption also release flight alignment. */
    public boolean glidingPhysics() {
        return isActive() && glidingPhysics;
    }

    public float serverYawOr(float fallback) {
        return isActive() ? serverRotation.yaw() : fallback;
    }

    /**
     * Returns the desired packet yaw without ever introducing a modulo-360
     * seam. Internally equivalent angles may be canonical, but vanilla yaw is
     * continuous: after 179 degrees, -179 degrees must be emitted as 181, not
     * as a raw -358 degree snap. The phase is retained while rotations release
     * back to the camera and is reset only with the player/world connection.
     */
    public float outgoingYawOr(float fallback) {
        float desired = isActive() ? serverRotation.yaw() : fallback;
        if (!Float.isFinite(desired)) {
            return fallback;
        }
        if (!outgoingYawInitialized || !Float.isFinite(outgoingYaw)) {
            outgoingYaw = desired;
            outgoingYawInitialized = true;
        } else {
            outgoingYaw += MathHelper.wrapDegrees(desired - outgoingYaw);
        }
        return outgoingYaw;
    }

    public float serverPitchOr(float fallback) {
        return isActive() ? serverRotation.pitch() : fallback;
    }

    public void clear() {
        tasks.clear(); owners.clear();
        releaseAfterActionOwner = null;
        tickEnded = true;
        active = false;
        activeOwner = null;
        moveFixMode = MoveFixMode.OFF;
        glidingPhysics = false;
        outgoingYaw = 0.0F;
        outgoingYawInitialized = false;
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null) {
            serverRotation = new Rotation(player.getYaw(), player.getPitch());
        }
        previousRenderRotation = serverRotation;
    }

    private void deactivateToCamera(ClientPlayerEntity player) {
        active = false;
        activeOwner = null;
        moveFixMode = MoveFixMode.OFF;
        glidingPhysics = false;
        if (player != null) {
            serverRotation = new Rotation(player.getYaw(), player.getPitch());
        }
    }

    private ActiveTask selectWinner(long now) {
        ActiveTask winner = null;
        for (ActiveTask candidate : tasks.values()) {
            if (candidate.expired(tick, now) || !owners.allowed(candidate.task.owner())) {
                continue;
            }
            if (winner == null
                || candidate.task.priority() > winner.task.priority()
                || (candidate.task.priority() == winner.task.priority()
                && candidate.sequence > winner.sequence)) {
                winner = candidate;
            }
        }
        return winner;
    }

    private record ActiveTask(RotationTask task, long submittedTick, long deadlineNanos, long sequence) {
        private boolean expired(long currentTick, long now) {
            return currentTick > submittedTick + task.lifetimeTicks() || now > deadlineNanos;
        }
    }
}
