package dev.c4lam.celeste.compat;

import dev.c4lam.celeste.module.impl.visual.expansion.FakeGambleFeature;
import net.minecraft.block.Blocks;
import net.minecraft.block.DispenserBlock;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.world.WorldEventHandler;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldEvents;

/** Shared original client-local dispenser-event and item-physics behavior. */
public final class FakeGambleHooks {
    public static final int SKIP = 0;
    public static final int TICK = 1;
    public static final int MERGE_AND_TICK = 2;
    private FakeGambleHooks() { }

    public static boolean worldEvent(WorldEventHandler handler, ClientWorld world, int eventId, BlockPos pos, int data) {
        if (eventId != WorldEvents.DISPENSER_FAILS || !FakeGambleFeature.onEmptyDispenserFired(world, pos)) return false;
        handler.processWorldEvent(WorldEvents.DISPENSER_DISPENSES, pos, data);
        var state = world.getBlockState(pos);
        if (state.isOf(Blocks.DISPENSER)) {
            handler.processWorldEvent(WorldEvents.DISPENSER_ACTIVATED, pos, state.get(DispenserBlock.FACING).getIndex());
        }
        return true;
    }

    public static int itemTickAction(ItemEntity self) {
        World world = self.getEntityWorld();
        if (!world.isClient() || !FakeGambleFeature.isFakeDrop(self) || self.isRemoved()) return SKIP;
        boolean moved = MathHelper.floor(self.lastX) != MathHelper.floor(self.getX())
            || MathHelper.floor(self.lastY) != MathHelper.floor(self.getY())
            || MathHelper.floor(self.lastZ) != MathHelper.floor(self.getZ());
        return self.age % (moved ? 2 : 40) == 0 ? MERGE_AND_TICK : TICK;
    }
}
