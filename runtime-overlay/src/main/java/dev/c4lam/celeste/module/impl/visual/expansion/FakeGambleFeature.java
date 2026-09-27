package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.render.RenderUtil;
import dev.c4lam.celeste.render.font.Fonts;
import dev.c4lam.celeste.module.expansion.inventory.SilentInventoryScreen;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.block.Blocks;
import net.minecraft.block.DispenserBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.particle.ItemPickupParticle;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.Generic3x3ContainerScreen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.Generic3x3ContainerScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.HopperScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

import java.awt.Color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

/**
 * Client-only dispenser gamble: selected empty dispensers hold renamed 1-9
 * prizes. Activation, pickup, and putting items back never leave this client.
 * Dropped prizes use vanilla dispenser spawn + item-entity merge/physics.
 */
public final class FakeGambleFeature implements ModuleFeature {
    private static final Item[] DEFAULT_PRIZES = {
        Items.DIRT, Items.OAK_LOG, Items.OAK_PLANKS, Items.IRON_ORE, Items.IRON_BLOCK,
        Items.DIAMOND_ORE, Items.NETHERITE_SWORD, Items.TRIDENT, Items.MACE
    };
    private static final Item[] prizes = DEFAULT_PRIZES.clone();
    private static final int DISPENSE_SPEED = 6;
    private static final AtomicInteger CLIENT_IDS = new AtomicInteger(-71_000_000);

    private static volatile boolean enabled;
    private static final FakeGambleSelection<BlockPos> selection = new FakeGambleSelection<>();
    private static final Map<BlockPos, ItemStack[]> dispensers = new ConcurrentHashMap<>();
    // Vanilla inventory index 40 is offhand; 36..39 are real equipment slots.
    private static final ItemStack[] playerGhost = new ItemStack[41];
    private static final Set<ItemEntity> ground = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<BlockPos> pendingFires = new LinkedHashSet<>();
    private static ItemStack cursor = ItemStack.EMPTY;
    private static BlockPos openPos;
    private static double win1Percent = 50;
    private static double win2Percent = 50;
    private static String tieMode = "Reroll";
    private static volatile boolean selectionMarkers = true;
    private static volatile boolean roundDisplay = true;
    private static volatile long displayDurationNanos = 3_500_000_000L;
    private static volatile RoundResult lastRound;

    static {
        java.util.Arrays.fill(playerGhost, ItemStack.EMPTY);
    }

    @Override
    public String name() {
        return "Fake Gamble";
    }

    @Override
    public void onEnable(ModuleContext context) {
        enabled = true;
    }

    @Override
    public void onStartTick(ModuleContext context) {
        enabled = true;
        MinecraftClient client = context.client();
        if (client.player == null || !client.player.isAlive()) lastRound = null;
        if (client.player == null || client.world == null) {
            return;
        }
        FakeGambleGhostBlocks.tick(client.world);
        syncConfiguredPrizes(context);
        win1Percent = context.slider("1 Win %", 50);
        win2Percent = context.slider("2 Win %", 50);
        tieMode = context.mode("Tie", "Reroll");
        selectionMarkers = context.toggle("Selection markers", true);
        roundDisplay = context.toggle("Round display", true);
        displayDurationNanos = (long) (MathHelper.clamp(context.slider("Display duration", 3.5D), 1D, 8D)
            * 1_000_000_000D);
        pruneGround();
        bindOpenDispenser(client);
        applyAll(client);
        stashCursorIfScreenClosed(client);
    }

    @Override
    public void onEndTick(ModuleContext context) {
        enabled = true;
        MinecraftClient client = context.client();
        if (client.world instanceof ClientWorld world) {
            flushPending(world);
        }
        if (client.player != null) {
            bindOpenDispenser(client);
            applyAll(client);
        }
    }

    @Override
    public void onDisable(ModuleContext context) {
        enabled = false;
        resetWorldState(context.client(), true);
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        resetWorldState(context.client(), false);
    }

    public static boolean active() {
        return enabled;
    }

    public static void noteUsedDispenser(BlockHitResult hit) {
        if (!enabled) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        // A normal/unselected interaction must not inherit a previously opened selection.
        openPos = null;
        selection.noteUsed(hit != null && client.world != null
            && client.world.getBlockState(hit.getBlockPos()).isOf(Blocks.DISPENSER)
            ? hit.getBlockPos().toImmutable() : null);
    }

    public static boolean onPickBlock(MinecraftClient client) {
        // Both the startup mixin and Celeste+ bridge route the queued pick action here.
        // Polling GLFW as well toggles the same physical click a second time.
        if (enabled && client != null && client.world != null && client.currentScreen == null
            && client.crosshairTarget instanceof BlockHitResult hit
            && FakeGambleGhostBlocks.isLocalBlock(client.world, hit.getBlockPos())) return true;
        return primeFromLook(client);
    }

    public static void ensureScreenPrizes(Screen screen) {
        if (!enabled || !(screen instanceof Generic3x3ContainerScreen) || !isDispenserTitle(screen.getTitle())) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        bindOpenDispenser(client);
        if (client.player != null) {
            applyDispenserSlots(client.player.currentScreenHandler);
        }
    }

    public static boolean isFakeDrop(ItemEntity entity) {
        return entity != null && (ground.contains(entity) || isLocal(entity.getStack()));
    }

    public static void clientItemTick(ItemEntity entity) {
        if (!enabled || entity == null || entity.isRemoved() || !isFakeDrop(entity)) {
            return;
        }
        if (!(entity.getEntityWorld() instanceof ClientWorld)) {
            return;
        }
        if (entity.getItemAge() >= 6000) {
            ground.remove(entity);
            entity.discard();
        }
    }

    public static int kindOf(ItemStack stack) {
        return FakeGamblePrize.kindOf(stack);
    }

    public static boolean isLocal(ItemStack stack) {
        return FakeGamblePrize.isLocal(stack);
    }

    /** A mined local block returns its normal item name while retaining local ownership. */
    public static ItemStack createLocalDrop(ItemStack source) {
        return FakeGamblePrize.unnamedCopy(source);
    }

    /** Commit an intentional local count change before the next sync restores ghost slots. */
    public static void inventoryChanged(PlayerEntity player) {
        if (enabled && player != null && player == MinecraftClient.getInstance().player)
            snapshotGhost(player.getInventory());
    }

    public static ItemStack create(int kind) {
        return create(kind, 1);
    }

    public static ItemStack create(int kind, int count) {
        if (kind < 0 || kind >= prizes.length || count <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(prizes[kind], count);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(Integer.toString(kind + 1)));
        FakeGamblePrize.mark(stack);
        return stack;
    }

