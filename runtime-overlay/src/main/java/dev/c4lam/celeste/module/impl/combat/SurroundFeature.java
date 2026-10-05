package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Four-block foot surround using one ordinary vanilla block interaction at a
 * time. It never moves inventory stacks and only selects an existing hotbar
 * stack for the duration of the interaction.
 */
public final class SurroundFeature extends SurroundCombatFeature {
    private static final int PRIORITY = 65;
    private static final Direction[] TARGET_DIRECTIONS = {
        Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };
    private static final Direction[] SUPPORT_FACES = {
        Direction.UP, Direction.NORTH, Direction.EAST,
        Direction.SOUTH, Direction.WEST, Direction.DOWN
    };

    private PlacementPlan plan;
    private int nextDirection;
    private int lastPlaceAge = Integer.MIN_VALUE / 2;

    @Override
    public String name() {
        return "Surround";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        plan = null;
        MinecraftClient client = context.client();
        if (!basicStateValid(client) || !delayElapsed(context)) {
            releaseIntent();
            return;
        }

        Material material = findMaterial(client, context.mode("Blocks", "Obsidian"));
        if (material == null) {
            releaseIntent();
            return;
        }

        BlockPos base = client.player.getBlockPos();
        double range = context.slider("Range", 4.5D);
        for (int offset = 0; offset < TARGET_DIRECTIONS.length; offset++) {
            int index = Math.floorMod(nextDirection + offset, TARGET_DIRECTIONS.length);
            BlockPos target = base.offset(TARGET_DIRECTIONS[index]);
            PlacementPlan candidate = createPlan(client, base, target, material, range, index);
            if (candidate != null) {
                plan = candidate;
                requestRotation(candidate.hit().getPos(), PRIORITY);
                return;
            }
        }
        releaseIntent();
    }

    @Override
    public void onActionTick(ModuleContext context) {
        PlacementPlan selected = plan;
        if (selected == null || !delayElapsed(context)) {
            return;
        }

        ActionScheduler.getInstance().submit(this, PRIORITY, client -> {
            ModuleContext current = new ModuleContext(client, context.module());
            if (resolveExecutionHit(client, current, selected) == null) {
                return;
            }

            int slot = findHotbarSlot(client, selected.material().item());
            if (slot < 0) {
                return;
            }
            Runnable place = () -> {
                BlockHitResult executionHit = resolveExecutionHit(client, current, selected);
                if (executionHit == null
                    || !client.player.getInventory().getStack(slot).isOf(selected.material().item())) {
                    return;
                }
                ActionResult result = client.interactionManager.interactBlock(
                    client.player, Hand.MAIN_HAND, executionHit);
                if (result instanceof ActionResult.Success success) {
                    if (success.swingSource() == ActionResult.SwingSource.CLIENT) {
                        client.player.swingHand(Hand.MAIN_HAND);
                    }
                    lastPlaceAge = client.player.age;
                    nextDirection = (selected.directionIndex() + 1) % TARGET_DIRECTIONS.length;
                }
            };

            if (slot == client.player.getInventory().getSelectedSlot()) {
                place.run();
            } else {
                SilentSlotManager.getInstance().runWithSlot(this, slot, PRIORITY,
                    current.toggle("Silent swap", true), place);
            }
        });
    }

    @Override
    protected void clearTarget() {
        plan = null;
    }

    @Override
    protected void resetFeatureState() {
        nextDirection = 0;
        lastPlaceAge = Integer.MIN_VALUE / 2;
    }

    /**
     * Resolves the interaction hit from Surround's packet-facing rotation.
     * The camera crosshair is deliberately not consulted: the returned hit is
     * the fresh world raycast produced by the rotation owned by this feature.
     */
    private BlockHitResult resolveExecutionHit(MinecraftClient client, ModuleContext context,
                                               PlacementPlan selected) {
        if (!basicStateValid(client)
            || client.world != selected.world()
            || !client.player.getBlockPos().equals(selected.playerBase())
            || !delayElapsed(context)
            || !validTarget(client, selected.target(), selected.material())) {
            return null;
        }

        RotationManager rotations = RotationManager.getInstance();
        if (!rotations.isOwnedBy(this)) {
            return null;
        }

        double range = Math.min(context.slider("Range", 4.5D),
            client.player.getBlockInteractionRange());
        if (range <= 0.0D) {
            return null;
        }

        Vec3d eyes = client.player.getEyePos();
        BlockHitResult rotationHit = client.world.raycast(new RaycastContext(
            eyes,
            eyes.add(rotations.serverRotation().direction().multiply(range)),
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            client.player
        ));
        BlockHitResult expected = selected.hit();
        if (rotationHit.getType() != HitResult.Type.BLOCK
            || !rotationHit.getBlockPos().equals(expected.getBlockPos())
            || rotationHit.getSide() != expected.getSide()
            || eyes.squaredDistanceTo(rotationHit.getPos()) > square(range) + 1.0E-7D
            || !client.player.canInteractWithBlockAt(rotationHit.getBlockPos(), 0.0D)
            || !validSupport(client, selected.target(), rotationHit)) {
            return null;
        }

        // Send the hit vector that actually corresponds to the silent server
        // rotation, not the planning-time face centre or the camera crosshair.
        return rotationHit;
    }

