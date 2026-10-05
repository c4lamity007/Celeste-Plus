package dev.c4lam.celeste.module.impl.expansion;

import dev.c4lam.celeste.rotation.LocalRotationProjection;
import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.action.GameplayFoundation;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.rotation.MoveFixMode;
import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.rotation.RotationTask;
import dev.c4lam.celeste.action.OwnFluidTracker;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Vanilla water-bucket cobweb escape derived from Cyemer WebBreaker. */
public final class WebBreakerFeature implements ModuleFeature {
    private static final int PRIORITY = 145;
    private static final int CONFIRM_TIMEOUT_TICKS = 12;
    private static final int PICKUP_RETRY_TICKS = 8;
    private BlockPos waterPos;
    private int placedAge;
    private int pickupRequestedAge = Integer.MIN_VALUE / 2;

    @Override public String name() { return "Web Breaker"; }

    @Override
    public void onStartTick(ModuleContext context) {
        MinecraftClient client = context.client();
        if (client.player == null || client.world == null || client.interactionManager == null || client.currentScreen != null) return;
        if (waterPos != null) {
            RotationManager.getInstance().release(this);
            pickup(context);
            return;
        }
        BlockPos web = client.player.getBlockPos();
        if (!client.world.getBlockState(web).isOf(Blocks.COBWEB)) return;
        BlockPos support = web.down();
        int slot = findHotbar(client, Items.WATER_BUCKET);
        if (slot < 0 || !client.world.getBlockState(support).isSideSolidFullSquare(client.world, support, Direction.UP)) return;
        Vec3d point = Vec3d.ofCenter(support).add(0, 0.5, 0);
        request(client, point, PRIORITY);
        ActionScheduler.getInstance().submit(this, PRIORITY, actionClient -> {
            if (actionClient.player == null || actionClient.interactionManager == null
                || !actionClient.world.getBlockState(web).isOf(Blocks.COBWEB)
                || !actionClient.player.getInventory().getStack(slot).isOf(Items.WATER_BUCKET)
                || !RotationManager.getInstance().isOwnedBy(this)) return;
            SilentSlotManager.getInstance().runWithSlot(this, slot, PRIORITY,
                context.toggle("Silent swap", true), () -> {
                ActionResult result = LocalRotationProjection.run(actionClient, this,
                    () -> actionClient.interactionManager.interactItem(actionClient.player, Hand.MAIN_HAND));
                if (result instanceof ActionResult.Success success) {
                    if (success.swingSource() == ActionResult.SwingSource.CLIENT) actionClient.player.swingHand(Hand.MAIN_HAND);
                    setWater(web, actionClient.player.age);
                }
            });
        });
    }

    private void pickup(ModuleContext context) {
        MinecraftClient client = context.client();
        if (client.player == null || client.world == null || client.interactionManager == null || waterPos == null) return;
        int placementAge = client.player.age - placedAge;
        if (!client.world.getBlockState(waterPos).isOf(Blocks.WATER)) {
            if (pickupRequestedAge > Integer.MIN_VALUE / 4
                || placementAge > CONFIRM_TIMEOUT_TICKS) clearWater();
            return;
        }
        if (placementAge > 30) {
            clearWater();
            return;
        }
        if (!context.toggle("Pick up", true) || placementAge < 3) return;
        if (pickupRequestedAge > Integer.MIN_VALUE / 4
            && client.player.age - pickupRequestedAge < PICKUP_RETRY_TICKS) return;
        int slot = findHotbar(client, Items.BUCKET);
        if (slot < 0) return;
        BlockPos target = waterPos;
        Vec3d point = Vec3d.ofCenter(target);
        request(client, point, PRIORITY - 1);
        ActionScheduler.getInstance().submit(this, PRIORITY - 1, actionClient -> {
            if (actionClient.player == null || actionClient.interactionManager == null
                || !actionClient.world.getBlockState(target).isOf(Blocks.WATER)
                || !actionClient.player.getInventory().getStack(slot).isOf(Items.BUCKET)
                || !RotationManager.getInstance().isOwnedBy(this)) return;
            SilentSlotManager.getInstance().runWithSlot(this, slot, PRIORITY - 1,
                context.toggle("Silent swap", true), () -> {
                ActionResult result = LocalRotationProjection.run(actionClient, this,
                    () -> actionClient.interactionManager.interactItem(actionClient.player, Hand.MAIN_HAND));
                if (result instanceof ActionResult.Success) pickupRequestedAge = actionClient.player.age;
            });
        });
    }

    private void request(MinecraftClient client, Vec3d point, int priority) {
        RotationManager.getInstance().submit(RotationTask.builder(this, Rotation.lookingAt(client.player, point))
            .priority(priority).lifetimeTicks(1).smoothing(180, 180).moveFix(MoveFixMode.STRICT).build());
    }

    private void setWater(BlockPos pos, int age) {
        clearWater();
        waterPos = pos.toImmutable();
        placedAge = age;
        pickupRequestedAge = Integer.MIN_VALUE / 2;
        OwnFluidTracker.getInstance().claim(waterPos);
    }

    private void clearWater() {
        if (waterPos != null) {
            OwnFluidTracker.getInstance().release(waterPos);
        }
        waterPos = null;
    }

    private void reset() {
        clearWater();
        pickupRequestedAge = Integer.MIN_VALUE / 2;
        GameplayFoundation.releaseOwner(this);
    }
    @Override public void onDisable(ModuleContext context) { reset(); }
    @Override public void onWorldChanged(ModuleContext context) { reset(); }
    private static int findHotbar(net.minecraft.client.MinecraftClient client, net.minecraft.item.Item item) {
        if (client.player == null) return -1;
        for (int slot = 0; slot < 9; slot++) if (client.player.getInventory().getStack(slot).isOf(item)) return slot;
        return -1;
    }
}