    public static boolean handleClick(int syncId, int slotId, int button, SlotActionType actionType, PlayerEntity player) {
        if (!enabled || player == null || !(player.getEntityWorld() instanceof ClientWorld)) {
            return false;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != player || client.player.currentScreenHandler.syncId != syncId) {
            return false;
        }
        ScreenHandler handler = client.player.currentScreenHandler;
        if (isReal(handler.getCursorStack())) {
            cursor = ItemStack.EMPTY;
        } else if (!isLocal(cursor) && isLocal(handler.getCursorStack())) {
            cursor = copyOf(handler.getCursorStack());
        }
        snapshotGhost(player.getInventory());
        int inv = playerInvIndex(handler, slotId);
        boolean disp = isDispenserContainerSlot(handler, slotId);
        ItemStack slotStack = ItemStack.EMPTY;
        if (disp) {
            ItemStack live = handler.getSlot(slotId).getStack();
            if (isReal(live)) {
                state(openPos)[slotId] = ItemStack.EMPTY;
                slotStack = live;
            } else {
                slotStack = state(openPos)[slotId];
            }
        } else if (inv >= 0) {
            slotStack = !playerGhost[inv].isEmpty() ? playerGhost[inv] : player.getInventory().getStack(inv);
        }
        boolean cursorPrize = isLocal(cursor);
        boolean slotPrize = isLocal(slotStack);
        boolean swapPrize = actionType == SlotActionType.SWAP && (button >= 0 && button < 9 || button == 40)
            && (isLocal(playerGhost[button]) || isLocal(player.getInventory().getStack(button)));
        if (disp && isReal(slotStack) && (cursorPrize || swapPrize)) return true;
        if (actionType == SlotActionType.QUICK_MOVE && !cursorPrize
            && slotId >= 0 && slotId < handler.slots.size()
            && isReal(handler.getSlot(slotId).getStack()) && hasLocalSlots(handler)) {
            return quickMoveReal(handler, player, client, slotId);
        }
        if (actionType == SlotActionType.THROW && slotId == ScreenHandler.EMPTY_SPACE_SLOT_INDEX && cursorPrize) {
            spawnDropped(client.world, player, copyOf(cursor));
            setCursor(handler, ItemStack.EMPTY);
            return true;
        }
        if (!cursorPrize && !slotPrize && !swapPrize) {
            return false;
        }
        if (isReal(handler.getCursorStack())) return true;
        return switch (actionType) {
            case PICKUP -> clickPickup(handler, player, client, slotId, inv, disp, button);
            case QUICK_MOVE -> clickQuickMove(handler, player, slotId, inv, disp);
            case SWAP -> clickSwap(handler, player, slotId, inv, disp, button);
            case THROW -> clickThrow(handler, player, client, slotId, inv, disp, button);
            case PICKUP_ALL -> clickPickupAll(handler, player);
            default -> true;
        };
    }

    public static boolean onEmptyDispenserFired(ClientWorld world, BlockPos pos) {
        if (!enabled || world == null || !selection.contains(pos)
            || MinecraftClient.getInstance().world != world) {
            return false;
        }
        if (!world.getBlockState(pos).isOf(Blocks.DISPENSER)) {
            return false;
        }
        if (!hasAny(state(pos.toImmutable()))) {
            return false;
        }
        pendingFires.add(pos.toImmutable());
        return true;
    }

    public static boolean tryPickup(ItemEntity entity, PlayerEntity player) {
        if (!enabled || entity == null || player == null || !(player.getEntityWorld() instanceof ClientWorld world)) {
            return false;
        }
        if (!isFakeDrop(entity)) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (player != client.player || client.world != world || entity.getEntityWorld() != world
            || !player.isAlive() || player.isSpectator() || entity.isRemoved() || entity.cannotPickup()) return true;
        ItemStack stack = entity.getStack();
        if (!isLocal(stack)) return true;
        int before = stack.getCount();
        // Vanilla insertion can return false on its final, full-inventory loop
        // even after moving part of the stack. The count delta is authoritative.
        ItemStack remaining = stack.copy();
        player.getInventory().insertStack(remaining);
        int accepted = before - remaining.getCount();
        if (accepted <= 0) return true;
        snapshotGhost(player.getInventory());
        try {
            // Match the normal client pickup packet's captured render state,
            // three-tick collect particle and randomized pickup sound.
            var renderState = client.getEntityRenderDispatcher().getAndUpdateRenderState(entity, 1.0F);
            client.particleManager.addParticle(new ItemPickupParticle(world, renderState, player, entity.getVelocity()));
            world.playSoundClient(entity.getX(), entity.getY(), entity.getZ(),
                SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.2F,
                (world.getRandom().nextFloat() - world.getRandom().nextFloat()) * 1.4F + 2.0F, false);
        } finally {
            if (remaining.isEmpty()) {
                ground.remove(entity);
                entity.discard();
            } else {
                entity.setStack(remaining);
            }
        }
        return true;
    }

