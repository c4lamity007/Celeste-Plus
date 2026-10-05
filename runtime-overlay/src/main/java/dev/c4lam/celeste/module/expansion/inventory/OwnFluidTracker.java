package dev.c4lam.celeste.module.expansion.inventory;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Remembers still water/lava the local player placed so Auto Drain will not steal it. */
public final class OwnFluidTracker {
    private static final OwnFluidTracker INSTANCE = new OwnFluidTracker();
    private static final int MAX_OWNED = 64;
    private static final long PLACE_GRACE_NANOS = 3_000_000_000L;

    private final Map<BlockPos, Long> owned = new LinkedHashMap<>();
    private final Set<BlockPos> claimed = new HashSet<>();
    private ClientWorld world;

    private OwnFluidTracker() {
    }

    public static OwnFluidTracker getInstance() {
        return INSTANCE;
    }

    public void mark(BlockPos pos) {
        if (pos == null) {
            return;
        }
        prune();
        BlockPos key = pos.toImmutable();
        owned.remove(key);
        owned.put(key, System.nanoTime());
        if (owned.size() > MAX_OWNED) {
            owned.keySet().removeIf(ownedPos -> !claimed.contains(ownedPos));
            while (owned.size() > MAX_OWNED) {
                Iterator<BlockPos> iterator = owned.keySet().iterator();
                if (!iterator.hasNext()) {
                    break;
                }
                iterator.next();
                iterator.remove();
            }
        }
    }

    public void claim(BlockPos pos) {
        if (pos == null) {
            return;
        }
        BlockPos key = pos.toImmutable();
        claimed.add(key);
        mark(key);
        for (BlockPos neighbor : BlockPos.iterate(key.add(-1, -1, -1), key.add(1, 1, 1))) {
            claimed.add(neighbor.toImmutable());
            mark(neighbor);
        }
    }

    public void release(BlockPos pos) {
        if (pos == null) {
            return;
        }
        BlockPos key = pos.toImmutable();
        claimed.remove(key);
        for (BlockPos neighbor : BlockPos.iterate(key.add(-1, -1, -1), key.add(1, 1, 1))) {
            claimed.remove(neighbor.toImmutable());
        }
    }

    public boolean hasClaim() {
        prune();
        return !claimed.isEmpty();
    }

    public boolean isOwn(BlockPos pos) {
        prune();
        if (pos == null) {
            return false;
        }
        if (claimed.contains(pos)) {
            return true;
        }
        return owned.containsKey(pos);
    }

    public void clear() {
        owned.clear();
        claimed.clear();
        world = null;
    }

    public static BlockPos predictFilledBucketPlace(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            return null;
        }
        double reach = client.player.getBlockInteractionRange();
        Vec3d start = client.player.getEyePos();
        Vec3d end = start.add(client.player.getRotationVec(1.0F).multiply(reach));
        HitResult result = client.world.raycast(new RaycastContext(
            start,
            end,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            client.player
        ));
        if (!(result instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        if (client.world.getBlockState(pos).isReplaceable() || client.world.getFluidState(pos).isStill()) {
            return pos.toImmutable();
        }
        return pos.offset(hit.getSide()).toImmutable();
    }

    private void prune() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld current = client == null ? null : client.world;
        if (current != world) {
            owned.clear();
            claimed.clear();
            world = current;
            return;
        }
        if (current == null) {
            return;
        }
        long now = System.nanoTime();
        owned.entrySet().removeIf(entry -> {
            if (isStillFluid(current, entry.getKey())) {
                return false;
            }
            return now - entry.getValue() > PLACE_GRACE_NANOS;
        });
    }

    private static boolean isStillFluid(ClientWorld world, BlockPos pos) {
        var fluid = world.getFluidState(pos);
        if (!fluid.isStill()) {
            return false;
        }
        Fluid type = fluid.getFluid();
        return type == Fluids.WATER || type == Fluids.LAVA;
    }
}
