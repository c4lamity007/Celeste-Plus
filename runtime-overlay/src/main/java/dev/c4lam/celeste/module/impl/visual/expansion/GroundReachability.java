package dev.c4lam.celeste.module.impl.visual.expansion;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import dev.c4lam.celeste.module.impl.combat.AuraServerState;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Client-tick terrain sampling. Rendering reads only the immutable published mask. */
public final class GroundReachability {
    private static final int RADIUS = 32;
    private static final int HEIGHT = 24;
    private static final int CACHE_LIMIT = 65536;
    private static final int READ_BUDGET = 768;
    private static final int RECHECK_BUDGET = 256;
    private static final int EDGE_BUDGET = 192;
    private static final long TICK_NANOS = 2_000_000;
    private static final GroundWalkability.Cell UNKNOWN = new GroundWalkability.Cell(false, List.of());
    private static final Exhausted EXHAUSTED = new Exhausted();
    private static State state;
    private static volatile Mask published;
    private static volatile boolean invalidated;
    private static long revision;

    private GroundReachability() { }

    /** Call only from the client's tick callback, never world rendering/extraction. */
    public static synchronized void tick(ClientWorld world, ClientPlayerEntity player) {
        if (world == null || player == null || !Double.isFinite(player.getX())
            || !Double.isFinite(player.getY()) || !Double.isFinite(player.getZ())) {
            reset();
            return;
        }
        double x = player.getX(), y = player.getY(), z = player.getZ();
        double width = Math.max(.6, player.getBoundingBox().getLengthX());
        double height = Math.max(1.8, player.getBoundingBox().getLengthY());
        if (!Double.isFinite(width) || !Double.isFinite(height) || width > 2 || height > 4) {
            // Never certify a route using a smaller body than an unusually scaled player.
            reset();
            return;
        }
        boolean replace = invalidated || state == null || state.world != world || state.player != player
            || Math.abs(state.width - width) > .01 || Math.abs(state.height - height) > .01;
        if (!replace) {
            double dx = x - state.lastX, dy = y - state.lastY, dz = z - state.lastZ;
            boolean teleport = dx * dx + dy * dy + dz * dz > 64;
            boolean outside = Math.abs(x - state.search.centerX()) > RADIUS - 3
                || Math.abs(z - state.search.centerZ()) > RADIUS - 3 || Math.abs(y - state.search.centerY()) > HEIGHT - 3;
            boolean recenter = player.isOnGround() && (Math.abs(x - state.search.centerX()) > 8
                || Math.abs(z - state.search.centerZ()) > 8 || Math.abs(y - state.search.centerY()) > 8);
            // A normal jump temporarily has no support. Keep its connected graph intact.
            if (player.isOnGround() && state.search.surfaceCount() > 0 && !state.search.reached(x, y, z))
                state.unreachedTicks++;
            else state.unreachedTicks = 0;
            replace = teleport || outside || recenter || state.unreachedTicks > 4;
        }
        if (replace) {
            published = null;
            state = new State(world, player, x, y, z, width, height);
            invalidated = false;
        }
        State active = state;
        active.lastX = x;
        active.lastY = y;
        active.lastZ = z;
        active.beginTick();
        try {
            if (active.serverChanged() || active.revalidate()) {
                // Any observed collision edit can disconnect a route, including remote stairs.
                published = null;
                state = new State(world, player, x, y, z, width, height);
                return;
            }
            if (active.search.surfaceCount() == 0 && player.isOnGround()
                && (Math.abs(active.seedX - x) > .05 || Math.abs(active.seedY - y) > .05 || Math.abs(active.seedZ - z) > .05)) {
                active.search = new GroundWalkability.Search(x, y, z, width, height, RADIUS, HEIGHT);
                active.seedX = x;
                active.seedY = y;
                active.seedZ = z;
            }
            if ((active.search.surfaceCount() > 0 || player.isOnGround()) && active.search.seed(active))
                active.search.advance(active, EDGE_BUDGET);
        } catch (Exhausted ignored) {
            // The current edge remains queued and cached reads make progress next tick.
        }
        if (active.serverScanTarget >= 0) {
            // A server edit has not yet been ruled out; never expose an unchecked route.
            published = null;
            return;
        }
        int count = active.search.surfaceCount();
        if (count > 0 && (count != active.publishedCount || published == null)) {
            published = new Mask(++revision, active.search);
            active.publishedCount = count;
        }
    }