    public static void reapplyAfterSync() {
        if (!enabled) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            bindOpenDispenser(client);
            applyAll(client);
        }
    }

    public static void stashCursor(ScreenHandler handler, PlayerEntity player) {
        if (!enabled || handler == null || player == null) {
            return;
        }
        if (isReal(handler.getCursorStack())) {
            // A late server update may arrive after the last tick reconciliation.
            cursor = ItemStack.EMPTY;
            return;
        }
        ItemStack held = !cursor.isEmpty() ? cursor : handler.getCursorStack();
        if (!isLocal(held)) {
            return;
        }
        insertIntoInventory(player, held);
        cursor = ItemStack.EMPTY;
        handler.setCursorStack(ItemStack.EMPTY);
    }

    private static boolean primeFromLook(MinecraftClient client) {
        if (!enabled || client == null || client.currentScreen != null || client.world == null
            || client.player == null || !client.player.isAlive()) {
            return false;
        }
        if (!(client.crosshairTarget instanceof BlockHitResult hit)) {
            return false;
        }
        if (!client.world.getBlockState(hit.getBlockPos()).isOf(Blocks.DISPENSER)) {
            return false;
        }
        BlockPos pos = hit.getBlockPos().toImmutable();
        selection.toggle(pos);
        pendingFires.removeIf(position -> !selection.contains(position));
        lastRound = null;
        return true;
    }

    private static void bindOpenDispenser(MinecraftClient client) {
        if (client == null || !isDispenserScreen(client.currentScreen)) {
            openPos = null;
            return;
        }
        BlockPos candidate = selection.openTarget(true);
        openPos = candidate != null && client.world != null
            && client.world.getBlockState(candidate).isOf(Blocks.DISPENSER) ? candidate : null;
        if (openPos != null) state(openPos);
    }

    private static boolean isDispenserScreen(Screen screen) {
        return screen instanceof Generic3x3ContainerScreen && isDispenserTitle(screen.getTitle());
    }

    private static boolean isOpenDispenser(ScreenHandler handler) {
        return handler instanceof Generic3x3ContainerScreenHandler
            && selection.contains(openPos)
            && isDispenserScreen(MinecraftClient.getInstance().currentScreen);
    }

    private static boolean isDispenserTitle(Text title) {
        if (title == null) {
            return false;
        }
        if (title.getContent() instanceof TranslatableTextContent trans
            && "container.dispenser".equals(trans.getKey())) {
            return true;
        }
        return "Dispenser".equals(title.getString());
    }

    private static ItemStack[] state(BlockPos pos) {
        if (!selection.contains(pos)) {
            throw new IllegalStateException("Fake Gamble inventory requires an explicitly selected dispenser");
        }
        return dispensers.computeIfAbsent(pos.toImmutable(), key -> freshPrizes());
    }

    private static ItemStack[] freshPrizes() {
        ItemStack[] slots = new ItemStack[9];
        for (int i = 0; i < 9; i++) {
            slots[i] = create(i);
        }
        return slots;
    }

    private static void applyAll(MinecraftClient client) {
        if (client.player == null) {
            return;
        }
        PlayerInventory inventory = client.player.getInventory();
        for (int i = 0; i < playerGhost.length; i++) {
            putInventoryIfNeeded(inventory, i, playerGhost[i]);
        }
        applyDispenserSlots(client.player.currentScreenHandler);
        if (!cursor.isEmpty()) {
            ItemStack held = client.player.currentScreenHandler.getCursorStack();
            if (isReal(held)) {
                // A server cursor update wins over an older local preview.
                cursor = ItemStack.EMPTY;
            } else if (!ItemStack.areEqual(held, cursor)) {
                client.player.currentScreenHandler.setCursorStack(copyOf(cursor));
            }
        }
    }

    private static void applyDispenserSlots(ScreenHandler handler) {
        if (!isOpenDispenser(handler) || openPos == null) {
            return;
        }
        ItemStack[] slots = state(openPos);
        for (int i = 0; i < 9; i++) {
            ItemStack current = handler.getSlot(i).getStack();
            if (isReal(current)) {
                // A server-owned item may replace this slot, but must not stop
                // the remaining empty server slots from showing their prizes.
                slots[i] = ItemStack.EMPTY;
            } else {
                setSlotIfNeeded(handler, i, slots[i]);
            }
        }
    }

    private static void stashCursorIfScreenClosed(MinecraftClient client) {
        if (client.currentScreen != null || cursor.isEmpty()) {
            return;
        }
        if (isReal(client.player.currentScreenHandler.getCursorStack())) {
            cursor = ItemStack.EMPTY;
            return;
        }
        insertIntoInventory(client.player, cursor);
        cursor = ItemStack.EMPTY;
        client.player.currentScreenHandler.setCursorStack(ItemStack.EMPTY);
    }

    private static void flushPending(ClientWorld world) {
        if (pendingFires.isEmpty()) {
            return;
        }
        List<BlockPos> batch = new ArrayList<>(pendingFires);
        pendingFires.clear();
        batch.removeIf(pos -> !selection.contains(pos) || !world.getBlockState(pos).isOf(Blocks.DISPENSER));
        Random random = world.getRandom();
        List<BlockPos> selected = selection.positions();
        BlockPos primedA = selected.isEmpty() ? null : selected.getFirst();
        BlockPos primedB = selected.size() < 2 ? null : selected.get(1);
        boolean pairFired = primedA != null && primedB != null
            && containsPos(batch, primedA) && containsPos(batch, primedB);
        if (pairFired) {
            boolean firstWins = decideFirstWins(random);
            ItemStack[] firstSlots = state(primedA);
            ItemStack[] secondSlots = state(primedB);
            int[] picks = pickByWinner(firstSlots, secondSlots, random, firstWins);
            int firstNumber = prizeNumber(firstSlots, picks[0]);
            int secondNumber = prizeNumber(secondSlots, picks[1]);
            ejectSlot(world, primedA, picks[0]);
            ejectSlot(world, primedB, picks[1]);
            if (firstNumber > 0 && secondNumber > 0) {
                lastRound = new RoundResult(world, firstNumber, secondNumber, System.nanoTime());
            }
            for (BlockPos pos : batch) {
                if (!pos.equals(primedA) && !pos.equals(primedB)) {
                    ejectVanilla(world, pos, random);
                }
            }
            return;
        }
        for (BlockPos pos : batch) {
            ejectVanilla(world, pos, random);
        }
    }

    private static boolean decideFirstWins(Random random) {
        double w1 = Math.max(0, Math.min(100, win1Percent)) / 100.0;
        double w2 = Math.max(0, Math.min(100, win2Percent)) / 100.0;
        if (w1 + w2 > 1.0) {
            double sum = w1 + w2;
            w1 /= sum;
            w2 /= sum;
        }
        for (int attempt = 0; attempt < 64; attempt++) {
            double roll = random.nextDouble();
            if (roll < w1) {
                return true;
            }
            if (roll < w1 + w2) {
                return false;
            }
            if ("Reroll".equalsIgnoreCase(tieMode)) {
                continue;
            }
            return "Win".equalsIgnoreCase(tieMode);
        }
        return w1 >= w2;
    }

    private static int[] pickByWinner(ItemStack[] first, ItemStack[] second, Random random, boolean firstWins) {
        int chosenA = -1;
        int chosenB = -1;
        int seen = 1;
        for (int i = 0; i < 9; i++) {
            if (first[i].isEmpty()) {
                continue;
            }
            int firstNum = prizeNumber(first, i);
            for (int j = 0; j < 9; j++) {
                if (second[j].isEmpty()) {
                    continue;
                }
                int secondNum = prizeNumber(second, j);
                boolean ok = firstWins ? firstNum > secondNum : secondNum > firstNum;
                if (ok && random.nextInt(seen++) == 0) {
                    chosenA = i;
                    chosenB = j;
                }
            }
        }
        if (chosenA < 0 || chosenB < 0) {
            return new int[] {
                chooseNonEmpty(first, random, k -> true),
                chooseNonEmpty(second, random, k -> true)
            };
        }
        return new int[] {chosenA, chosenB};
    }

    private static void ejectVanilla(ClientWorld world, BlockPos pos, Random random) {
        ejectSlot(world, pos, chooseNonEmpty(state(pos), random, k -> true));
    }

    private static void ejectSlot(ClientWorld world, BlockPos pos, int slot) {
        if (slot < 0) {
            return;
        }
        ItemStack[] slots = state(pos);
        if (slot >= 9 || slots[slot].isEmpty()) {
            return;
        }
        ItemStack ejected = slots[slot].split(1);
        if (slots[slot].isEmpty()) {
            slots[slot] = ItemStack.EMPTY;
        }
        spawnDispensed(world, pos, ejected);
        MinecraftClient client = MinecraftClient.getInstance();
        if (openPos != null && openPos.equals(pos) && client.player != null) {
            setSlotIfNeeded(client.player.currentScreenHandler, slot, slots[slot]);
        }
    }

    /**
     * Vanilla {@code DispenserBlockEntity.chooseNonEmptySlot}: reservoir sampling
     * with {@code Random.nextInt}.
     */
    private static int chooseNonEmpty(ItemStack[] slots, Random random, IntPredicate extra) {
        int chosen = -1;
        int seen = 1;
        for (int i = 0; i < 9; i++) {
            if (!slots[i].isEmpty() && extra.test(i) && random.nextInt(seen++) == 0) {
                chosen = i;
            }
        }
        return chosen;
    }

    private static int prizeNumber(ItemStack[] slots, int slot) {
        if (slot < 0 || slot >= 9) {
            return 0;
        }
        int kind = kindOf(slots[slot]);
        return kind < 0 ? 0 : kind + 1;
    }

    private static int maxPrize(ItemStack[] slots) {
        int max = 0;
        for (int i = 0; i < 9; i++) {
            max = Math.max(max, prizeNumber(slots, i));
        }
        return max;
    }

    private static boolean hasAny(ItemStack[] slots) {
        for (ItemStack stack : slots) {
            if (!stack.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsPos(List<BlockPos> batch, BlockPos pos) {
        for (BlockPos other : batch) {
            if (other.equals(pos)) {
                return true;
            }
        }
        return false;
    }

    private static BlockPos nearestOther(List<BlockPos> batch, BlockPos primedPos) {
        BlockPos best = null;
        int bestDist = Integer.MAX_VALUE;
        for (BlockPos pos : batch) {
            if (pos.equals(primedPos)) {
                continue;
            }
            int dist = pos.getManhattanDistance(primedPos);
            if (dist < bestDist) {
                bestDist = dist;
                best = pos;
            }
        }
        return best;
    }

    private static void spawnDispensed(ClientWorld world, BlockPos pos, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        Direction side = world.getBlockState(pos).isOf(Blocks.DISPENSER)
            ? world.getBlockState(pos).get(DispenserBlock.FACING)
            : Direction.NORTH;
        Vec3d center = pos.toCenterPos();
        double x = center.x + 0.7 * side.getOffsetX();
        double y = center.y + 0.7 * side.getOffsetY();
        double z = center.z + 0.7 * side.getOffsetZ();
        if (side.getAxis() == Direction.Axis.Y) {
            y -= 0.125;
        } else {
            y -= 0.15625;
        }
        ItemEntity entity = new ItemEntity(world, x, y, z, stack);
        entity.setToDefaultPickupDelay();
        Random random = world.getRandom();
        double g = random.nextDouble() * 0.1 + 0.2;
        entity.setVelocity(
            random.nextTriangular(side.getOffsetX() * g, 0.0172275 * DISPENSE_SPEED),
            random.nextTriangular(0.2, 0.0172275 * DISPENSE_SPEED),
            random.nextTriangular(side.getOffsetZ() * g, 0.0172275 * DISPENSE_SPEED)
        );
        addDrop(world, entity);
    }

    private static void spawnDropped(World world, PlayerEntity player, ItemStack stack) {
        if (!(world instanceof ClientWorld clientWorld) || stack.isEmpty()) {
            return;
        }
        ItemEntity entity = new ItemEntity(
            clientWorld,
            player.getX(),
            player.getEyeY() - 0.3,
            player.getZ(),
            stack
        );
        entity.setPickupDelay(40);
        // Same non-random-throw velocity as LivingEntity#createItemEntity.
        Random random = world.getRandom();
        float pitch = player.getPitch() * 0.017453292F;
        float yaw = player.getYaw() * 0.017453292F;
        float spread = random.nextFloat() * 6.2831855F;
        float jitter = 0.02F * random.nextFloat();
        entity.setVelocity(-MathHelper.sin(yaw) * MathHelper.cos(pitch) * 0.3F + Math.cos(spread) * jitter,
            -MathHelper.sin(pitch) * 0.3F + 0.1F + (random.nextFloat() - random.nextFloat()) * 0.1F,
            MathHelper.cos(yaw) * MathHelper.cos(pitch) * 0.3F + Math.sin(spread) * jitter);
        addDrop(clientWorld, entity);
    }

    public static void spawnLocalDrop(ClientWorld world, Vec3d position, ItemStack stack, int pickupDelay) {
        if (!enabled || world == null || world != MinecraftClient.getInstance().world
            || position == null || !isLocal(stack)) return;
        ItemEntity entity = new ItemEntity(world, position.x, position.y, position.z, stack.copy());
        entity.setPickupDelay(Math.max(0, pickupDelay));
        addDrop(world, entity);
    }

    public static boolean dropSelectedLocal(PlayerEntity player, boolean entireStack) {
        if (!enabled || player == null || player != MinecraftClient.getInstance().player
            || !(player.getEntityWorld() instanceof ClientWorld world)) return false;
        ItemStack held = player.getInventory().getSelectedStack();
        if (!isLocal(held)) return false;
        ItemStack dropped = entireStack ? held.copyAndEmpty() : held.split(1);
        snapshotGhost(player.getInventory());
        spawnDropped(world, player, dropped);
        return true;
    }

    public static boolean swapLocalOffhand(PlayerEntity player) {
        if (!enabled || player == null || player != MinecraftClient.getInstance().player) return false;
        PlayerInventory inventory = player.getInventory();
        int selected = inventory.getSelectedSlot();
        ItemStack held = inventory.getStack(selected);
        ItemStack offhand = inventory.getStack(40);
        if (!isLocal(held) && !isLocal(offhand)) return false;
        // A real server item must keep its server-owned slot when no packet is sent.
        if (!canSwap(held, offhand)) return true;
        inventory.setStack(selected, offhand);
        inventory.setStack(40, held);
        snapshotGhost(inventory);
        return true;
    }

    /** Creative packets use player-screen slot numbers rather than inventory indices. */
    public static boolean handleCreativeStack(ItemStack stack, int slotId) {
        if (!enabled) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return false;
        int inv = slotId >= 36 && slotId <= 44 ? slotId - 36
            : slotId >= 9 && slotId <= 35 ? slotId
            : slotId == 45 ? 40 : slotId >= 5 && slotId <= 8 ? 44 - slotId : -1;
        if (inv < 0) return isLocal(stack);
        ItemStack current = client.player.getInventory().getStack(inv);
        if (isReal(stack)) {
            // A normal creative selection is a legitimate real inventory update.
            playerGhost[inv] = ItemStack.EMPTY;
            return false;
        }
        if (!isLocal(stack) && !isLocal(current) && !isLocal(playerGhost[inv])) return false;
        setPlayerSlot(client.player, inv, stack);
        return true;
    }

    public static boolean dropCreativeLocal(ItemStack stack) {
        if (!enabled || !isLocal(stack)) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.world != null) {
            spawnDropped(client.world, client.player, stack.copy());
            snapshotGhost(client.player.getInventory());
        }
        return true;
    }

    private static void addDrop(ClientWorld world, ItemEntity entity) {
        entity.setId(CLIENT_IDS.getAndDecrement());
        world.addEntity(entity);
        ground.add(entity);
    }

    private static void insertIntoInventory(PlayerEntity player, ItemStack moving) {
        if (moving.isEmpty()) {
            return;
        }
        player.getInventory().insertStack(moving);
        snapshotGhost(player.getInventory());
        if (!moving.isEmpty() && player.getEntityWorld() instanceof ClientWorld clientWorld) {
            spawnDropped(clientWorld, player, moving.copyAndEmpty());
        }
    }

    private static void insertIntoDispenser(ScreenHandler handler, ItemStack[] slots, ItemStack moving) {
        for (int i = 0; i < 9 && !moving.isEmpty(); i++) {
            Slot target = handler.getSlot(i);
            if (isReal(target.getStack()) || !target.canInsert(moving)) continue;
            if (!slots[i].isEmpty()
                && ItemStack.areItemsAndComponentsEqual(slots[i], moving)
                && slots[i].getCount() < Math.min(slots[i].getMaxCount(), target.getMaxItemCount(moving))) {
                int n = Math.min(moving.getCount(), Math.min(slots[i].getMaxCount(), target.getMaxItemCount(moving)) - slots[i].getCount());
                slots[i].increment(n);
                moving.decrement(n);
            }
        }
        for (int i = 0; i < 9 && !moving.isEmpty(); i++) {
            Slot target = handler.getSlot(i);
            if (isReal(target.getStack()) || !target.canInsert(moving)) continue;
            if (slots[i].isEmpty()) {
                slots[i] = moving.split(Math.min(moving.getMaxCount(), target.getMaxItemCount(moving)));
            }
        }
    }

    private static void snapshotGhost(PlayerInventory inventory) {
        for (int i = 0; i < playerGhost.length; i++) {
            ItemStack stack = inventory.getStack(i);
            if (isLocal(stack)) {
                playerGhost[i] = stack.copy();
            } else {
                playerGhost[i] = ItemStack.EMPTY;
            }
        }
    }

    private static void putInventoryIfNeeded(PlayerInventory inventory, int slot, ItemStack ghost) {
        if (ghost.isEmpty()) {
            return;
        }
        ItemStack current = inventory.getStack(slot);
        if (isReal(current)) {
            playerGhost[slot] = ItemStack.EMPTY;
            return;
        }
        if (ItemStack.areEqual(current, ghost)) {
            return;
        }
        inventory.setStack(slot, copyOf(ghost));
    }

    private static void setSlotIfNeeded(ScreenHandler handler, int slot, ItemStack wanted) {
        if (slot < 0 || slot >= handler.slots.size()) {
            return;
        }
        ItemStack current = handler.getSlot(slot).getStack();
        ItemStack target = wanted == null ? ItemStack.EMPTY : wanted;
        if (ItemStack.areEqual(current, target)) {
            return;
        }
        handler.getSlot(slot).setStack(copyOf(target));
    }

    private static void refreshHandlerInventory(ScreenHandler handler, PlayerEntity player) {
        for (int slotId = 0; slotId < handler.slots.size(); slotId++) {
            int inv = playerInvIndex(handler, slotId);
            if (inv >= 0) setSlotIfNeeded(handler, slotId, player.getInventory().getStack(inv));
        }
    }

    private static void rightClickSlot(ItemStack[] slots, int slotId, ScreenHandler handler) {
        ItemStack slot = slots[slotId];
        if (!canSwap(slot, cursor)) return;
        if (cursor.isEmpty()) {
            if (slot.isEmpty()) {
                return;
            }
            int take = MathHelper.ceil(slot.getCount() / 2.0F);
            cursor = slot.split(take);
            if (slot.isEmpty()) {
                slots[slotId] = ItemStack.EMPTY;
            }
        } else if (slot.isEmpty()) {
            slots[slotId] = cursor.split(1);
            if (cursor.isEmpty()) {
                cursor = ItemStack.EMPTY;
            }
        } else if (ItemStack.areItemsAndComponentsEqual(slot, cursor) && slot.getCount() < slot.getMaxCount()) {
            slot.increment(1);
            cursor.decrement(1);
            if (cursor.isEmpty()) {
                cursor = ItemStack.EMPTY;
            }
        } else if (!ItemStack.areItemsAndComponentsEqual(slot, cursor)) {
            slots[slotId] = cursor;
            cursor = slot;
        }
        setSlotIfNeeded(handler, slotId, slots[slotId]);
        handler.setCursorStack(copyOf(cursor));
    }

    private static void rightClickInventory(int inv, ScreenHandler handler, PlayerEntity player) {
        if (isReal(player.getInventory().getStack(inv))) return;
        ItemStack slot = playerGhost[inv];
        if (!canSwap(slot, cursor)) return;
        if (cursor.isEmpty()) {
            if (slot.isEmpty()) {
                return;
            }
            int take = MathHelper.ceil(slot.getCount() / 2.0F);
            cursor = slot.split(take);
            if (slot.isEmpty()) {
                playerGhost[inv] = ItemStack.EMPTY;
            }
        } else if (slot.isEmpty()) {
            playerGhost[inv] = cursor.split(1);
            if (cursor.isEmpty()) {
                cursor = ItemStack.EMPTY;
            }
        } else if (ItemStack.areItemsAndComponentsEqual(slot, cursor) && slot.getCount() < slot.getMaxCount()) {
            slot.increment(1);
            cursor.decrement(1);
            if (cursor.isEmpty()) {
                cursor = ItemStack.EMPTY;
            }
        } else if (!ItemStack.areItemsAndComponentsEqual(slot, cursor)) {
            playerGhost[inv] = cursor;
            cursor = slot;
        }
        setPlayerSlot(player, inv, playerGhost[inv]);
        handler.setCursorStack(copyOf(cursor));
    }

    private static boolean clickPickup(ScreenHandler handler, PlayerEntity player, MinecraftClient client,
                                       int slotId, int inv, boolean disp, int button) {
        if (slotId == ScreenHandler.EMPTY_SPACE_SLOT_INDEX && isLocal(cursor)) {
            spawnDropped(client.world, player, button == 1 ? cursor.split(1) : cursor.copyAndEmpty());
            setCursor(handler, cursor);
            return true;
        }
        if (disp) {
            ItemStack[] slots = state(openPos);
            if (button == 1) {
                rightClickSlot(slots, slotId, handler);
                return true;
            }
            if (!canSwap(slots[slotId], cursor)) {
                return true;
            }
            ItemStack was = slots[slotId];
            if (mergeCursorInto(was)) {
                setCursor(handler, cursor);
            } else {
                slots[slotId] = copyOf(cursor);
                setCursor(handler, was);
            }
            setSlotIfNeeded(handler, slotId, slots[slotId]);
            return true;
        }
        if (inv >= 0) {
            if (isReal(player.getInventory().getStack(inv))) return true;
            if (button == 1) {
                rightClickInventory(inv, handler, player);
                return true;
            }
            ItemStack was = copyOf(playerGhost[inv]);
            if (was.isEmpty()) {
                was = copyOf(player.getInventory().getStack(inv));
            }
            if (!canSwap(was, cursor)) {
                return true;
            }
            if (mergeCursorInto(was)) {
                setPlayerSlot(player, inv, was);
                setCursor(handler, cursor);
            } else {
                setPlayerSlot(player, inv, cursor);
                setCursor(handler, was);
            }
            setSlotIfNeeded(handler, slotId, playerGhost[inv]);
            return true;
        }
        return true;
    }

    private static boolean clickQuickMove(ScreenHandler handler, PlayerEntity player, int slotId, int inv, boolean disp) {
        if (disp) {
            ItemStack[] slots = state(openPos);
            if (!slots[slotId].isEmpty()) {
                ItemStack moving = slots[slotId];
                insertIntoContainerPlayerSlots(handler, moving);
                snapshotGhost(player.getInventory());
                if (moving.isEmpty()) {
                    slots[slotId] = ItemStack.EMPTY;
                }
                setSlotIfNeeded(handler, slotId, slots[slotId]);
                refreshHandlerInventory(handler, player);
            }
            return true;
        }
        if (inv < 0 || !isLocal(playerGhost[inv])) {
            return true;
        }
        ItemStack moving = copyOf(playerGhost[inv]);
        if (isOpenDispenser(handler) && openPos != null) {
            insertIntoDispenser(handler, state(openPos), moving);
            applyDispenserSlots(handler);
        } else {
            insertIntoPlayerRegion(player, moving, inv < 9);
        }
        setPlayerSlot(player, inv, moving);
        snapshotGhost(player.getInventory());
        refreshHandlerInventory(handler, player);
        return true;
    }

    private static boolean clickSwap(ScreenHandler handler, PlayerEntity player, int slotId, int inv, boolean disp, int hotbar) {
        if (hotbar < 0 || hotbar > 8 && hotbar != 40) {
            return true;
        }
        ItemStack hot = copyOf(playerGhost[hotbar]);
        if (hot.isEmpty()) {
            hot = copyOf(player.getInventory().getStack(hotbar));
        }
        if (disp) {
            ItemStack[] slots = state(openPos);
            if (!canSwap(slots[slotId], hot)) {
                return true;
            }
            ItemStack was = slots[slotId];
            slots[slotId] = copyOf(hot);
            setPlayerSlot(player, hotbar, was);
            setSlotIfNeeded(handler, slotId, slots[slotId]);
            refreshHandlerInventory(handler, player);
            return true;
        }
        if (inv >= 0 && inv != hotbar) {
            ItemStack hovered = copyOf(playerGhost[inv]);
            if (hovered.isEmpty()) {
                hovered = copyOf(player.getInventory().getStack(inv));
            }
            if (!canSwap(hovered, hot)) {
                return true;
            }
            setPlayerSlot(player, inv, hot);
            setPlayerSlot(player, hotbar, hovered);
            refreshHandlerInventory(handler, player);
        }
        return true;
    }

    private static boolean clickThrow(ScreenHandler handler, PlayerEntity player, MinecraftClient client,
                                      int slotId, int inv, boolean disp, int button) {
        if (disp) {
            ItemStack[] slots = state(openPos);
            if (!slots[slotId].isEmpty()) {
                ItemStack dropped = button == 1 ? slots[slotId].copyAndEmpty() : slots[slotId].split(1);
                if (slots[slotId].isEmpty()) {
                    slots[slotId] = ItemStack.EMPTY;
                }
                spawnDropped(client.world, player, dropped);
                setSlotIfNeeded(handler, slotId, slots[slotId]);
            }
            return true;
        }
        if (inv >= 0 && isLocal(playerGhost[inv])) {
            ItemStack stack = playerGhost[inv];
            ItemStack dropped = button == 1 ? stack.copyAndEmpty() : stack.split(1);
            if (stack.isEmpty()) {
                setPlayerSlot(player, inv, ItemStack.EMPTY);
            } else {
                setPlayerSlot(player, inv, stack);
            }
            spawnDropped(client.world, player, dropped);
            refreshHandlerInventory(handler, player);
        }
        return true;
    }

    private static boolean clickPickupAll(ScreenHandler handler, PlayerEntity player) {
        if (!isLocal(cursor)) {
            return true;
        }
        int room = cursor.getMaxCount() - cursor.getCount();
        if (room <= 0) {
            return true;
        }
        if (isOpenDispenser(handler) && openPos != null) {
            ItemStack[] slots = state(openPos);
            for (int i = 0; i < 9 && room > 0; i++) {
                if (isLocal(slots[i]) && ItemStack.areItemsAndComponentsEqual(slots[i], cursor)) {
                    int n = Math.min(room, slots[i].getCount());
                    cursor.increment(n);
                    slots[i].decrement(n);
                    if (slots[i].isEmpty()) {
                        slots[i] = ItemStack.EMPTY;
                    }
                    room -= n;
                }
            }
            applyDispenserSlots(handler);
        }
        for (int i = 0; i < playerGhost.length && room > 0; i++) {
            if (isLocal(playerGhost[i]) && !isReal(player.getInventory().getStack(i))
                && ItemStack.areItemsAndComponentsEqual(playerGhost[i], cursor)) {
                int n = Math.min(room, playerGhost[i].getCount());
                cursor.increment(n);
                ItemStack leftover = copyOf(playerGhost[i]);
                leftover.decrement(n);
                setPlayerSlot(player, i, leftover);
                room -= n;
            }
        }
        setCursor(handler, cursor);
        refreshHandlerInventory(handler, player);
        return true;
    }

    private static void insertIntoPlayerRegion(PlayerEntity player, ItemStack moving, boolean fromHotbar) {
        int start = fromHotbar ? 9 : 0;
        int end = fromHotbar ? 36 : 9;
        for (int pass = 0; pass < 2 && !moving.isEmpty(); pass++) {
            for (int i = start; i < end && !moving.isEmpty(); i++) {
                ItemStack existing = playerGhost[i].isEmpty() ? player.getInventory().getStack(i) : playerGhost[i];
                if (pass == 0) {
                    if (isLocal(existing) && ItemStack.areItemsAndComponentsEqual(existing, moving)
                        && existing.getCount() < existing.getMaxCount()) {
                        int n = Math.min(moving.getCount(), existing.getMaxCount() - existing.getCount());
                        existing.increment(n);
                        moving.decrement(n);
                        setPlayerSlot(player, i, existing);
                    }
                } else if (existing.isEmpty()) {
                    setPlayerSlot(player, i, moving.split(moving.getMaxCount()));
                }
            }
        }
    }

    private static void setPlayerSlot(PlayerEntity player, int inv, ItemStack stack) {
        if (isReal(player.getInventory().getStack(inv))) {
            playerGhost[inv] = ItemStack.EMPTY;
            return;
        }
        ItemStack prize = isLocal(stack) ? copyOf(stack) : ItemStack.EMPTY;
        playerGhost[inv] = prize;
        ItemStack current = player.getInventory().getStack(inv);
        if (prize.isEmpty()) {
            if (isLocal(current)) {
                player.getInventory().setStack(inv, ItemStack.EMPTY);
            }
        } else {
            player.getInventory().setStack(inv, copyOf(prize));
        }
    }

    private static void setCursor(ScreenHandler handler, ItemStack stack) {
        cursor = stack == null || stack.isEmpty() ? ItemStack.EMPTY : copyOf(stack);
        handler.setCursorStack(copyOf(cursor));
    }

    private static boolean canSwap(ItemStack a, ItemStack b) {
        return !isReal(a) && !isReal(b);
    }

    private static boolean mergeCursorInto(ItemStack target) {
        if (!isLocal(target) || !isLocal(cursor)
            || !ItemStack.areItemsAndComponentsEqual(target, cursor)) return false;
        int amount = Math.min(cursor.getCount(), Math.max(0, target.getMaxCount() - target.getCount()));
        target.increment(amount);
        cursor.decrement(amount);
        return true;
    }

    private static void insertIntoContainerPlayerSlots(ScreenHandler handler, ItemStack moving) {
        // Container quick-move visits hotbar 8..0, then main inventory 35..9;
        // normal ground pickup uses a different order and must not be reused here.
        for (int pass = 0; pass < 2 && !moving.isEmpty(); pass++) {
            for (int i = handler.slots.size() - 1; i >= 0 && !moving.isEmpty(); i--) {
                Slot target = handler.getSlot(i);
                int inv = playerInvIndex(handler, i);
                if (inv < 0 || inv >= 36 || !target.canInsert(moving)) continue;
                ItemStack at = target.getStack();
                if (pass == 0 ? at.isEmpty() || !ItemStack.areItemsAndComponentsEqual(at, moving)
                    : !at.isEmpty()) continue;
                int amount = Math.min(moving.getCount(), Math.max(0,
                    Math.min(moving.getMaxCount(), target.getMaxItemCount(moving)) - at.getCount()));
                if (amount == 0) continue;
                if (at.isEmpty()) target.setStack(moving.split(amount));
                else { at.increment(amount); moving.decrement(amount); }
            }
        }
    }

    private static boolean hasLocalSlots(ScreenHandler handler) {
        for (Slot slot : handler.slots) if (isLocal(slot.getStack())) return true;
        return false;
    }

    /** The server sees prize slots as empty, so its QUICK_MOVE could overwrite them. */
    private static boolean quickMoveReal(ScreenHandler handler, PlayerEntity player,
                                         MinecraftClient client, int sourceId) {
        if (client.interactionManager == null || !handler.getCursorStack().isEmpty()) return true;
        Slot source = handler.getSlot(sourceId);
        if (!source.canTakeItems(player)) return true;
        List<Integer> targets = realQuickMoveTargets(handler, player, sourceId);
        if (targets == null) return true;
        ItemStack moving = source.getStack();
        List<Integer> usable = new ArrayList<>();
        // Vanilla fills compatible stacks before visiting empty slots.
        for (int pass = 0; pass < 2; pass++) {
            for (int targetId : targets) {
                Slot target = handler.getSlot(targetId);
                ItemStack at = target.getStack();
                if (isLocal(at) || !target.canInsert(moving) || targetId == sourceId) continue;
                if (pass == 0 ? at.isEmpty() || !ItemStack.areItemsAndComponentsEqual(at, moving)
                    : !at.isEmpty()) continue;
                if (at.getCount() < Math.min(moving.getMaxCount(), target.getMaxItemCount(moving))) {
                    usable.add(targetId);
                }
            }
        }
        if (usable.isEmpty()) return true;
        // Use normal predicted clicks with explicit destinations. Neither local
        // components nor an ambiguous server-side shift transfer leave the client.
        client.interactionManager.clickSlot(handler.syncId, sourceId, 0, SlotActionType.PICKUP, player);
        for (int targetId : usable) {
            if (player.currentScreenHandler != handler || handler.getCursorStack().isEmpty()) break;
            Slot target = handler.getSlot(targetId);
            ItemStack held = handler.getCursorStack();
            ItemStack at = target.getStack();
            if (!isReal(held) || isLocal(at) || !target.canInsert(held)
                || !at.isEmpty() && !ItemStack.areItemsAndComponentsEqual(at, held)) continue;
            client.interactionManager.clickSlot(handler.syncId, targetId, 0, SlotActionType.PICKUP, player);
        }
        if (player.currentScreenHandler == handler && isReal(handler.getCursorStack())
            && source.canInsert(handler.getCursorStack()) && !isLocal(source.getStack())) {
            client.interactionManager.clickSlot(handler.syncId, sourceId, 0, SlotActionType.PICKUP, player);
        }
        return true;
    }

    private static List<Integer> realQuickMoveTargets(ScreenHandler handler, PlayerEntity player, int sourceId) {
        List<Integer> targets = new ArrayList<>();
        if (handler instanceof PlayerScreenHandler) {
            // Crafting output has recipe/repeat semantics that cannot be replaced
            // with a single pickup. Keep it intact while local slots are reserved.
            if (sourceId == 0) return null;
            ItemStack stack = handler.getSlot(sourceId).getStack();
            EquipmentSlot equipment = player.getPreferredEquipmentSlot(stack);
            int armorSlot = 8 - equipment.getEntitySlotId();
            if (sourceId >= 9 && equipment.getType() == EquipmentSlot.Type.HUMANOID_ARMOR
                && handler.getSlot(armorSlot).getStack().isEmpty()) {
                targets.add(armorSlot);
            } else if (sourceId >= 9 && equipment == EquipmentSlot.OFFHAND
                && handler.getSlot(45).getStack().isEmpty()) {
                targets.add(45);
            } else {
                int from = sourceId >= 9 && sourceId < 36 ? 36 : 9;
                int to = sourceId >= 36 && sourceId < 45 ? 36 : 45;
                for (int i = from; i < to; i++) targets.add(i);
            }
        } else if (handler instanceof Generic3x3ContainerScreenHandler
            || handler instanceof GenericContainerScreenHandler || handler instanceof HopperScreenHandler
            || handler instanceof ShulkerBoxScreenHandler) {
            int containerSlots = handler.slots.size() - 36;
            if (sourceId < containerSlots) {
                for (int i = handler.slots.size() - 1; i >= containerSlots; i--) targets.add(i);
            } else {
                for (int i = 0; i < containerSlots; i++) targets.add(i);
            }
        } else {
            return null;
        }
        return targets;
    }

    private static boolean isReal(ItemStack stack) {
        return stack != null && !stack.isEmpty() && !isLocal(stack);
    }

    private static int playerInvIndex(ScreenHandler handler, int slotId) {
        if (slotId < 0 || slotId >= handler.slots.size()) {
            return -1;
        }
        Slot slot = handler.getSlot(slotId);
        if (!(slot.inventory instanceof PlayerInventory)) {
            return -1;
        }
        int index = slot.getIndex();
        return index >= 0 && index < 36 || index == 40 ? index : -1;
    }

    private static boolean isDispenserContainerSlot(ScreenHandler handler, int slotId) {
        return isOpenDispenser(handler) && openPos != null && slotId >= 0 && slotId < 9
            && slotId < handler.slots.size()
            && !(handler.getSlot(slotId).inventory instanceof PlayerInventory);
    }

    private static void syncConfiguredPrizes(ModuleContext context) {
        boolean changed = false;
        for (int i = 0; i < 9; i++) {
            String fallback = Registries.ITEM.getId(DEFAULT_PRIZES[i]).toString();
            Item item = resolveItem(context.itemPick(Integer.toString(i + 1), fallback), DEFAULT_PRIZES[i]);
            if (item != prizes[i]) {
                prizes[i] = item;
                changed = true;
            }
        }
        if (changed) {
            restylePrizes();
        }
    }

    private static Item resolveItem(String id, Item fallback) {
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null) {
            return fallback;
        }
        Item item = Registries.ITEM.get(identifier);
        return item == Items.AIR ? fallback : item;
    }

    private static void restylePrizes() {
        for (ItemStack[] slots : dispensers.values()) {
            for (int i = 0; i < 9; i++) {
                int kind = kindOf(slots[i]);
                if (kind >= 0) {
                    slots[i] = create(kind, slots[i].getCount());
                }
            }
        }
        for (int i = 0; i < playerGhost.length; i++) {
            int kind = kindOf(playerGhost[i]);
            if (kind >= 0) {
                playerGhost[i] = create(kind, playerGhost[i].getCount());
            }
        }
        int cursorKind = kindOf(cursor);
        if (cursorKind >= 0) {
            cursor = create(cursorKind, cursor.getCount());
        }
    }

    private static ItemStack copyOf(ItemStack stack) {
        return stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
    }

    private static void pruneGround() {
        ground.removeIf(entity -> entity == null || entity.isRemoved() || entity.getStack().isEmpty());
    }

    /** Bounded presentation of existing local selections; does not inspect or create prize slots. */
    static void extract(WorldExtractionContext context, List<VisualExpansionRenderer.WorldBox> boxes,
                        List<VisualExpansionRenderer.WorldLineStrip> lines) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!enabled || !selectionMarkers || client.player == null || !client.player.isAlive()
            || client.world != context.world() || client.options.hudHidden) return;
        List<BlockPos> selected = selection.positions();
        BlockPos first = selected.isEmpty() ? null : selected.getFirst();
        BlockPos second = selected.size() < 2 ? null : selected.get(1);
        if (first != null) selection(first, 0x80CEE5, client, boxes, lines);
        if (second != null) selection(second, 0xE7A3CD, client, boxes, lines);
    }

    private static void selection(BlockPos pos, int rgb, MinecraftClient client,
                                  List<VisualExpansionRenderer.WorldBox> boxes,
                                  List<VisualExpansionRenderer.WorldLineStrip> lines) {
        if (!client.world.getBlockState(pos).isOf(Blocks.DISPENSER)
            || client.player.squaredDistanceTo(pos.toCenterPos()) > 4096D) return;
        double phase = System.nanoTime() / 1_000_000_000D;
        float pulse = (float) (0.68D + 0.15D * Math.sin(phase * 2.4D));
        double x = pos.getX();
        double y = pos.getY() + 1.014D;
        double z = pos.getZ();
        boxes.add(new VisualExpansionRenderer.WorldBox(
            new Box(x - 0.018D, y, z - 0.018D, x + 1.018D, y + 0.012D, z + 1.018D),
            VisualExpansionRenderer.argb(0.08F, rgb), 0, 1F));
        for (int index = 0; index < 4; index++) {
            double cornerX = x + (index == 1 || index == 2 ? 1.03D : -0.03D);
            double cornerZ = z + (index >= 2 ? 1.03D : -0.03D);
            double dx = index == 1 || index == 2 ? -0.24D : 0.24D;
            double dz = index >= 2 ? -0.24D : 0.24D;
            List<Vec3d> corner = List.of(new Vec3d(cornerX + dx, y + 0.014D, cornerZ),
                new Vec3d(cornerX, y + 0.014D, cornerZ), new Vec3d(cornerX, y + 0.014D, cornerZ + dz));
            lines.add(new VisualExpansionRenderer.WorldLineStrip(corner,
                VisualExpansionRenderer.argb(pulse * 0.15F, rgb), 5F));
            lines.add(new VisualExpansionRenderer.WorldLineStrip(corner,
                VisualExpansionRenderer.argb(pulse, rgb), 1.5F));
        }
    }

    public static void renderHud(MinecraftClient client, int width, int height) {
        if (!enabled || !roundDisplay || client.player == null || !client.player.isAlive()
            || client.world == null || client.options.hudHidden
            || (client.currentScreen != null && !(client.currentScreen instanceof SilentInventoryScreen))) return;
        int primed = selection.positions().size();
        RoundResult result = lastRound;
        long now = System.nanoTime();
        boolean showResult = result != null && result.world() == client.world
            && now - result.startedNanos() < displayDurationNanos;
        if (primed == 0 && !showResult) return;
        float alpha = 1F;
        if (showResult) {
            float enter = MathHelper.clamp((now - result.startedNanos()) / 160_000_000F, 0F, 1F);
            float leave = MathHelper.clamp((displayDurationNanos - (now - result.startedNanos())) / 450_000_000F, 0F, 1F);
            alpha = enter * enter * (3F - 2F * enter) * leave * leave * (3F - 2F * leave);
        }
        float x = Math.max(8F, width - 160F);
        float y = Math.max(8F, height - 126F) + (1F - alpha) * 5F;
        MatrixStack matrices = RenderUtil.matrices();
        matrices.push();
        matrices.loadIdentity();
        RenderUtil.RECT.draw(matrices, x, y, 148F, 55F, 8F, tint(0x1A1D25, alpha * 0.93F));
        Fonts.regular().draw(matrices, showResult ? "LOCAL ROUND" : "LOCAL DISPENSERS", x + 10F, y + 7F,
            5F, tint(0x8F9EAB, alpha));
        if (showResult) {
            boolean tie = result.first() == result.second();
            int winner = tie ? 0 : result.first() > result.second() ? 1 : 2;
            resultValue(matrices, x + 10F, y + 18F, "A", result.first(), 0x80CEE5, winner == 1, alpha);
            resultValue(matrices, x + 82F, y + 18F, "B", result.second(), 0xE7A3CD, winner == 2, alpha);
            Fonts.regular().draw(matrices, tie ? "Equal result" : winner == 1 ? "A has the higher prize" : "B has the higher prize",
                x + 10F, y + 43F, 4.8F, tint(0xADB9C4, alpha));
        } else {
            for (int index = 0; index < 2; index++) {
                float cx = x + 10F + index * 23F;
                int rgb = index == 0 ? 0x80CEE5 : 0xE7A3CD;
                boolean selected = index < primed;
                RenderUtil.RECT.draw(matrices, cx, y + 21F, 17F, 17F, 5F, tint(rgb, selected ? 0.18F : 0.05F));
                Fonts.semibold().drawCenteredText(matrices, index == 0 ? "A" : "B", cx + 8.5F, y + 26F,
                    6F, tint(rgb, selected ? 1F : 0.25F));
            }
            Fonts.semibold().draw(matrices, primed == 2 ? "Pair ready" : "One selected", x + 62F, y + 22F,
                6.5F, tint(0xE4ECF2, alpha));
            Fonts.regular().draw(matrices, primed == 2 ? "Trigger both dispensers" : "Middle-click a second dispenser",
                x + 10F, y + 43F, 4.8F, tint(0xA3B2BE, alpha));
        }
        matrices.pop();
    }

    private static void resultValue(MatrixStack matrices, float x, float y, String label, int value,
                                    int rgb, boolean higher, float alpha) {
        Fonts.regular().draw(matrices, label, x, y + 4F, 5.5F, tint(rgb, alpha * 0.8F));
        Fonts.semibold().draw(matrices, Integer.toString(value), x + 13F, y, 12F,
            tint(higher ? 0xEDF6F9 : rgb, alpha));
        if (higher) RenderUtil.RECT.draw(matrices, x + 34F, y + 6F, 3.3F, 3.3F, 1.65F, tint(rgb, alpha));
    }

    private static Color tint(int rgb, float opacity) {
        return new Color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255,
            MathHelper.clamp(Math.round(opacity * 255F), 0, 255));
    }

    private record RoundResult(Object world, int first, int second, long startedNanos) { }

    private static void resetWorldState(MinecraftClient client, boolean wipePlayerVisuals) {
        FakeGambleGhostBlocks.reset();
        if (wipePlayerVisuals && client != null && client.player != null) {
            for (int i = 0; i < playerGhost.length; i++) {
                if (isLocal(client.player.getInventory().getStack(i))) {
                    client.player.getInventory().setStack(i, ItemStack.EMPTY);
                }
            }
            if (isLocal(client.player.currentScreenHandler.getCursorStack()))
                client.player.currentScreenHandler.setCursorStack(ItemStack.EMPTY);
            if (isOpenDispenser(client.player.currentScreenHandler)) {
                for (int i = 0; i < 9; i++) {
                    Slot slot = client.player.currentScreenHandler.getSlot(i);
                    if (isLocal(slot.getStack())) slot.setStack(ItemStack.EMPTY);
                }
            }
        }
        for (ItemEntity entity : List.copyOf(ground)) {
            entity.discard();
        }
        ground.clear();
        dispensers.clear();
        pendingFires.clear();
        java.util.Arrays.fill(playerGhost, ItemStack.EMPTY);
        cursor = ItemStack.EMPTY;
        openPos = null;
        selection.clear();
        lastRound = null;
    }
}
