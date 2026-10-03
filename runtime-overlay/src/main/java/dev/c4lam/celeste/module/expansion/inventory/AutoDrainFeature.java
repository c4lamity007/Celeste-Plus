package dev.c4lam.celeste.module.expansion.inventory;

import dev.c4lam.celeste.action.OwnFluidTracker;
import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.action.GameplayFoundation;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.rotation.LocalRotationProjection;
import dev.c4lam.celeste.rotation.MoveFixMode;
import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.rotation.RotationTask;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/** Flick-aims at nearby still water or lava, buckets it, then looks back. */
public final class AutoDrainFeature implements ModuleFeature {
    private static final int PRIORITY = 130;
    private int lastActionAge = Integer.MIN_VALUE / 2;
    private Plan plan;

    @Override
    public String name() {
        return "Auto Drain";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        plan = null;
        MinecraftClient client = context.client();
        if (!AutoDrainInventorySupport.playable(client) || !delayElapsed(context)) {
            GameplayFoundation.releaseOwner(this);
            return;
        }
        int slot = AutoDrainInventorySupport.hotbarSlot(client, Items.BUCKET);
        BlockPos source = findSource(client);
        if (slot < 0 || source == null) {
            GameplayFoundation.releaseOwner(this);
            return;
        }
        Vec3d point = Vec3d.ofCenter(source);
        plan = new Plan(client.world, source, slot, AutoDrainInventorySupport.lookAt(client.player, point));
        RotationManager.getInstance().submit(RotationTask.builder(this, plan.rotation())
            .priority(PRIORITY)
            .lifetimeTicks(1)
            .smoothing(180.0F, 180.0F)
            .sensitivityQuantized(true)
            .moveFix(MoveFixMode.STRICT)
            .build());
    }

    @Override
    public void onActionTick(ModuleContext context) {
        Plan selected = plan;
        if (selected == null || !RotationManager.getInstance().isOwnedBy(this)) {
            return;
        }
        ActionScheduler.getInstance().submit(this, PRIORITY, client -> {
            try {
                if (!AutoDrainInventorySupport.playable(client) || client.world != selected.world()
                    || !context.module().enabled() || !delayElapsed(context)
                    || !isSource(client, selected.source())
                    || OwnFluidTracker.getInstance().isOwn(selected.source()) || selected.slot() > 8
                    || !client.player.getInventory().getStack(selected.slot()).isOf(Items.BUCKET)
                    || !RotationManager.getInstance().isOwnedBy(this)) {
                    return;
                }
                SilentSlotManager.getInstance().runWithSlot(this, selected.slot(), PRIORITY,
                    context.toggle("Silent swap", true), () -> {
                    if (isSource(client, selected.source())
                        && Boolean.TRUE.equals(LocalRotationProjection.run(client, this,
                            () -> AutoDrainInventorySupport.useItem(client, Hand.MAIN_HAND)))) {
                        lastActionAge = client.player.age;
                    }
                });
            } finally {
                GameplayFoundation.releaseOwner(this);
            }
        });
    }

    private static BlockPos findSource(MinecraftClient client) {
        if (client.player == null || client.world == null || OwnFluidTracker.getInstance().hasClaim()) {
            return null;
        }
        double reach = client.player.getBlockInteractionRange();
        Vec3d eyes = client.player.getEyePos();
        BlockPos origin = BlockPos.ofFloored(eyes);
        int radius = Math.max(1, (int) Math.ceil(reach));
        BlockPos best = null;
        double bestDistance = reach * reach;
        for (BlockPos pos : BlockPos.iterate(
            origin.add(-radius, -radius, -radius),
            origin.add(radius, radius, radius)
        )) {
            if (!isSource(client, pos) || OwnFluidTracker.getInstance().isOwn(pos)) {
                continue;
            }
            Vec3d center = Vec3d.ofCenter(pos);
            double distance = eyes.squaredDistanceTo(center);
            if (distance > bestDistance || !hasLineOfSight(client, eyes, center)) {
                continue;
            }
            bestDistance = distance;
            best = pos.toImmutable();
        }
        return best;
    }

    private static boolean hasLineOfSight(MinecraftClient client, Vec3d eyes, Vec3d target) {
        HitResult result = client.world.raycast(new RaycastContext(
            eyes,
            target,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            client.player
        ));
        return result.getType() == HitResult.Type.MISS
            || eyes.squaredDistanceTo(result.getPos()) + 1.0E-4D >= eyes.squaredDistanceTo(target);
    }

    private static boolean isSource(MinecraftClient client, BlockPos pos) {
        if (client.world == null || pos == null) {
            return false;
        }
        var fluid = client.world.getFluidState(pos);
        if (!fluid.isStill()) {
            return false;
        }
        Fluid type = fluid.getFluid();
        return type == Fluids.WATER || type == Fluids.LAVA;
    }

    private boolean delayElapsed(ModuleContext context) {
        return context.client().player != null && context.client().player.age - lastActionAge
            >= Math.max(1, (int) Math.round(context.slider("Delay", 4.0D)));
    }

    @Override
    public void onDisable(ModuleContext context) {
        reset();
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        reset();
    }

    private void reset() {
        plan = null;
        lastActionAge = Integer.MIN_VALUE / 2;
        GameplayFoundation.releaseOwner(this);
    }

    private record Plan(ClientWorld world, BlockPos source, int slot, Rotation rotation) {
    }
}
