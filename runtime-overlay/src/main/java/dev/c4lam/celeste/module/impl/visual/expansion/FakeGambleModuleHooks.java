package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.compat.*;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.delivery.ModuleHookRegistry;

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
public final class FakeGambleModuleHooks {
    public static final int SKIP = 0;
    public static final int TICK = 1;
    public static final int MERGE_AND_TICK = 2;
    private FakeGambleModuleHooks() { }

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

 public static boolean handleClick(int syncId, int slotId, int button, net.minecraft.screen.slot.SlotActionType action, PlayerEntity player) { return FakeGambleFeature.handleClick(syncId, slotId, button, action, player); }
 public static boolean tryPickup(ItemEntity item, PlayerEntity player) { return FakeGambleFeature.tryPickup(item, player); }
 public static void clientItemTick(ItemEntity item) { FakeGambleFeature.clientItemTick(item); }
 public static void reapplyAfterSync() { FakeGambleFeature.reapplyAfterSync(); }
 public static void stashCursor(net.minecraft.screen.ScreenHandler handler, PlayerEntity player) { FakeGambleFeature.stashCursor(handler, player); }
 public static void noteUsedDispenser(BlockHitResult hit) { FakeGambleFeature.noteUsedDispenser(hit); }
 public static boolean onPickBlock(MinecraftClient client) { return FakeGambleFeature.onPickBlock(client); }
 public static void ensureScreenPrizes(net.minecraft.client.gui.screen.ingame.Generic3x3ContainerScreen screen) { FakeGambleFeature.ensureScreenPrizes(screen); }


