package net.minecraft.client.network;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.silent.SilentSlotManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

/** Bounded protocol model derived from cached Minecraft 1.21.11 named bytecode.
 * interactBlock predicts inside a new sequence then emits USE even for FAIL/PASS.
 * Client anchor detonation returns CONSUME without removing the block. The server
 * sees ordered uses independently; acknowledgement delivery is controlled by tests.
 * This is not a server implementation or proof of multiplayer acceptance.
 */
public final class ClientPlayerInteractionManager {
    public record Event(long tick, String action, int slot, BlockPos hit, int sequence) { }
    public record Packet(long tick, String kind, int slot) { }
    public final List<Event> events = new ArrayList<>(), serverEvents = new ArrayList<>();
    public final List<Packet> packets = new ArrayList<>(), orderViolations = new ArrayList<>();
    public final Map<BlockPos, BlockState> serverBlocks = new HashMap<>();
    public final Set<Integer> throwAttempts = new HashSet<>(), failAttempts = new HashSet<>(), passAttempts = new HashSet<>();
    public final Map<BlockPos, BlockState> pendingAcknowledgements = new HashMap<>();
    public int lastSelectedSlot, sequence, vanillaUses;
    public boolean rejectServerReplacement;
    private boolean rightClicking;
    private final MinecraftClient client;

    public ClientPlayerInteractionManager(MinecraftClient client) { this.client = client; }
    public void packet(String kind, int slot) {
        Packet packet = new Packet(ActionScheduler.getInstance().tickNumber(), kind, slot);
        packets.add(packet);
        if (kind.equals("HELD_ITEM_CHANGE") && rightClicking) orderViolations.add(packet);
        if (kind.equals("USE")) rightClicking = true;
        if (kind.equals("MOVEMENT")) rightClicking = false;
    }
    public void movement() { packet("MOVEMENT", lastSelectedSlot); }
    public void syncSelectedSlot() {
        int selected = SilentSlotManager.getInstance().serverSlotOr(client.player.inventory.selected);
        if (lastSelectedSlot != selected) { lastSelectedSlot = selected; packet("HELD_ITEM_CHANGE", selected); }
    }
    public ActionResult interactBlock(ClientPlayerEntity player, Hand hand, BlockHitResult hit) {
        syncSelectedSlot();
        if (!client.world.insideBorder) return ActionResult.FAIL;
        int currentSequence = ++sequence;
        ItemStack stack = player.getMainHandStack();
        String predicted = action(client.world.blocks, stack, hit);
        events.add(new Event(ActionScheduler.getInstance().tickNumber(), predicted, lastSelectedSlot, hit.getBlockPos(), currentSequence));
        if (throwAttempts.contains(events.size())) throw new IllegalStateException("Synthetic prediction failure");
        ActionResult result;
        if (failAttempts.contains(events.size())) result = ActionResult.FAIL;
        else if (passAttempts.contains(events.size())) result = ActionResult.PASS;
        else if (predicted.equals("explode")) result = ActionResult.CONSUME;
        else if (predicted.equals("place") || predicted.equals("charge")) {
            apply(client.world.blocks, predicted, hit);
            stack.decrement(1);
            result = ActionResult.SUCCESS;
        } else result = ActionResult.PASS;
        packet("USE", lastSelectedSlot);
        String actual = action(serverBlocks, new ItemStack(stack.getItem()), hit);
        if (rejectServerReplacement && serverEvents.stream().anyMatch(event -> event.action().equals("explode"))
            && actual.equals("place")) actual = "rejected-place";
        serverEvents.add(new Event(ActionScheduler.getInstance().tickNumber(), actual, lastSelectedSlot, hit.getBlockPos(), currentSequence));
        apply(serverBlocks, actual, hit);
        pendingAcknowledgements.putAll(serverBlocks);
        return result;
    }
    private String action(Map<BlockPos, BlockState> blocks, ItemStack stack, BlockHitResult hit) {
        BlockState state = blocks.getOrDefault(hit.getBlockPos(), Blocks.AIR.getDefaultState());
        if (state.isOf(Blocks.RESPAWN_ANCHOR) && stack.isOf(Items.GLOWSTONE) && state.charges() < 4) return "charge";
        if (state.isOf(Blocks.RESPAWN_ANCHOR) && state.charges() > 0) return "explode";
        if (stack.isOf(Items.RESPAWN_ANCHOR)) {
            BlockPos target = state.isReplaceable() ? hit.getBlockPos() : hit.getBlockPos().offset(hit.getSide());
            return blocks.getOrDefault(target, Blocks.AIR.getDefaultState()).isReplaceable() ? "place" : "blocked";
        }
        return "other";
    }
    private void apply(Map<BlockPos, BlockState> blocks, String action, BlockHitResult hit) {
        BlockState state = blocks.getOrDefault(hit.getBlockPos(), Blocks.AIR.getDefaultState());
        if (action.equals("explode")) blocks.put(hit.getBlockPos(), Blocks.AIR.getDefaultState());
        if (action.equals("charge")) blocks.put(hit.getBlockPos(), state.with(RespawnAnchorBlock.CHARGES, state.charges() + 1));
        if (action.equals("place")) {
            BlockPos target = state.isReplaceable() ? hit.getBlockPos() : hit.getBlockPos().offset(hit.getSide());
            blocks.put(target, Blocks.RESPAWN_ANCHOR.getDefaultState());
        }
    }
    public void acknowledge() { client.world.blocks.putAll(pendingAcknowledgements); pendingAcknowledgements.clear(); }
    public void simulateVanillaUse() {
        if (client.options.useKey.pressed || client.options.useKey.queued > 0) {
            vanillaUses++;
            if (client.crosshairTarget instanceof BlockHitResult hit) interactBlock(client.player, Hand.MAIN_HAND, hit);
        }
    }
    public void attackEntity(ClientPlayerEntity player, Entity entity) { throw new AssertionError("Unexpected crystal branch"); }
}
