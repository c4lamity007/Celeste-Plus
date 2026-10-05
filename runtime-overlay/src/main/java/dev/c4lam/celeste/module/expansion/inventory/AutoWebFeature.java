package dev.c4lam.celeste.module.expansion.inventory;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.action.GameplayFoundation;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

/** Volt/Silk AutoWeb behavior using only the aimed vanilla support face. */
public final class AutoWebFeature implements ModuleFeature {
    private static final int PRIORITY = 155;
    private boolean actionDown;
    private int remaining;
    private int expiresAtAge;
    private int lastActionAge = Integer.MIN_VALUE / 2;
    private Plan plan;

    @Override
    public String name() {
        return "Auto Web";
    }

    @Override
    public void onEnable(ModuleContext context) {
        actionDown = AutoWebInventorySupport.actionKeyDown(context);
    }

    @Override
    public void onStartTick(ModuleContext context) {
        MinecraftClient client = context.client();
        boolean down = AutoWebInventorySupport.actionKeyDown(context);
        boolean rising = down && !actionDown;
        actionDown = down;
        plan = null;
        if (!AutoWebInventorySupport.playable(client) || !client.isWindowFocused() || client.getOverlay() != null) {
            cancel();
            return;
        }
        if (rising) {
            remaining = Math.max(1, Math.min(3, (int) Math.round(context.slider("Count", 1.0D))));
            expiresAtAge = client.player.age + 40;
        }
        if (remaining <= 0 || client.player.age > expiresAtAge || !delayElapsed(context)
            || !(client.crosshairTarget instanceof BlockHitResult hit) || !valid(client, hit)) {
            if (remaining <= 0 || client.player.age > expiresAtAge) GameplayFoundation.releaseOwner(this);
            return;
        }
        int slot = AutoWebInventorySupport.hotbarSlot(client, Items.COBWEB);
        if (slot < 0) {
            cancel();
            return;
        }
        plan = new Plan(client.world, hit, slot, AutoWebInventorySupport.lookAt(client.player, hit.getPos()));
        AutoWebInventorySupport.requestRotation(this, client.player, hit.getPos(), PRIORITY, 180.0F, 90.0F);
    }

    @Override
    public void onActionTick(ModuleContext context) {
        Plan selected = plan;
        if (selected == null || !AutoWebInventorySupport.rotationReady(this, selected.rotation, 8.0F)) return;
        ActionScheduler.getInstance().submit(this, PRIORITY, client -> {
            if (!AutoWebInventorySupport.playable(client) || !client.isWindowFocused() || client.getOverlay() != null
                || client.world != selected.world || !context.module().enabled()
                || remaining <= 0 || client.player.age > expiresAtAge || !delayElapsed(context)
                || !AutoWebInventorySupport.sameHit(client.crosshairTarget, selected.hit) || !valid(client, selected.hit)
                || selected.slot > 8 || !client.player.getInventory().getStack(selected.slot).isOf(Items.COBWEB)
                || !AutoWebInventorySupport.rotationReady(this, selected.rotation, 8.0F)) return;
            SilentSlotManager.getInstance().runWithSlot(this, selected.slot, PRIORITY,
                context.toggle("Silent swap", true), () -> {
                if (valid(client, selected.hit)
                    && AutoWebInventorySupport.interactBlock(client, Hand.MAIN_HAND, selected.hit)) {
                    lastActionAge = client.player.age;
                    remaining--;
                }
            });
        });
    }

    private static boolean valid(MinecraftClient client, BlockHitResult hit) {
        if (client.player == null || client.world == null || hit == null
            || client.player.squaredDistanceTo(hit.getPos()) > 20.25D) return false;
        BlockPos target = hit.getBlockPos().offset(hit.getSide());
        return client.world.isInBuildLimit(target) && client.world.getWorldBorder().contains(target)
            && client.world.getBlockState(target).isReplaceable()
            && client.world.canPlace(Blocks.COBWEB.getDefaultState(), target, ShapeContext.ofPlacement(client.player))
            && !target.equals(client.player.getBlockPos()) && !target.equals(client.player.getBlockPos().up());
    }

    private boolean delayElapsed(ModuleContext context) {
        return context.client().player != null && context.client().player.age - lastActionAge
            >= Math.max(1, (int) Math.round(context.slider("Delay", 2.0D)));
    }

    @Override
    public void onDisable(ModuleContext context) { reset(); }

    @Override
    public void onWorldChanged(ModuleContext context) { reset(); }

    private void reset() {
        actionDown = false;
        remaining = 0;
        expiresAtAge = 0;
        lastActionAge = Integer.MIN_VALUE / 2;
        cancel();
    }

    private void cancel() {
        remaining = 0;
        expiresAtAge = 0;
        plan = null;
        GameplayFoundation.releaseOwner(this);
    }

    private record Plan(ClientWorld world, BlockHitResult hit, int slot, Rotation rotation) { }
}
