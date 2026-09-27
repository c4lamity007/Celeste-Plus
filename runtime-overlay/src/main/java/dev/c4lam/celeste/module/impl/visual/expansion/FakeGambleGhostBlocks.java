package dev.c4lam.celeste.module.impl.visual.expansion;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Local block overlays. All interaction hooks return null for ordinary vanilla work. */
public final class FakeGambleGhostBlocks {
    private static final int MAX_CELLS = 1024;
    private static final int MAX_TRANSACTION = 128;
    private static final int RESTORE_FLAGS = Block.FORCE_STATE_AND_SKIP_CALLBACKS_AND_DROPS | Block.NOTIFY_LISTENERS;
    private static final Map<BlockPos, Cell> cells = new LinkedHashMap<>();
    private static ClientWorld world;
    private static Transaction transaction;
    private static BlockPos mining;
    private static ItemStack miningTool = ItemStack.EMPTY;
    private static float progress;
    private static boolean miningGesture;
    private static int soundTicks, lastProgressAge = Integer.MIN_VALUE, cooldownUntil;

    private FakeGambleGhostBlocks() { }

    /** Nullable result lets unowned items and ordinary world interactions pass unchanged. */
    public static ActionResult interactBlock(ClientPlayerEntity player, Hand hand, BlockHitResult hit) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (player == null || player != client.player || hit == null || !FakeGambleFeature.active() || client.world == null) return null;
        ItemStack held = player.getStackInHand(hand);
        ensureWorld(client.world);
        if (!FakeGambleFeature.isLocal(held)) {
            // Never let a real held item activate a client-only block on the server.
            return owned(hit.getBlockPos()) ? ActionResult.FAIL : null;
        }
        if (!(held.getItem() instanceof BlockItem item) || transaction != null
            || client.interactionManager == null || !player.canModifyBlocks()
            || !player.canInteractWithBlockAt(hit.getBlockPos(), 0)) return ActionResult.FAIL;
        ItemPlacementContext context = new ItemPlacementContext(player, hand, held, hit);
        ItemPlacementContext resolved = item.getPlacementContext(context);
        if (resolved == null || !world.getWorldBorder().contains(resolved.getBlockPos())
            || !world.isChunkLoaded(resolved.getBlockPos().getX() >> 4, resolved.getBlockPos().getZ() >> 4))
            return ActionResult.FAIL;
        int count = held.getCount();
        Transaction pending = new Transaction(item.getBlock(), resolved.getBlockPos().toImmutable(),
            FakeGambleFeature.createLocalDrop(held.copyWithCount(1)));
        transaction = pending;
        ActionResult result;
        try {
            // Call the item directly: placement state, collision, multipart blocks, sound and
            // hand consumption are vanilla, but no interaction-manager packet is constructed.
            beforeSetBlockState(world, resolved.getBlockPos());
            result = item.place(context);
            if (!result.isAccepted() || !commit(pending)) {
                rollback(pending);
                held.setCount(count);
                result = ActionResult.FAIL;
            }
        } catch (PlacementLimit ignored) {
            rollback(pending);
            held.setCount(count);
            result = ActionResult.FAIL;
        } catch (RuntimeException failure) {
            rollback(pending);
            held.setCount(count);
            FakeGambleFeature.inventoryChanged(player);
            throw failure;
        } finally {
            transaction = null;
        }
        FakeGambleFeature.inventoryChanged(player);
        return result;
    }

    public static ActionResult interactItem(PlayerEntity player, Hand hand) {
        return player != null && player == MinecraftClient.getInstance().player && FakeGambleFeature.active()
            && FakeGambleFeature.isLocal(player.getStackInHand(hand))
            ? ActionResult.FAIL : null;
    }

    /** Called at ClientWorld.setBlockState HEAD; inert outside our synchronous placement. */
    public static void beforeSetBlockState(ClientWorld changedWorld, BlockPos position) {
        Transaction pending = transaction;
        if (pending == null || changedWorld != world || pending.before.containsKey(position)) return;
        if (pending.before.size() >= MAX_TRANSACTION || cells.size() + pending.before.size() >= MAX_CELLS)
            throw new PlacementLimit();
        BlockPos pos = position.toImmutable();
        BlockState previous = world.getBlockState(pos);
        // Do not destroy server block-entity data that a BlockState snapshot cannot restore.
        if (previous.hasBlockEntity() && !cells.containsKey(pos)) throw new PlacementLimit();
        pending.before.put(pos, previous);
    }

    private static boolean commit(Transaction pending) {
        Set<Group> replaced = new LinkedHashSet<>();
        boolean placed = false;
        for (var entry : pending.before.entrySet()) {
            BlockState current = world.getBlockState(entry.getKey());
            // A connected fence/stair can rewrite a neighboring real block or a separately
            // placed ghost. Refuse that placement instead of claiming/removing its neighbor.
            // Multipart placement into air/replaceable cells remains supported.
            if (current != entry.getValue() && !entry.getKey().equals(pending.primary)
                && !entry.getValue().isReplaceable()) return false;
            if (current != entry.getValue() && current.isOf(pending.block)) placed = true;
        }
        if (!placed) return false;
        Group group = new Group(pending.drop);
        for (var entry : pending.before.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState current = world.getBlockState(pos);
            if (current == entry.getValue()) continue;
            Cell old = cells.get(pos);
            boolean mineable = current.isOf(pending.block) || old != null && old.mineable;
            if (old != null && old.mineable) replaced.add(old.group);
            cells.put(pos, new Cell(old == null ? entry.getValue() : old.original, current, group, mineable));
        }
        // Merging slabs/layers keeps earlier local material and the earliest server snapshot.
        for (Group previous : replaced) {
            group.drops.addAll(previous.drops);
            for (var entry : new ArrayList<>(cells.entrySet())) {
                Cell cell = entry.getValue();
                if (cell.group == previous)
                    cells.put(entry.getKey(), new Cell(cell.original, cell.expected, group, cell.mineable));
            }
        }
        return true;
    }

    private static void rollback(Transaction pending) {
        Transaction active = transaction;
        transaction = null;
        try {
            for (var entry : pending.before.entrySet()) world.setBlockState(entry.getKey(), entry.getValue(), RESTORE_FLAGS);
        } finally { transaction = active; }
    }

    public static Boolean attackBlock(BlockPos pos, Direction side) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!validClient(client)) return null;
        Cell cell = cells.get(pos);
        if (cell == null || !cell.mineable) {
            cancelBlockBreaking();
            return FakeGambleFeature.isLocal(client.player.getMainHandStack()) ? Boolean.FALSE : null;
        }
        if (!mayMine(client, pos, cell.expected)) { clearMining(); miningGesture = true; return Boolean.FALSE; }
        if (client.player.age < cooldownUntil) { miningGesture = true; return Boolean.TRUE; }
        if (!pos.equals(mining) || !ItemStack.areItemsAndComponentsEqual(miningTool, client.player.getMainHandStack())) {
            cancelBlockBreaking();
            // Abort a preceding real break normally before starting this local operation.
            client.interactionManager.cancelBlockBreaking();
            miningGesture = true;
            mining = pos.toImmutable();
            miningTool = client.player.getMainHandStack().copy();
            lastProgressAge = client.player.age;
            cell.expected.onBlockBreakStart(world, pos, client.player);
            if (client.player.isCreative() || cell.expected.calcBlockBreakingDelta(client.player, world, pos) >= 1)
                finish(client, pos, cell);
            else world.setBlockBreakingInfo(client.player.getId(), pos, -1);
        }
        return Boolean.TRUE;
    }

    public static Boolean updateBlockBreakingProgress(BlockPos pos, Direction side) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!validClient(client)) return null;
        Cell cell = cells.get(pos);
        if (cell == null || !cell.mineable) {
            if (miningGesture) { clearMining(); return Boolean.FALSE; }
            cancelBlockBreaking();
            return FakeGambleFeature.isLocal(client.player.getMainHandStack()) ? Boolean.FALSE : null;
        }
        if (!mayMine(client, pos, cell.expected)) { clearMining(); miningGesture = true; return Boolean.FALSE; }
        if (!pos.equals(mining) || !ItemStack.areItemsAndComponentsEqual(miningTool, client.player.getMainHandStack()))
            return attackBlock(pos, side);
        if (client.player.age < cooldownUntil || lastProgressAge == client.player.age) return Boolean.TRUE;
        lastProgressAge = client.player.age;
        float delta = cell.expected.calcBlockBreakingDelta(client.player, world, pos);
        if (!Float.isFinite(delta) || delta < 0) { clearMining(); return Boolean.FALSE; }
        progress += delta;
        if (soundTicks++ % 4 == 0) {
            var sound = cell.expected.getSoundGroup();
            world.playSoundAtBlockCenterClient(pos, sound.getHitSound(), SoundCategory.BLOCKS,
                (sound.getVolume() + 1) / 8, sound.getPitch() * .5F, false);
        }
        if (progress >= 1) finish(client, pos, cell);
        else world.setBlockBreakingInfo(client.player.getId(), pos, progress > 0 ? Math.min(9, (int) (progress * 10)) : -1);
        // MinecraftClient supplies the normal hit particles and hand swing for this result.
        return Boolean.TRUE;
    }

    public static Boolean breakBlock(BlockPos pos) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!validClient(client)) return null;
        Cell cell = cells.get(pos);
        if (cell == null || !cell.mineable)
            return miningGesture || FakeGambleFeature.isLocal(client.player.getMainHandStack()) ? Boolean.FALSE : null;
        // A direct call must not bypass local survival mining time.
        if (!mayMine(client, pos, cell.expected) || !client.player.isCreative() && progress < 1) return Boolean.FALSE;
        finish(client, pos, cell);
        return Boolean.TRUE;
    }

    public static boolean cancelBlockBreaking() {
        boolean handled = mining != null || miningGesture;
        miningGesture = false;
        clearMining();
        return handled;
    }

    public static boolean isMining() { return mining != null; }

    public static boolean isLocalBlock(ClientWorld candidateWorld, BlockPos pos) {
        return candidateWorld == world && pos != null && owned(pos);
    }

    private static void clearMining() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (mining != null && world != null && client.player != null)
            world.setBlockBreakingInfo(client.player.getId(), mining, -1);
        mining = null;
        miningTool = ItemStack.EMPTY;
        progress = 0;
        soundTicks = 0;
        lastProgressAge = Integer.MIN_VALUE;
    }

    private static boolean mayMine(MinecraftClient client, BlockPos pos, BlockState state) {
        return world.getBlockState(pos) == state && world.getWorldBorder().contains(pos)
            && client.currentScreen == null && client.player.canInteractWithBlockAt(pos, 0)
            && !client.player.isBlockBreakingRestricted(world, pos, client.interactionManager.getCurrentGameMode())
            && client.player.getMainHandStack().canMine(state, world, pos, client.player);
    }

    private static void finish(MinecraftClient client, BlockPos pos, Cell cell) {
        boolean drops = !client.player.isCreative() && client.player.canHarvest(cell.expected);
        world.addBlockBreakParticles(pos, cell.expected);
        var sound = cell.expected.getSoundGroup();
        world.playSoundAtBlockCenterClient(pos, sound.getBreakSound(), SoundCategory.BLOCKS,
            (sound.getVolume() + 1) / 2, sound.getPitch() * .8F, false);
        retire(cell.group, null, true);
        if (drops) for (ItemStack source : cell.group.drops) {
            ItemStack drop = miningDrop(source, client.player.getMainHandStack());
            FakeGambleFeature.spawnLocalDrop(world, new Vec3d(pos.getX() + .25 + world.random.nextDouble() * .5,
                pos.getY() + .25 + world.random.nextDouble() * .5 - .125,
                pos.getZ() + .25 + world.random.nextDouble() * .5), drop, 10);
        }
        cooldownUntil = client.player.age + 5;
    }

    /** Default prize ores follow vanilla silk/fortune/tool rules; arbitrary custom loot is server-only. */
    static ItemStack miningDrop(ItemStack source, ItemStack tool) {
        ItemStack drop = FakeGambleFeature.createLocalDrop(source);
        if (drop.isEmpty() || level(tool, Enchantments.SILK_TOUCH) > 0) return drop;
        if (source.isOf(Items.IRON_ORE) || source.isOf(Items.DEEPSLATE_IRON_ORE)
            || source.isOf(Items.DIAMOND_ORE) || source.isOf(Items.DEEPSLATE_DIAMOND_ORE)) {
            int fortune = Math.min(255, level(tool, Enchantments.FORTUNE));
            int multiplier = fortune == 0 ? 1 : Math.max(0, world.random.nextInt(fortune + 2) - 1) + 1;
            drop = new ItemStack(source.isOf(Items.IRON_ORE) || source.isOf(Items.DEEPSLATE_IRON_ORE)
                ? Items.RAW_IRON : Items.DIAMOND, multiplier);
            FakeGamblePrize.mark(drop);
        }
        return drop;
    }

    private static int level(ItemStack stack, RegistryKey<Enchantment> key) {
        for (var entry : stack.getEnchantments().getEnchantmentEntries())
            if (entry.getKey().matchesKey(key)) return Math.max(0, entry.getIntValue());
        return 0;
    }

    public static void serverBlockUpdate(ClientWorld changedWorld, BlockPos pos, BlockState state) {
        if (changedWorld != world) return;
        Cell cell = cells.get(pos);
        if (cell == null) return;
        // A refresh of the unchanged server cell is not a new block. Keep the local
        // state in the chunk so vanilla side/landing collision and sneak support all
        // see the same shape that is rendered. A genuinely changed server cell wins.
        if (state == cell.original && keepOverlay(pos, cell)) return;
        retire(cell.group, pos, true);
    }

    public static void chunkUnloaded(ClientWorld changedWorld, int chunkX, int chunkZ) {
        if (changedWorld != world) return;
        Set<Group> retired = new LinkedHashSet<>();
        for (var entry : new ArrayList<>(cells.entrySet())) {
            BlockPos pos = entry.getKey();
            if (pos.getX() >> 4 == chunkX && pos.getZ() >> 4 == chunkZ) {
                retired.add(entry.getValue().group);
                if (pos.equals(mining)) clearMining();
                cells.remove(pos);
            }
        }
        // Retire multipart counterparts too; never rewrite the chunk being unloaded.
        for (Group group : retired) retire(group, null, true);
    }

    public static void tick(ClientWorld nextWorld) {
        ensureWorld(nextWorld);
        MinecraftClient client = MinecraftClient.getInstance();
        if (miningGesture && (client.currentScreen != null || !client.isWindowFocused() || client.player == null
            || !client.player.isAlive())) cancelBlockBreaking();
        else if (mining != null && !ItemStack.areItemsAndComponentsEqual(miningTool, client.player.getMainHandStack())) clearMining();
        for (var entry : new ArrayList<>(cells.entrySet())) {
            BlockPos pos = entry.getKey();
            // An earlier multipart cell may already have retired this whole group.
            if (cells.get(pos) != entry.getValue()) continue;
            if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                chunkUnloaded(world, pos.getX() >> 4, pos.getZ() >> 4);
            } else if (!keepOverlay(pos, entry.getValue())) {
                retire(entry.getValue().group, pos, true);
            }
        }
    }

    private static boolean keepOverlay(BlockPos pos, Cell cell) {
        if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return false;
        BlockState current = world.getBlockState(pos);
        if (current == cell.expected) return true;
        if (current != cell.original) return false;
        // Vanilla may also restore an earlier prediction when its acknowledgement
        // arrives. Only reapply over the exact state saved before our placement.
        world.setBlockState(pos, cell.expected, RESTORE_FLAGS);
        return world.getBlockState(pos) == cell.expected;
    }

    public static void reset() {
        cancelBlockBreaking();
        if (world != null) for (Group group : new LinkedHashSet<>(cells.values().stream().map(Cell::group).toList()))
            retire(group, null, true);
        cells.clear();
        world = null;
        transaction = null;
        cooldownUntil = 0;
    }

    private static void retire(Group group, BlockPos authoritative, boolean restore) {
        for (var entry : new ArrayList<>(cells.entrySet())) {
            Cell cell = entry.getValue();
            if (cell.group != group) continue;
            BlockPos pos = entry.getKey();
            if (pos.equals(mining)) clearMining();
            cells.remove(pos);
            if (restore && !pos.equals(authoritative) && world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)
                && world.getBlockState(pos) == cell.expected) world.setBlockState(pos, cell.original, RESTORE_FLAGS);
        }
    }

    private static void ensureWorld(ClientWorld nextWorld) {
        if (world != nextWorld) { reset(); world = nextWorld; }
    }

    private static boolean validClient(MinecraftClient client) {
        if (!FakeGambleFeature.active() || client.world == null || client.player == null || client.interactionManager == null)
            return false;
        ensureWorld(client.world);
        return true;
    }

    private static boolean owned(BlockPos pos) { Cell cell = cells.get(pos); return cell != null && cell.mineable; }
    private record Cell(BlockState original, BlockState expected, Group group, boolean mineable) { }
    private static final class Group {
        final ArrayList<ItemStack> drops = new ArrayList<>();
        Group(ItemStack drop) { drops.add(drop); }
    }
    private static final class Transaction {
        final Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        final Block block;
        final BlockPos primary;
        final ItemStack drop;
        Transaction(Block block, BlockPos primary, ItemStack drop) {
            this.block = block; this.primary = primary; this.drop = drop;
        }
    }
    private static final class PlacementLimit extends RuntimeException { }
}
