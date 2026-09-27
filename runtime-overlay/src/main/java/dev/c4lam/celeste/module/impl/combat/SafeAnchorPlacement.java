package dev.c4lam.celeste.module.impl.combat;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.ColoredFallingBlock;
import net.minecraft.block.ConcretePowderBlock;
import net.minecraft.block.DirtPathBlock;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.FarmlandBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.PillarBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.SpreadableBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TransparentBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Random;
import java.util.List;

/** Plans a real glowstone placement while retaining a reachable face of the anchor. */
final class SafeAnchorPlacement {
    private static final double MAX_REACH = 4.45D;
    private static final double INSET = 0.0001D;
    private static final double[] FACE_SAMPLES = { .035D, .25D, .5D, .75D, .965D };
    private static final int[] HEIGHTS = { 0, -1, 1 };

    record Plan(BlockPos shieldPos, BlockHitResult placementHit, BlockHitResult anchorHit) {}

    /** World boundary used by the planner and its deterministic geometry checks. */
    interface Scene {
        Vec3d eyes();
        Vec3d body();
        double reach();
        boolean anchor(BlockPos pos);
        boolean available(BlockPos pos);
        boolean support(BlockPos pos);
        default boolean replaceableTarget(BlockPos pos) { return false; }
        boolean placeable(BlockPos pos, BlockHitResult hit);
        BlockHitResult raycast(Vec3d end);
        default List<Box> outline(BlockPos pos) { return List.of(new Box(pos)); }
    }

    private SafeAnchorPlacement() {}

    static Plan find(MinecraftClient client, BlockPos anchorPos, Random random) {
        if (!ready(client)) return null;
        return find(new ClientScene(client), anchorPos, random);
    }

    static BlockHitResult visibleAnchor(MinecraftClient client, BlockPos anchorPos, Random random) {
        if (!ready(client)) return null;
        return visibleAnchor(new ClientScene(client), anchorPos, null, random);
    }

    static boolean validPlacement(MinecraftClient client, Plan plan) {
        return ready(client) && validPlacement(new ClientScene(client), plan);
    }

    static boolean hitVisible(MinecraftClient client, BlockHitResult hit) {
        if (!ready(client) || hit == null) return false;
        Scene scene = new ClientScene(client);
        return withinReach(scene, hit.getPos()) && sameFace(scene.raycast(inside(hit)), hit);
    }

    static boolean inertSupport(BlockState state) {
        Block block = state.getBlock();
        // Deliberately exclude anchor/container/workstation/button uses. FallingBlock
        // alone is not sufficient: anvils and dragon eggs also inherit from it.
        return !state.isReplaceable() && (block.getClass() == Block.class
            || block.getClass() == FallingBlock.class || block instanceof ColoredFallingBlock
            || block instanceof ConcretePowderBlock || block instanceof PillarBlock
            || block instanceof TransparentBlock || block instanceof SlabBlock
            || block instanceof StairsBlock || block instanceof LeavesBlock
            || block instanceof SpreadableBlock || block instanceof FarmlandBlock
            || block instanceof DirtPathBlock || state.isOf(Blocks.OBSIDIAN)
            || state.isOf(Blocks.BEDROCK) || state.isOf(Blocks.STONE) || state.isOf(Blocks.DIRT)
            || state.isOf(Blocks.DEEPSLATE) || state.isOf(Blocks.NETHERRACK) || state.isOf(Blocks.END_STONE));
    }

