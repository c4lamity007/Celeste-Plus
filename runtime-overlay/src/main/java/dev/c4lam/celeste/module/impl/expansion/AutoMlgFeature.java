package dev.c4lam.celeste.module.impl.expansion;

import dev.c4lam.celeste.rotation.LocalRotationProjection;
import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.action.GameplayFoundation;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.action.OwnFluidTracker;
import dev.c4lam.celeste.rotation.MoveFixMode;
import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.rotation.RotationTask;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Fall-aware water placement based on Silk Auto MLG, without camera mutation. */
public final class AutoMlgFeature implements ModuleFeature {
    private static final int PRIORITY = 150;
    private static final int CONFIRM_TIMEOUT_TICKS = 12;
    private static final int PICKUP_RETRY_TICKS = 8;
    private Plan plan;
    private BlockPos placedWater;
    private int placedAtAge;
    private int pickupRequestedAtAge = Integer.MIN_VALUE / 2;

    @Override public String name() { return "Auto MLG"; }

    @Override
    public void onStartTick(ModuleContext context) {
        plan = null;
        MinecraftClient client = context.client();
        if (placedWater != null) {
            RotationManager.getInstance().release(this);
            tryPickup(context);
            return;
        }
        if (!validFalling(client, context.slider("Fall distance", 6.0D), context.toggle("Ignore windcharge", true))) {
            RotationManager.getInstance().release(this);
            return;
        }
        int slot = findHotbar(client, Items.WATER_BUCKET);
        BlockPos ground = groundBelow(client, 6);
        if (slot < 0 || ground == null) {
            RotationManager.getInstance().release(this);
            return;
        }
        BlockPos water = ground.up();
        if (!client.world.getBlockState(water).isReplaceable()) return;
        Vec3d point = Vec3d.ofCenter(ground).add(0.0D, 0.5D, 0.0D);
        plan = new Plan(client.world, ground, water, point, slot);
        RotationManager.getInstance().submit(RotationTask.builder(this, Rotation.lookingAt(client.player, point))
            .priority(PRIORITY).lifetimeTicks(1).smoothing(180, 180)
            .sensitivityQuantized(true).moveFix(MoveFixMode.STRICT).build());
    }

    @Override
    public void onActionTick(ModuleContext context) {
        Plan selected = plan;
        if (selected == null) return;
        ActionScheduler.getInstance().submit(this, PRIORITY, client -> {
            if (!context.module().enabled() || client.player == null || client.interactionManager == null
                || client.world != selected.world() || !validFalling(client, context.slider("Fall distance", 6.0D), context.toggle("Ignore windcharge", true))
                || !client.player.getInventory().getStack(selected.slot()).isOf(Items.WATER_BUCKET)
                || !client.world.getBlockState(selected.water()).isReplaceable()
                || !RotationManager.getInstance().isOwnedBy(this)) return;
            SilentSlotManager.getInstance().runWithSlot(this, selected.slot(), PRIORITY,
                context.toggle("Silent swap", true), () -> {
                ActionResult result = LocalRotationProjection.run(client, this,
                    () -> client.interactionManager.interactItem(client.player, Hand.MAIN_HAND));
                if (result instanceof ActionResult.Success success) {
                    if (success.swingSource() == ActionResult.SwingSource.CLIENT) client.player.swingHand(Hand.MAIN_HAND);
                    clearWater();
                    placedWater = selected.water();
                    OwnFluidTracker.getInstance().claim(placedWater);
                    placedAtAge = client.player.age;
                    pickupRequestedAtAge = Integer.MIN_VALUE / 2;
                }
            });
        });
    }

