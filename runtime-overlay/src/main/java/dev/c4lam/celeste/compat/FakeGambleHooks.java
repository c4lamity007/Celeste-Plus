package dev.c4lam.celeste.compat;

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
import dev.c4lam.celeste.module.delivery.ModuleHooks;

/** Permanent game hook surface. Implementation is owned by the enabled module. */
public final class FakeGambleHooks {
 private FakeGambleHooks() { }
 public static final int SKIP = 0;
 public static final int TICK = 1;
 public static final int MERGE_AND_TICK = 2;
 public static ActionResult interactBlock(ClientPlayerEntity player, Hand hand, BlockHitResult hit) { return (ActionResult) ModuleHooks.invoke("fake_gamble", "gamble.interactBlock", null, player, hand, hit); }
 public static ActionResult interactItem(PlayerEntity player, Hand hand) { return (ActionResult) ModuleHooks.invoke("fake_gamble", "gamble.interactItem", null, player, hand); }
 public static ActionResult interactEntity(PlayerEntity player, Hand hand) { return (ActionResult) ModuleHooks.invoke("fake_gamble", "gamble.interactEntity", null, player, hand); }
 public static boolean attackEntity(PlayerEntity player) { return (boolean) ModuleHooks.invoke("fake_gamble", "gamble.attackEntity", false, player); }
 public static boolean packet(Packet<?> packet) { return (boolean) ModuleHooks.invoke("fake_gamble", "gamble.packet", false, packet); }
 public static void beforeSetBlockState(ClientWorld world, BlockPos pos) { ModuleHooks.invoke("fake_gamble", "gamble.beforeSetBlockState", null, world, pos); }
 public static Boolean attackBlock(BlockPos pos, Direction direction) { return (Boolean) ModuleHooks.invoke("fake_gamble", "gamble.attackBlock", null, pos, direction); }
 public static Boolean updateBlockBreakingProgress(BlockPos pos, Direction direction) { return (Boolean) ModuleHooks.invoke("fake_gamble", "gamble.updateBlockBreakingProgress", null, pos, direction); }
 public static Boolean breakBlock(BlockPos pos) { return (Boolean) ModuleHooks.invoke("fake_gamble", "gamble.breakBlock", null, pos); }
 public static boolean cancelBlockBreaking() { return (boolean) ModuleHooks.invoke("fake_gamble", "gamble.cancelBlockBreaking", false); }
 public static Boolean dropSelected(PlayerEntity player, boolean entireStack) { return (Boolean) ModuleHooks.invoke("fake_gamble", "gamble.dropSelected", null, player, entireStack); }
 public static boolean creativeStack(ItemStack stack, int slot) { return (boolean) ModuleHooks.invoke("fake_gamble", "gamble.creativeStack", false, stack, slot); }
 public static boolean creativeDrop(ItemStack stack) { return (boolean) ModuleHooks.invoke("fake_gamble", "gamble.creativeDrop", false, stack); }
 public static void serverBlockUpdate(ClientWorld world, BlockPos pos, BlockState state) { ModuleHooks.invoke("fake_gamble", "gamble.serverBlockUpdate", null, world, pos, state); }
 public static void chunkUnloaded(ClientWorld world, long packedChunk) { ModuleHooks.invoke("fake_gamble", "gamble.chunkUnloaded", null, world, packedChunk); }
 public static boolean worldEvent(WorldEventHandler handler, ClientWorld world, int eventId, BlockPos pos, int data) { return (boolean) ModuleHooks.invoke("fake_gamble", "gamble.worldEvent", false, handler, world, eventId, pos, data); }
 public static int itemTickAction(ItemEntity self) { return (int) ModuleHooks.invoke("fake_gamble", "gamble.itemTickAction", 0, self); }
 public static boolean handleClick(int syncId, int slotId, int button, net.minecraft.screen.slot.SlotActionType action, PlayerEntity player) { return (boolean) ModuleHooks.invoke("fake_gamble", "gamble.handleClick", false, syncId, slotId, button, action, player); }
 public static boolean tryPickup(ItemEntity item, PlayerEntity player) { return (boolean) ModuleHooks.invoke("fake_gamble", "gamble.tryPickup", false, item, player); }
 public static void clientItemTick(ItemEntity item) { ModuleHooks.invoke("fake_gamble", "gamble.clientItemTick", null, item); }
 public static void reapplyAfterSync() { ModuleHooks.invoke("fake_gamble", "gamble.reapplyAfterSync", null); }
 public static void stashCursor(net.minecraft.screen.ScreenHandler handler, PlayerEntity player) { ModuleHooks.invoke("fake_gamble", "gamble.stashCursor", null, handler, player); }
 public static void noteUsedDispenser(BlockHitResult hit) { ModuleHooks.invoke("fake_gamble", "gamble.noteUsedDispenser", null, hit); }
 public static boolean onPickBlock(MinecraftClient client) { return (boolean) ModuleHooks.invoke("fake_gamble", "gamble.onPickBlock", false, client); }
 public static void ensureScreenPrizes(net.minecraft.client.gui.screen.ingame.Generic3x3ContainerScreen screen) { ModuleHooks.invoke("fake_gamble", "gamble.ensureScreenPrizes", null, screen); }
}
