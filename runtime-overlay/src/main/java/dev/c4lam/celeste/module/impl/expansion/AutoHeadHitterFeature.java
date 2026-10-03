package dev.c4lam.celeste.module.impl.expansion;

import dev.c4lam.celeste.action.LocalInputOwnership;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

/** Tick-driven version of Volt/Silk Auto Head Hitter. */
public final class AutoHeadHitterFeature implements ModuleFeature {
    private int lastJumpAge = Integer.MIN_VALUE / 2;
    private boolean ownsJumpKey;

    @Override
    public String name() {
        return "Auto Head Hitter";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        MinecraftClient client = context.client();
        if (client.player == null || client.world == null || client.currentScreen != null
            || !client.player.isAlive() || !client.player.isOnGround() || client.player.hasVehicle()
            || client.player.isSneaking() || client.player.isTouchingWater() || client.player.isInLava()) {
            return;
        }
        if (client.player.input.getMovementInput().lengthSquared() < 1.0E-4F) {
            return;
        }
        int delay = Math.max(0, (int) Math.round(context.slider("Delay", 5.0D)));
        if (client.player.age < lastJumpAge) lastJumpAge = Integer.MIN_VALUE / 2;
        if (client.player.age - lastJumpAge < delay) {
            return;
        }
        BlockPos head = client.player.getBlockPos().up(2);
        if (client.world.getBlockState(head).isSolidBlock(client.world, head)) {
            ownsJumpKey = LocalInputOwnership.setSynthetic(
                client, client.options.jumpKey, true, ownsJumpKey);
            lastJumpAge = client.player.age;
        }
    }

    @Override
    public void onEndTick(ModuleContext context) {
        releaseJump(context);
    }

    @Override
    public void onDisable(ModuleContext context) {
        releaseJump(context);
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        releaseJump(context);
        lastJumpAge = Integer.MIN_VALUE / 2;
    }

    private void releaseJump(ModuleContext context) {
        LocalInputOwnership.release(context.client(), context.client().options.jumpKey, ownsJumpKey);
        ownsJumpKey = false;
    }
}