    static Plan find(Scene scene, BlockPos anchorPos, Random random) {
        if (anchorPos == null || !scene.anchor(anchorPos)) return null;
        Plan best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        // Eight directions around the anchor, with vertical choices for uneven terrain.
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) continue;
                for (int y : HEIGHTS) {
                    BlockPos shield = anchorPos.add(x, y, z);
                    double score = score(scene, anchorPos, shield);
                    if (!Double.isFinite(score) || score >= bestScore || !scene.available(shield)) continue;
                    BlockHitResult place = placementHit(scene, shield, random);
                    if (place == null) continue;
                    BlockHitResult anchor = visibleAnchor(scene, anchorPos, shield, random);
                    if (anchor == null) continue;
                    // Geometry wins; a small tie break avoids fixed diagonal/face preference.
                    score += random.nextDouble() * .025D;
                    if (score < bestScore) {
                        bestScore = score;
                        best = new Plan(shield.toImmutable(), place, anchor);
                    }
                }
            }
        }
        return best;
    }

    static boolean validPlacement(Scene scene, Plan plan) {
        if (plan == null || !scene.anchor(plan.anchorHit().getBlockPos())
            || !scene.available(plan.shieldPos())
            || !Double.isFinite(score(scene, plan.anchorHit().getBlockPos(), plan.shieldPos()))) return false;
        BlockHitResult place = plan.placementHit();
        boolean replacesTarget = place.getBlockPos().equals(plan.shieldPos())
            && scene.replaceableTarget(plan.shieldPos());
        if ((!replacesTarget && !scene.support(place.getBlockPos())) || !withinReach(scene, place.getPos())
            || !scene.placeable(plan.shieldPos(), place)) return false;
        BlockHitResult seen = scene.raycast(inside(place));
        if (!sameFace(seen, place)) return false;
        // Never change the already-aimed placement target during validation.
        BlockHitResult anchor = plan.anchorHit();
        if (withinReach(scene, anchor.getPos())
            && !blockedBy(new Box(plan.shieldPos()).expand(INSET), scene.eyes(), anchor.getPos())
            && sameFace(scene.raycast(inside(anchor)), anchor)) return true;
        return visibleAnchor(scene, anchor.getBlockPos(), plan.shieldPos(), new Random(0)) != null;
    }

    static BlockHitResult visibleAnchor(Scene scene, BlockPos anchorPos, BlockPos hypotheticalShield, Random random) {
        if (anchorPos == null || !scene.anchor(anchorPos)) return null;
        Box shield = hypotheticalShield == null ? null : new Box(hypotheticalShield).expand(INSET);
        int faceOffset = random.nextInt(Direction.values().length);
        for (int i = 0; i < Direction.values().length; i++) {
            Direction face = Direction.values()[(i + faceOffset) % Direction.values().length];
            if (!facesEyes(scene.eyes(), anchorPos, face)) continue;
            // Random interior aim first, then a dense fixed fallback for partial visibility.
            BlockHitResult hit = anchorFace(scene, anchorPos, face,
                .18D + random.nextDouble() * .64D, .18D + random.nextDouble() * .64D, shield);
            if (hit != null) return hit;
            for (double u : FACE_SAMPLES) {
                for (double v : FACE_SAMPLES) {
                    hit = anchorFace(scene, anchorPos, face, u, v, shield);
                    if (hit != null) return hit;
                }
            }
        }
        return null;
    }

    private static BlockHitResult anchorFace(Scene scene, BlockPos anchorPos, Direction face,
                                             double u, double v, Box shield) {
        Vec3d end = facePoint(anchorPos, face, u, v);
        if (!withinReach(scene, end) || blockedBy(shield, scene.eyes(), end)) return null;
        BlockHitResult hit = scene.raycast(end);
        return hit != null && hit.getType() == HitResult.Type.BLOCK
            && hit.getBlockPos().equals(anchorPos) && !blockedBy(shield, scene.eyes(), hit.getPos())
            ? hit : null;
    }

    private static BlockHitResult placementHit(Scene scene, BlockPos shield, Random random) {
        // Fire has a selectable outline. Vanilla replaces it at the hit position;
        // aiming through that outline at the floor would fail the real raycast.
        if (scene.replaceableTarget(shield)) {
            for (Direction face : Direction.values()) {
                BlockHitResult hit = placementFace(scene, shield, shield, face, random);
                if (hit != null) return hit;
            }
        }
        // The floor is usually the most stable support. Remaining faces handle stairs in terrain.
        for (Direction face : new Direction[]{ Direction.UP, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST, Direction.DOWN }) {
            BlockPos support = shield.offset(face.getOpposite());
            if (!scene.support(support)) continue;
            BlockHitResult hit = placementFace(scene, shield, support, face, random);
            if (hit != null) return hit;
        }
        return null;
    }

    private static BlockHitResult placementFace(Scene scene, BlockPos shield, BlockPos target,
                                                Direction face, Random random) {
        for (Box outline : scene.outline(target)) {
            if (!facesEyes(scene.eyes(), outline, face)) continue;
            BlockHitResult hit = supportFace(scene, shield, target, outline, face,
                .18D + random.nextDouble() * .64D, .18D + random.nextDouble() * .64D);
            if (hit != null) return hit;
            for (double u : FACE_SAMPLES) {
                for (double v : FACE_SAMPLES) {
                    hit = supportFace(scene, shield, target, outline, face, u, v);
                    if (hit != null) return hit;
                }
            }
        }
        return null;
    }

    private static BlockHitResult supportFace(Scene scene, BlockPos shield, BlockPos support, Box outline,
                                              Direction face, double u, double v) {
        Vec3d end = facePoint(outline, face, u, v);
        if (!withinReach(scene, end)) return null;
        BlockHitResult hit = scene.raycast(end);
        return hit != null && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(support)
            && hit.getSide() == face && scene.placeable(shield, hit) ? hit : null;
    }

    private static double score(Scene scene, BlockPos anchor, BlockPos shield) {
        Vec3d origin = anchor.toCenterPos();
        Vec3d body = scene.body();
        Vec3d center = shield.toCenterPos();
        double dx = body.x - origin.x;
        double dz = body.z - origin.z;
        double distance = dx * dx + dz * dz;
        if (distance < .01D) return Double.POSITIVE_INFINITY;
        double fraction = ((center.x - origin.x) * dx + (center.z - origin.z) * dz) / distance;
        // Never place behind the anchor or beyond the player's body.
        if (fraction <= 0.0D || fraction >= 1.0D) return Double.POSITIVE_INFINITY;
        double lateralX = center.x - (origin.x + dx * fraction);
        double lateralZ = center.z - (origin.z + dz * fraction);
        double lateral = lateralX * lateralX + lateralZ * lateralZ;
        // A nominally "between" block off to one side must actually intercept body exposure.
        Box cover = new Box(shield).contract(INSET);
        if (!blockedBy(cover, origin, body) && !blockedBy(cover, origin, body.add(0, -.45D, 0)))
            return Double.POSITIVE_INFINITY;
        double vertical = center.y - (origin.y + (body.y - origin.y) * fraction);
        return lateral * 4.0D + vertical * vertical + fraction * .15D;
    }

    private static Vec3d facePoint(BlockPos block, Direction face, double u, double v) {
        return facePoint(new Box(block), face, u, v);
    }

    private static Vec3d facePoint(Box box, Direction face, double u, double v) {
        double fixed = face.getDirection() == Direction.AxisDirection.POSITIVE
            ? box.getMax(face.getAxis()) - INSET : box.getMin(face.getAxis()) + INSET;
        return switch (face.getAxis()) {
            case X -> new Vec3d(fixed, box.minY + (box.maxY - box.minY) * u, box.minZ + (box.maxZ - box.minZ) * v);
            case Y -> new Vec3d(box.minX + (box.maxX - box.minX) * u, fixed, box.minZ + (box.maxZ - box.minZ) * v);
            case Z -> new Vec3d(box.minX + (box.maxX - box.minX) * u, box.minY + (box.maxY - box.minY) * v, fixed);
        };
    }

    private static boolean facesEyes(Vec3d eyes, BlockPos block, Direction face) {
        return facesEyes(eyes, new Box(block), face);
    }

    private static boolean facesEyes(Vec3d eyes, Box box, Direction face) {
        Vec3d point = facePoint(box, face, .5D, .5D);
        return (eyes.x - point.x) * face.getOffsetX() + (eyes.y - point.y) * face.getOffsetY()
            + (eyes.z - point.z) * face.getOffsetZ() > INSET;
    }

    private static boolean blockedBy(Box box, Vec3d from, Vec3d to) {
        return box != null && (box.contains(from) || box.raycast(from, to).isPresent());
    }

    private static boolean withinReach(Scene scene, Vec3d point) {
        return scene.eyes().squaredDistanceTo(point) <= scene.reach() * scene.reach();
    }

    private static Vec3d inside(BlockHitResult hit) {
        return hit.getPos().add(-hit.getSide().getOffsetX() * INSET,
            -hit.getSide().getOffsetY() * INSET, -hit.getSide().getOffsetZ() * INSET);
    }

    private static boolean sameFace(BlockHitResult actual, BlockHitResult expected) {
        return actual != null && actual.getType() == HitResult.Type.BLOCK
            && actual.getBlockPos().equals(expected.getBlockPos()) && actual.getSide() == expected.getSide();
    }

    private static boolean ready(MinecraftClient client) {
        return client != null && client.player != null && client.world != null;
    }

    private record ClientScene(MinecraftClient client) implements Scene {
        @Override public Vec3d eyes() { return client.player.getEyePos(); }
        @Override public Vec3d body() {
            return new Vec3d(client.player.getX(), client.player.getY() + client.player.getHeight() * .35D,
                client.player.getZ());
        }
        @Override public double reach() { return Math.min(MAX_REACH, client.player.getBlockInteractionRange()); }
        @Override public boolean anchor(BlockPos pos) {
            return loaded(pos) && client.world.getBlockState(pos).isOf(Blocks.RESPAWN_ANCHOR);
        }
        @Override public boolean available(BlockPos pos) {
            return loaded(pos) && client.world.getBlockState(pos).isReplaceable()
                && client.world.getFluidState(pos).isEmpty()
                && client.world.canPlace(Blocks.GLOWSTONE.getDefaultState(), pos, ShapeContext.ofPlacement(client.player))
                && client.world.getOtherEntities(null, new Box(pos).contract(INSET),
                    entity -> entity.isAlive() && !entity.isSpectator() && !(entity instanceof ItemEntity)).isEmpty();
        }
        @Override public boolean support(BlockPos pos) {
            if (!loaded(pos)) return false;
            BlockState state = client.world.getBlockState(pos);
            // Glowstone on the charged anchor adds a charge instead of placing a shield.
            // Likewise containers/buttons would consume use while not sneaking.
            return inertSupport(state);
        }
        @Override public boolean replaceableTarget(BlockPos pos) {
            if (!loaded(pos)) return false;
            BlockState state = client.world.getBlockState(pos);
            return state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE);
        }
        @Override public boolean placeable(BlockPos pos, BlockHitResult hit) {
            ItemPlacementContext context = new ItemPlacementContext(client.player, Hand.MAIN_HAND,
                new ItemStack(Items.GLOWSTONE), hit);
            return context.getBlockPos().equals(pos) && context.canPlace() && available(pos);
        }
        @Override public BlockHitResult raycast(Vec3d end) {
            return client.world.raycast(new RaycastContext(eyes(), end, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, client.player));
        }
        @Override public List<Box> outline(BlockPos pos) {
            return client.world.getBlockState(pos).getOutlineShape(client.world, pos,
                ShapeContext.of(client.player)).getBoundingBoxes().stream().map(box -> box.offset(pos)).toList();
        }
        private boolean loaded(BlockPos pos) {
            return client.world.isInBuildLimit(pos) && client.world.getWorldBorder().contains(pos)
                && client.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
        }
    }
}
