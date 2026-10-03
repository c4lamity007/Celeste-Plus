package dev.c4lam.celeste.module.impl.expansion;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.action.GameplayFoundation;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.rotation.MoveFixMode;
import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.rotation.RotationTask;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/** One-shot, two-position vanilla cobweb placement based on Silk Cover Up. */
public final class CoverUpFeature implements ModuleFeature {
    private static final int PRIORITY = 88;
    private int step;
    private int lastActionAge = Integer.MIN_VALUE / 2;

    @Override public String name() { return "Cover Up"; }
    @Override public void onEnable(ModuleContext context) { step = 0; lastActionAge = Integer.MIN_VALUE / 2; }

    @Override
    public void onStartTick(ModuleContext context) {
        MinecraftClient client = context.client();
        if (client.player == null || client.world == null || client.interactionManager == null || client.currentScreen != null) return;
        if (step >= 2) { context.module().setEnabled(false); return; }
        int delay = Math.max(1, (int) Math.round(context.slider("Delay", 2)));
        if (client.player.age < lastActionAge) lastActionAge = Integer.MIN_VALUE / 2;
        if (client.player.age - lastActionAge < delay) return;
        int slot = findHotbar(client, Items.COBWEB);
        if (slot < 0) { context.module().setEnabled(false); return; }
        BlockPos base = client.player.getBlockPos();
        BlockPos target = step == 0 ? base : base.offset(client.player.getHorizontalFacing().getOpposite());
        BlockPos support = target.down();
        if (!client.world.getBlockState(target).isReplaceable()
            || !client.world.getBlockState(support).isSideSolidFullSquare(client.world, support, Direction.UP)) {
            step++;
            return;
        }
        Vec3d point = Vec3d.ofCenter(support).add(0, .5, 0);
        RotationManager.getInstance().submit(RotationTask.builder(this, Rotation.lookingAt(client.player, point))
            .priority(PRIORITY).lifetimeTicks(1).smoothing(180, 180).moveFix(MoveFixMode.STRICT).build());
        ActionScheduler.getInstance().submit(this, PRIORITY, actionClient -> {
            BlockHitResult hit = new BlockHitResult(point, Direction.UP, support, false);
            if (actionClient.player == null || actionClient.interactionManager == null
                || !actionClient.world.getBlockState(target).isReplaceable()
                || !actionClient.player.getInventory().getStack(slot).isOf(Items.COBWEB)
                || !RotationManager.getInstance().isOwnedBy(this)
                || !validServerHit(actionClient, hit)) return;
            SilentSlotManager.getInstance().runWithSlot(this, slot, PRIORITY,
                context.toggle("Silent swap", true), () -> {
                if (!validServerHit(actionClient, hit)) return;
                ActionResult result = actionClient.interactionManager.interactBlock(
                    actionClient.player, Hand.MAIN_HAND, hit);
                if (result instanceof ActionResult.Success success) {
                    if (success.swingSource() == ActionResult.SwingSource.CLIENT) actionClient.player.swingHand(Hand.MAIN_HAND);
                    step++;
                    lastActionAge = actionClient.player.age;
                    if (step >= 2) context.module().setEnabled(false);
                }
            });
        });
    }

    private static boolean validServerHit(MinecraftClient client, BlockHitResult expected) {
        if (client.player == null || client.world == null
            || !client.player.canInteractWithBlockAt(expected.getBlockPos(), 0.0D)) {
            return false;
        }
        double range = client.player.getBlockInteractionRange();
        Vec3d eyes = client.player.getEyePos();
        if (eyes.squaredDistanceTo(expected.getPos()) > range * range + 1.0E-7D) {
            return false;
        }
        Vec3d end = eyes.add(RotationManager.getInstance().serverRotation().direction().multiply(range));
        HitResult result = client.world.raycast(new RaycastContext(
            eyes, end, RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE, client.player));
        return result instanceof BlockHitResult hit
            && hit.getType() == HitResult.Type.BLOCK
            && hit.getBlockPos().equals(expected.getBlockPos())
            && hit.getSide() == expected.getSide();
    }

    private void reset() { step = 0; GameplayFoundation.releaseOwner(this); }
    @Override public void onDisable(ModuleContext context) { reset(); }
    @Override public void onWorldChanged(ModuleContext context) { reset(); }
    private static int findHotbar(net.minecraft.client.MinecraftClient client, net.minecraft.item.Item item) {
        if (client.player == null) return -1;
        for (int slot = 0; slot < 9; slot++) if (client.player.getInventory().getStack(slot).isOf(item)) return slot;
        return -1;
    }
}