 public static void bind(ModuleServices services) {
  services.hook("gamble.interactBlock", a -> FakeGambleModuleHooks.interactBlock((ClientPlayerEntity) a[0], (Hand) a[1], (BlockHitResult) a[2]));
  services.hook("gamble.interactItem", a -> FakeGambleModuleHooks.interactItem((PlayerEntity) a[0], (Hand) a[1]));
  services.hook("gamble.interactEntity", a -> FakeGambleModuleHooks.interactEntity((PlayerEntity) a[0], (Hand) a[1]));
  services.hook("gamble.attackEntity", a -> FakeGambleModuleHooks.attackEntity((PlayerEntity) a[0]));
  services.hook("gamble.packet", a -> FakeGambleModuleHooks.packet((Packet<?>) a[0]));
  services.hook("gamble.beforeSetBlockState", a -> { FakeGambleModuleHooks.beforeSetBlockState((ClientWorld) a[0], (BlockPos) a[1]); return null; });
  services.hook("gamble.attackBlock", a -> FakeGambleModuleHooks.attackBlock((BlockPos) a[0], (Direction) a[1]));
  services.hook("gamble.updateBlockBreakingProgress", a -> FakeGambleModuleHooks.updateBlockBreakingProgress((BlockPos) a[0], (Direction) a[1]));
  services.hook("gamble.breakBlock", a -> FakeGambleModuleHooks.breakBlock((BlockPos) a[0]));
  services.hook("gamble.cancelBlockBreaking", a -> FakeGambleModuleHooks.cancelBlockBreaking());
  services.hook("gamble.dropSelected", a -> FakeGambleModuleHooks.dropSelected((PlayerEntity) a[0], (boolean) a[1]));
  services.hook("gamble.creativeStack", a -> FakeGambleModuleHooks.creativeStack((ItemStack) a[0], (int) a[1]));
  services.hook("gamble.creativeDrop", a -> FakeGambleModuleHooks.creativeDrop((ItemStack) a[0]));
  services.hook("gamble.serverBlockUpdate", a -> { FakeGambleModuleHooks.serverBlockUpdate((ClientWorld) a[0], (BlockPos) a[1], (BlockState) a[2]); return null; });
  services.hook("gamble.chunkUnloaded", a -> { FakeGambleModuleHooks.chunkUnloaded((ClientWorld) a[0], (long) a[1]); return null; });
  services.hook("gamble.worldEvent", a -> FakeGambleModuleHooks.worldEvent((WorldEventHandler) a[0], (ClientWorld) a[1], (int) a[2], (BlockPos) a[3], (int) a[4]));
  services.hook("gamble.itemTickAction", a -> FakeGambleModuleHooks.itemTickAction((ItemEntity) a[0]));
  services.hook("gamble.handleClick", a -> FakeGambleModuleHooks.handleClick((int) a[0], (int) a[1], (int) a[2], (net.minecraft.screen.slot.SlotActionType) a[3], (PlayerEntity) a[4]));
  services.hook("gamble.tryPickup", a -> FakeGambleModuleHooks.tryPickup((ItemEntity) a[0], (PlayerEntity) a[1]));
  services.hook("gamble.clientItemTick", a -> { FakeGambleModuleHooks.clientItemTick((ItemEntity) a[0]); return null; });
  services.hook("gamble.reapplyAfterSync", a -> { FakeGambleModuleHooks.reapplyAfterSync(); return null; });
  services.hook("gamble.stashCursor", a -> { FakeGambleModuleHooks.stashCursor((net.minecraft.screen.ScreenHandler) a[0], (PlayerEntity) a[1]); return null; });
  services.hook("gamble.noteUsedDispenser", a -> { FakeGambleModuleHooks.noteUsedDispenser((BlockHitResult) a[0]); return null; });
  services.hook("gamble.onPickBlock", a -> FakeGambleModuleHooks.onPickBlock((MinecraftClient) a[0]));
  services.hook("gamble.ensureScreenPrizes", a -> { FakeGambleModuleHooks.ensureScreenPrizes((net.minecraft.client.gui.screen.ingame.Generic3x3ContainerScreen) a[0]); return null; });
 }
 public static void bindLegacy(ModuleHookRegistry registry) {
  registry.connect("fake_gamble", 0, "gamble.interactBlock", a -> FakeGambleModuleHooks.interactBlock((ClientPlayerEntity) a[0], (Hand) a[1], (BlockHitResult) a[2]));
  registry.connect("fake_gamble", 0, "gamble.interactItem", a -> FakeGambleModuleHooks.interactItem((PlayerEntity) a[0], (Hand) a[1]));
  registry.connect("fake_gamble", 0, "gamble.interactEntity", a -> FakeGambleModuleHooks.interactEntity((PlayerEntity) a[0], (Hand) a[1]));
  registry.connect("fake_gamble", 0, "gamble.attackEntity", a -> FakeGambleModuleHooks.attackEntity((PlayerEntity) a[0]));
  registry.connect("fake_gamble", 0, "gamble.packet", a -> FakeGambleModuleHooks.packet((Packet<?>) a[0]));
  registry.connect("fake_gamble", 0, "gamble.beforeSetBlockState", a -> { FakeGambleModuleHooks.beforeSetBlockState((ClientWorld) a[0], (BlockPos) a[1]); return null; });
  registry.connect("fake_gamble", 0, "gamble.attackBlock", a -> FakeGambleModuleHooks.attackBlock((BlockPos) a[0], (Direction) a[1]));
  registry.connect("fake_gamble", 0, "gamble.updateBlockBreakingProgress", a -> FakeGambleModuleHooks.updateBlockBreakingProgress((BlockPos) a[0], (Direction) a[1]));
  registry.connect("fake_gamble", 0, "gamble.breakBlock", a -> FakeGambleModuleHooks.breakBlock((BlockPos) a[0]));
  registry.connect("fake_gamble", 0, "gamble.cancelBlockBreaking", a -> FakeGambleModuleHooks.cancelBlockBreaking());
  registry.connect("fake_gamble", 0, "gamble.dropSelected", a -> FakeGambleModuleHooks.dropSelected((PlayerEntity) a[0], (boolean) a[1]));
  registry.connect("fake_gamble", 0, "gamble.creativeStack", a -> FakeGambleModuleHooks.creativeStack((ItemStack) a[0], (int) a[1]));
  registry.connect("fake_gamble", 0, "gamble.creativeDrop", a -> FakeGambleModuleHooks.creativeDrop((ItemStack) a[0]));
  registry.connect("fake_gamble", 0, "gamble.serverBlockUpdate", a -> { FakeGambleModuleHooks.serverBlockUpdate((ClientWorld) a[0], (BlockPos) a[1], (BlockState) a[2]); return null; });
  registry.connect("fake_gamble", 0, "gamble.chunkUnloaded", a -> { FakeGambleModuleHooks.chunkUnloaded((ClientWorld) a[0], (long) a[1]); return null; });
  registry.connect("fake_gamble", 0, "gamble.worldEvent", a -> FakeGambleModuleHooks.worldEvent((WorldEventHandler) a[0], (ClientWorld) a[1], (int) a[2], (BlockPos) a[3], (int) a[4]));
  registry.connect("fake_gamble", 0, "gamble.itemTickAction", a -> FakeGambleModuleHooks.itemTickAction((ItemEntity) a[0]));
  registry.connect("fake_gamble", 0, "gamble.handleClick", a -> FakeGambleModuleHooks.handleClick((int) a[0], (int) a[1], (int) a[2], (net.minecraft.screen.slot.SlotActionType) a[3], (PlayerEntity) a[4]));
  registry.connect("fake_gamble", 0, "gamble.tryPickup", a -> FakeGambleModuleHooks.tryPickup((ItemEntity) a[0], (PlayerEntity) a[1]));
  registry.connect("fake_gamble", 0, "gamble.clientItemTick", a -> { FakeGambleModuleHooks.clientItemTick((ItemEntity) a[0]); return null; });
  registry.connect("fake_gamble", 0, "gamble.reapplyAfterSync", a -> { FakeGambleModuleHooks.reapplyAfterSync(); return null; });
  registry.connect("fake_gamble", 0, "gamble.stashCursor", a -> { FakeGambleModuleHooks.stashCursor((net.minecraft.screen.ScreenHandler) a[0], (PlayerEntity) a[1]); return null; });
  registry.connect("fake_gamble", 0, "gamble.noteUsedDispenser", a -> { FakeGambleModuleHooks.noteUsedDispenser((BlockHitResult) a[0]); return null; });
  registry.connect("fake_gamble", 0, "gamble.onPickBlock", a -> FakeGambleModuleHooks.onPickBlock((MinecraftClient) a[0]));
  registry.connect("fake_gamble", 0, "gamble.ensureScreenPrizes", a -> { FakeGambleModuleHooks.ensureScreenPrizes((net.minecraft.client.gui.screen.ingame.Generic3x3ContainerScreen) a[0]); return null; });
 }
}