    private PlacementPlan createPlan(MinecraftClient client, BlockPos base, BlockPos target,
                                     Material material, double range, int directionIndex) {
        if (!validTarget(client, target, material)) {
            return null;
        }
        double interactionRange = Math.min(range, client.player.getBlockInteractionRange());
        if (interactionRange <= 0.0D) {
            return null;
        }
        for (Direction face : SUPPORT_FACES) {
            BlockPos support = target.offset(face.getOpposite());
            Vec3d point = Vec3d.ofCenter(support).add(
                face.getOffsetX() * 0.5D,
                face.getOffsetY() * 0.5D,
                face.getOffsetZ() * 0.5D
            );
            BlockHitResult hit = new BlockHitResult(point, face, support, false);
            if (client.player.getEyePos().squaredDistanceTo(point) <= square(interactionRange)
                && client.player.canInteractWithBlockAt(support, 0.0D)
                && validSupport(client, target, hit)
                && visibleSupport(client, hit)) {
                return new PlacementPlan(client.world, base, target, material, hit, directionIndex);
            }
        }
        return null;
    }

    private static boolean validTarget(MinecraftClient client, BlockPos target, Material material) {
        if (client.player == null || client.world == null
            || !client.world.isInBuildLimit(target)
            || !client.world.getWorldBorder().contains(target)) {
            return false;
        }
        BlockState existing = client.world.getBlockState(target);
        return existing.isReplaceable()
            && client.world.canPlace(material.block().getDefaultState(), target,
                ShapeContext.ofPlacement(client.player));
    }

    private static boolean validSupport(MinecraftClient client, BlockPos target, BlockHitResult hit) {
        if (client.world == null || hit == null
            || !hit.getBlockPos().offset(hit.getSide()).equals(target)) {
            return false;
        }
        BlockState support = client.world.getBlockState(hit.getBlockPos());
        return !support.isReplaceable()
            && support.isSideSolidFullSquare(client.world, hit.getBlockPos(), hit.getSide());
    }

    private static boolean visibleSupport(MinecraftClient client, BlockHitResult expected) {
        if (client.player == null || client.world == null) {
            return false;
        }
        Direction face = expected.getSide();
        Vec3d end = expected.getPos().subtract(
            face.getOffsetX() * 0.01D,
            face.getOffsetY() * 0.01D,
            face.getOffsetZ() * 0.01D
        );
        BlockHitResult ray = client.world.raycast(new RaycastContext(
            client.player.getEyePos(), end,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            client.player
        ));
        return ray.getType() == HitResult.Type.BLOCK
            && ray.getBlockPos().equals(expected.getBlockPos())
            && ray.getSide() == expected.getSide();
    }

    private boolean delayElapsed(ModuleContext context) {
        if (context.client().player == null) {
            return false;
        }
        if (context.client().player.age < lastPlaceAge) {
            lastPlaceAge = Integer.MIN_VALUE / 2;
        }
        int delay = Math.max(1, (int) Math.round(context.slider("Delay", 1.0D)));
        return context.client().player.age - lastPlaceAge >= delay;
    }

    private static boolean basicStateValid(MinecraftClient client) {
        return client.player != null
            && client.world != null
            && client.interactionManager != null
            && client.currentScreen == null
            && client.player.isAlive()
            && !client.player.isSpectator()
            && !client.player.hasVehicle() && !client.player.isUsingItem()
            && !client.interactionManager.isBreakingBlock();
    }

    private static Material findMaterial(MinecraftClient client, String preference) {
        Material preferred = preference.equalsIgnoreCase("Ender Chest")
            ? new Material(Items.ENDER_CHEST, Blocks.ENDER_CHEST)
            : new Material(Items.OBSIDIAN, Blocks.OBSIDIAN);
        Material fallback = preferred.item() == Items.OBSIDIAN
            ? new Material(Items.ENDER_CHEST, Blocks.ENDER_CHEST)
            : new Material(Items.OBSIDIAN, Blocks.OBSIDIAN);
        if (findHotbarSlot(client, preferred.item()) >= 0) {
            return preferred;
        }
        return findHotbarSlot(client, fallback.item()) >= 0 ? fallback : null;
    }

    private static int findHotbarSlot(MinecraftClient client, Item item) {
        if (client.player == null) {
            return -1;
        }
        int selected = client.player.getInventory().getSelectedSlot();
        if (client.player.getInventory().getStack(selected).isOf(item)) {
            return selected;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (client.player.getInventory().getStack(slot).isOf(item)) {
                return slot;
            }
        }
        return -1;
    }

    private static double square(double value) {
        return value * value;
    }

    private record Material(Item item, Block block) {
    }

    private record PlacementPlan(ClientWorld world, BlockPos playerBase, BlockPos target,
                                 Material material, BlockHitResult hit, int directionIndex) {
    }
}