    private void tryPickup(ModuleContext context) {
        MinecraftClient client = context.client();
        if (client.player == null || client.world == null
            || client.interactionManager == null || client.currentScreen != null
            || placedWater == null) {
            return;
        }
        int placementAge = client.player.age - placedAtAge;
        // Abandon recovery even when the bucket is gone or the server never
        // accepts pickup. Otherwise this phase can block every later clutch.
        if (placementAge < 0 || placementAge > 30 || !context.toggle("Pick up", true)) {
            clearWater();
            return;
        }
        if (!client.world.getBlockState(placedWater).isOf(Blocks.WATER)) {
            if (pickupRequestedAtAge > Integer.MIN_VALUE / 4
                || placementAge > CONFIRM_TIMEOUT_TICKS) clearWater();
            return;
        }
        if (placementAge < 3
            || client.player.squaredDistanceTo(Vec3d.ofCenter(placedWater)) > 25.0D) {
            return;
        }
        if (pickupRequestedAtAge > Integer.MIN_VALUE / 4
            && client.player.age - pickupRequestedAtAge < PICKUP_RETRY_TICKS) {
            return;
        }
        int slot = findHotbar(client, Items.BUCKET);
        if (slot < 0) return;
        Vec3d point = Vec3d.ofCenter(placedWater);
        RotationManager.getInstance().submit(RotationTask.builder(this, Rotation.lookingAt(client.player, point))
            .priority(PRIORITY - 1).lifetimeTicks(1).smoothing(180, 180)
            .moveFix(MoveFixMode.STRICT).build());
        ActionScheduler.getInstance().submit(this, PRIORITY - 1, actionClient -> {
            if (actionClient.player == null || actionClient.interactionManager == null || placedWater == null
                || !actionClient.world.getBlockState(placedWater).isOf(Blocks.WATER)
                || !actionClient.player.getInventory().getStack(slot).isOf(Items.BUCKET)
                || !RotationManager.getInstance().isOwnedBy(this)) return;
            SilentSlotManager.getInstance().runWithSlot(this, slot, PRIORITY - 1,
                context.toggle("Silent swap", true), () -> {
                ActionResult result = LocalRotationProjection.run(actionClient, this,
                    () -> actionClient.interactionManager.interactItem(actionClient.player, Hand.MAIN_HAND));
                if (result instanceof ActionResult.Success) pickupRequestedAtAge = actionClient.player.age;
            });
        });
    }

    private boolean validFalling(MinecraftClient client, double fallDistance, boolean ignoreWindcharge) {
        if (client.player == null || client.world == null || client.interactionManager == null
            || client.currentScreen != null || !client.player.isAlive() || client.player.isOnGround()
            || client.player.isGliding() || client.player.isTouchingWater()
            || client.player.getVelocity().y >= -0.35D || client.player.fallDistance < fallDistance) {
            return false;
        }
        if (ignoreWindcharge && hasWindChargeProtection(client)) {
            return false;
        }
        return true;
    }

    private boolean hasWindChargeProtection(MinecraftClient client) {
        if (client.player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.SLOW_FALLING)) {
            return true;
        }
        if (client.world != null) {
            for (net.minecraft.entity.Entity entity : client.world.getEntitiesByClass(
                net.minecraft.entity.projectile.WindChargeEntity.class,
                client.player.getBoundingBox().expand(6.0D), e -> true)) {
                double dy = client.player.getY() - entity.getY();
                if (dy > -2.0D && dy < 10.0D) {
                    return true;
                }
            }
        }
        return false;
    }

    private static BlockPos groundBelow(MinecraftClient client, int maximum) {
        BlockPos base = client.player.getBlockPos();
        for (int distance = 1; distance <= maximum; distance++) {
            BlockPos candidate = base.down(distance);
            if (client.world.getBlockState(candidate).isSideSolidFullSquare(client.world, candidate, Direction.UP)) return candidate;
        }
        return null;
    }

    static int findHotbar(MinecraftClient client, net.minecraft.item.Item item) {
        if (client.player == null) return -1;
        for (int slot = 0; slot < 9; slot++) if (client.player.getInventory().getStack(slot).isOf(item)) return slot;
        return -1;
    }

    private void clearWater() {
        if (placedWater != null) OwnFluidTracker.getInstance().release(placedWater);
        placedWater = null;
    }

    private void reset() {
        plan = null;
        clearWater();
        pickupRequestedAtAge = Integer.MIN_VALUE / 2;
        GameplayFoundation.releaseOwner(this);
    }
    @Override public void onDisable(ModuleContext context) { reset(); }
    @Override public void onWorldChanged(ModuleContext context) { reset(); }
    private record Plan(net.minecraft.client.world.ClientWorld world, BlockPos ground, BlockPos water, Vec3d point, int slot) { }
}
