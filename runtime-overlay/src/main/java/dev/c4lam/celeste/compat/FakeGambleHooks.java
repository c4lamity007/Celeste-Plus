package dev.c4lam.celeste.compat;

import dev.c4lam.celeste.module.impl.visual.expansion.FakeGambleFeature;
import dev.c4lam.celeste.module.impl.visual.expansion.FakeGambleGhostBlocks;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.block.DispenserBlock;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.world.WorldEventHandler;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldEvents;

/** Shared original client-local dispenser-event and item-physics behavior. */
public final class FakeGambleHooks {
    public static final int SKIP = 0;
    public static final int TICK = 1;
    public static final int MERGE_AND_TICK = 2;
    private FakeGambleHooks() { }

    public static ActionResult interactBlock(ClientPlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!localPlayer(player)) return null;
        if (hand == Hand.MAIN_HAND && hit != null && FakeGambleFeature.isLocal(player.getStackInHand(hand))
            && !player.shouldCancelInteraction()) {
            ClientWorld world = MinecraftClient.getInstance().world;
            BlockPos pos = hit.getBlockPos();
            BlockState state = world.getBlockState(pos);
            if (!FakeGambleGhostBlocks.isLocalBlock(world, pos)
                && (state.isOf(Blocks.DISPENSER) || state.isOf(Blocks.DROPPER))) {
                if (!player.canInteractWithBlockAt(pos, 0) || !world.getWorldBorder().contains(pos)
                    || !world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return ActionResult.FAIL;
                // Vanilla opens these blocks before using the held item. Its block action
                // always consumes, so the local prize cannot reach packet-backed placement.
                // Sneaking with an item intentionally keeps local placement priority.
                FakeGambleFeature.noteUsedDispenser(hit);
                return null;
            }
        }
        ActionResult result = FakeGambleGhostBlocks.interactBlock(player, hand, hit);
        if (result == null) FakeGambleFeature.noteUsedDispenser(hit);
        return result;
    }
    public static ActionResult interactItem(PlayerEntity player, Hand hand) {
        return localPlayer(player) ? FakeGambleGhostBlocks.interactItem(player, hand) : null;
    }
    public static ActionResult interactEntity(PlayerEntity player, Hand hand) {
        return localPlayer(player) && FakeGambleFeature.isLocal(player.getStackInHand(hand)) ? ActionResult.FAIL : null;
    }
    public static boolean attackEntity(PlayerEntity player) {
        return localPlayer(player) && FakeGambleFeature.isLocal(player.getMainHandStack());
    }
    public static boolean packet(Packet<?> packet) {
        if (!FakeGambleFeature.active() || !(packet instanceof PlayerActionC2SPacket action)
                || action.getAction() != PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null && client.isOnThread() && FakeGambleFeature.swapLocalOffhand(client.player);
    }
    private static boolean localPlayer(PlayerEntity player) {
        if (!FakeGambleFeature.active() || player == null) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null && client.isOnThread() && client.player == player
            && client.world != null && player.getEntityWorld() == client.world;
    }
    public static void beforeSetBlockState(ClientWorld world, BlockPos pos) {
        if (clientWorld(world)) FakeGambleGhostBlocks.beforeSetBlockState(world, pos);
    }
    private static boolean localClient() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null && localPlayer(client.player);
    }
    private static boolean clientWorld(ClientWorld world) {
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null && client.isOnThread() && world != null && world == client.world;
    }
    public static Boolean attackBlock(BlockPos pos, Direction direction) { return localClient() ? FakeGambleGhostBlocks.attackBlock(pos, direction) : null; }
    public static Boolean updateBlockBreakingProgress(BlockPos pos, Direction direction) { return localClient() ? FakeGambleGhostBlocks.updateBlockBreakingProgress(pos, direction) : null; }
    public static Boolean breakBlock(BlockPos pos) { return localClient() ? FakeGambleGhostBlocks.breakBlock(pos) : null; }
    public static boolean cancelBlockBreaking() { return localClient() && FakeGambleGhostBlocks.cancelBlockBreaking(); }
    public static Boolean dropSelected(PlayerEntity player, boolean entireStack) {
        return localPlayer(player) && FakeGambleFeature.dropSelectedLocal(player, entireStack) ? Boolean.TRUE : null;
    }
    public static boolean creativeStack(ItemStack stack, int slot) {
        return localClient() && FakeGambleFeature.handleCreativeStack(stack, slot);
    }
    public static boolean creativeDrop(ItemStack stack) {
        return localClient() && FakeGambleFeature.dropCreativeLocal(stack);
    }
    public static void serverBlockUpdate(ClientWorld world, BlockPos pos, BlockState state) {
        if (clientWorld(world)) FakeGambleGhostBlocks.serverBlockUpdate(world, pos, state);
    }
    public static void chunkUnloaded(ClientWorld world, long packedChunk) {
        if (clientWorld(world)) FakeGambleGhostBlocks.chunkUnloaded(world, ChunkPos.getPackedX(packedChunk), ChunkPos.getPackedZ(packedChunk));
    }

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