    public static Mask snapshot() { return published; }

    /** Optional immediate invalidation; no client-world access is performed here. */
    public static synchronized void invalidate() {
        invalidated = true;
        published = null;
    }

    public static synchronized void reset() {
        published = null;
        state = null;
        invalidated = false;
    }

    public static final class Mask {
        private final long revision;
        private final int originX, originY, originZ, sizeX, sizeY, sizeZ;
        private final byte[] cells;

        private Mask(long revision, GroundWalkability.Search search) {
            this.revision = revision;
            originX = search.centerX() - search.radius();
            originY = search.centerY() - search.verticalRadius();
            originZ = search.centerZ() - search.radius();
            sizeX = sizeZ = search.radius() * 2 + 1;
            sizeY = search.verticalRadius() * 2 + 1;
            // RGBA bytes carry 32 independent top-height bits per support voxel.
            cells = new byte[sizeX * sizeY * sizeZ * 4];
            for (GroundWalkability.Surface surface : search.surfaces()) {
                int x = surface.x() - originX, y = surface.y() - originY, z = surface.z() - originZ;
                if (x >= 0 && x < sizeX && y >= 0 && y < sizeY && z >= 0 && z < sizeZ) {
                    int level = GroundWalkability.heightLevel(surface.topY());
                    int index = ((y * sizeZ + z) * sizeX + x) * 4 + (level >>> 3);
                    cells[index] |= (byte) (1 << (level & 7));
                }
            }
        }
        public long revision() { return revision; }
        public int originX() { return originX; }
        public int originY() { return originY; }
        public int originZ() { return originZ; }
        public int sizeX() { return sizeX; }
        public int sizeY() { return sizeY; }
        public int sizeZ() { return sizeZ; }
        public void copyTo(ByteBuffer destination) { destination.put(cells); }
    }

    private static final class Exhausted extends RuntimeException {
        Exhausted() { super(null, null, false, false); }
    }
    private record Cached(int x, int y, int z, BlockState state, GroundWalkability.Cell cell) { }

    private static final class State implements GroundWalkability.Probe {
        final ClientWorld world;
        final ClientPlayerEntity player;
        final double width, height;
        final Map<Long, Cached> cells = new HashMap<>();
        final ArrayList<Cached> order = new ArrayList<>();
        final BlockPos.Mutable position = new BlockPos.Mutable();
        GroundWalkability.Search search;
        double seedX, seedY, seedZ, lastX, lastY, lastZ;
        int reads, probes, cursor, nearCursor, publishedCount, unreachedTicks, serverCursor;
        long deadline, serverRevision, serverScanTarget = -1;

        State(ClientWorld world, ClientPlayerEntity player, double x, double y, double z, double width, double height) {
            this.world = world;
            this.player = player;
            this.width = width;
            this.height = height;
            seedX = lastX = x;
            seedY = lastY = y;
            seedZ = lastZ = z;
            search = new GroundWalkability.Search(x, y, z, width, height, RADIUS, HEIGHT);
            serverRevision = AuraServerState.revision(world);
        }

        void beginTick() {
            reads = probes = 0;
            deadline = System.nanoTime() + TICK_NANOS;
        }

        void budget(boolean worldRead) {
            if (((++probes & 63) == 0 && System.nanoTime() >= deadline) || (worldRead && ++reads > READ_BUDGET))
                throw EXHAUSTED;
        }

        boolean serverChanged() {
            while (true) {
                budget(false);
                long current = AuraServerState.revision(world);
                // The existing observer retains at most 1024 block updates. Unknown history
                // or a reset cannot certify routes built against the previous revision.
                if (current < serverRevision || current - serverRevision > 1024) return true;
                if (serverScanTarget < 0) {
                    if (current == serverRevision) return false;
                    serverScanTarget = current;
                    serverCursor = 0;
                }
                while (serverCursor < order.size()) {
                    budget(false);
                    Cached cached = order.get(serverCursor);
                    position.set(cached.x, cached.y, cached.z);
                    AuraServerState.BlockObservation observed = AuraServerState.block(world, position);
                    if (observed != null && observed.revision() > serverRevision && observed.state() != cached.state)
                        return true;
                    serverCursor++;
                }
                serverRevision = serverScanTarget;
                serverScanTarget = -1;
                // If an observation arrived during a continued scan, inspect it as well.
            }
        }

        boolean revalidate() {
            // Prioritize nearby client prediction (placing/mining), which may precede the
            // server observation. No uncached positions are queried by this extra sweep.
            int feetX = (int) Math.floor(lastX), feetY = (int) Math.floor(lastY), feetZ = (int) Math.floor(lastZ);
            for (int i = 0; i < 64; i++) {
                budget(false);
                int index = nearCursor;
                nearCursor = (nearCursor + 1) % (11 * 11 * 7);
                int x = feetX + index % 11 - 5;
                int z = feetZ + (index / 11) % 11 - 5;
                int y = feetY + index / (11 * 11) - 2;
                Cached cached = cells.get(BlockPos.asLong(x, y, z));
                if (cached != null && changed(cached)) return true;
            }
            for (int i = 0, count = Math.min(RECHECK_BUDGET, order.size()); i < count; i++) {
                if (cursor >= order.size()) cursor = 0;
                Cached cached = order.get(cursor);
                if (changed(cached)) return true;
                cursor++;
            }
            return false;
        }

        boolean changed(Cached cached) {
            budget(true);
            position.set(cached.x, cached.y, cached.z);
            boolean loaded = !world.isOutOfHeightLimit(position) && world.isChunkLoaded(position);
            return loaded != cached.cell.loaded() || (loaded && world.getBlockState(position) != cached.state);
        }

        @Override public GroundWalkability.Cell cell(int x, int y, int z) {
            budget(false);
            long key = BlockPos.asLong(x, y, z);
            Cached existing = cells.get(key);
            if (existing != null) return existing.cell;
            if (cells.size() >= CACHE_LIMIT) return UNKNOWN;
            budget(true);
            position.set(x, y, z);
            BlockState block = null;
            GroundWalkability.Cell result = UNKNOWN;
            if (!world.isOutOfHeightLimit(position) && world.isChunkLoaded(position)) {
                block = world.getBlockState(position);
                ArrayList<GroundWalkability.Box> boxes = new ArrayList<>();
                boolean ordinary = !block.isOf(Blocks.POWDER_SNOW) && !block.isOf(Blocks.SCAFFOLDING);
                if (!ordinary) {
                    // No climbing or equipment/pose-dependent support assumptions.
                    boxes.add(new GroundWalkability.Box(x, y, z, x + 1, y + 1, z + 1));
                } else {
                    for (net.minecraft.util.math.Box box : block.getCollisionShape(world, position, ShapeContext.of(player)).getBoundingBoxes())
                        boxes.add(new GroundWalkability.Box(x + box.minX, y + box.minY, z + box.minZ,
                            x + box.maxX, y + box.maxY, z + box.maxZ));
                }
                List<GroundWalkability.Box> supports = ordinary ? List.copyOf(boxes) : List.of();
                if (!block.getFluidState().isEmpty())
                    boxes.add(new GroundWalkability.Box(x, y, z, x + 1, y + 1, z + 1));
                // Water blocks traversal but is never support; real waterlogged solid tops
                // remain available when the player's standing body is above the fluid.
                result = new GroundWalkability.Cell(true, boxes, supports);
            }
            Cached cached = new Cached(x, y, z, block, result);
            cells.put(key, cached);
            order.add(cached);
            return result;
        }
    }
}
