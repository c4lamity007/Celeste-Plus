package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.action.UseInputLease;
import dev.c4lam.celeste.gui.model.GuiCatalog;
import dev.c4lam.celeste.gui.model.GuiModule;
import dev.c4lam.celeste.compat.MinecraftAccess;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.gui.HudTransparentScreen;
import dev.c4lam.celeste.action.HeldUseBroker;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Payload-owned sequence. Shared host arbitration does not contain item or sequence behavior. */
final class AutoHitCrystalController implements HeldUseBroker.Policy {
    private static final HeldUseBroker BROKER = HeldUseBroker.INSTANCE;

    enum ModuleKind {
        AUTO_CRYSTAL("Auto Crystal"),
        AUTO_HIT_CRYSTAL("Auto Hit Crystal"),
        ANCHOR_MACRO("Anchor Macro");

        final String moduleName;
        ModuleKind(String moduleName) { this.moduleName = moduleName; }
    }

    private enum Branch { NONE, AUTO_HIT }


    private enum ActionKind { NONE, RESERVE, AUTO_HIT_OBSIDIAN, AUTO_HIT_CRYSTAL, AUTO_HIT_ATTACK }

    private static final int PRIORITY = 190;
    private static final double AUTO_HIT_CRYSTAL_RANGE = 3.35D;
    private static final double BLOCK_INTERACTION_RANGE = 4.45D;
    private static final int PENDING_OBSIDIAN_WINDOW_TICKS = 2;

    private final Random random = new Random();

    private Branch branch = Branch.NONE;
    private long tickCounter;
    private BlockPos pendingObsidian;
    private long pendingObsidianStartTick;
    private long nextAutoHitTick;
    private boolean forcedObsidianPlacement;
    private boolean forcedCrystalPlacement;

    private ClientWorld observedWorld;
    private ClientPlayerEntity observedPlayer;
    private boolean useHeldAtStart;
    private ClientPlayerEntity sequencePlayer;
    private int ownedSlot = -1;
    private int sequenceVisibleSlot = -1;
    private boolean sequenceSilent;

    /* The source's selected slot, separated from its visible presentation. */

    private long startSchedulerTick = Long.MIN_VALUE;
    private long actionSchedulerTick = Long.MIN_VALUE;
    private long plannedSchedulerTick = Long.MIN_VALUE;
    private boolean wantsUse;
    private boolean useClaimed;
    private boolean actionUsedThisTick;
    private boolean silentTakeoverUntilUseRelease;
    private PlannedAction plannedAction = PlannedAction.NONE;


    @Override public boolean wantsUse() { return wantsUse; }
    @Override public void stop(MinecraftClient client) { resetEverything(client); wantsUse = false; useClaimed = false; }

    @Override public void start(ModuleContext caller) {
        long schedulerTick = ActionScheduler.getInstance().tickNumber();
        if (startSchedulerTick == schedulerTick) return;
        startSchedulerTick = schedulerTick;
        tickCounter = schedulerTick;
        actionUsedThisTick = false;
        wantsUse = false;
        clearPlan();

        MinecraftClient client = caller.client();
        // Only an observed physical release rearms the modifier. Cycle, GUI,
        // module and world cleanup must not turn one held key into repeated pairs.

        if (!basicClientState(client)) {
            useHeldAtStart = false;
            silentTakeoverUntilUseRelease = false;
            resetActiveSequence();
            endSilentTransport(client);
            releaseUse(client);
            return;
        }
        if (observedWorld != client.world || observedPlayer != client.player) {
            resetWorldState(client.world, client.player, client);
        }

        useHeldAtStart = client.isWindowFocused() && UseInputLease.physicalDown(client);
        if (silentTakeoverActive(client)) {
            silentTakeoverUntilUseRelease = true;
        }
        if (silentTakeoverUntilUseRelease) {
            resetActiveSequence();
            endSilentTransport(client);
            releaseUse(client);
            if (!useHeldAtStart) {
                silentTakeoverUntilUseRelease = false;
            }
            return;
        }
        if (client.currentScreen instanceof HudTransparentScreen) {
            // Silent Totem temporarily opens a real player inventory so its
            // clicks are vanilla-valid. That screen must pause interactions,
            // but it is not a user takeover: retaining the held-use branch is
            // essential for Auto Crystal's PLACE -> BREAK sequence to resume
            // after the inventory closes. Releasing the synthetic use lease
            // prevents any action from being emitted while the screen is up.
            releaseUse(client);
            return;
        }
        if (BROKER.silent() && !renewSilentTransport(client)) {
            resetActiveSequence();
            endSilentTransport(client);
        }
        if (branch != Branch.NONE && !ownsSequenceContext(client.player)) {
            resetActiveSequence();
            endSilentTransport(client);
        }

        if (!useHeldAtStart) {
            resetActiveSequence();
            endSilentTransport(client);
            releaseUse(client);
            return;
        }

        advanceStartTick(client, true);
        reserveCombatOwnershipIfNeeded();
        if (wantsUse) {
            useClaimed = UseInputLease.getInstance().acquire(
                BROKER, PRIORITY, schedulerTick, client);
            if (!useClaimed) clearPlan();
        } else {
            if (branch == Branch.NONE) endSilentTransport(client);
            releaseUse(client);
        }
    }

    @Override public void action(ModuleContext ignored) {
        long schedulerTick = ActionScheduler.getInstance().tickNumber();
        if (actionSchedulerTick == schedulerTick) return;
        actionSchedulerTick = schedulerTick;
        if (!useClaimed || !UseInputLease.getInstance().owns(BROKER) || plannedAction.kind == ActionKind.NONE
            || plannedSchedulerTick != schedulerTick) return;
        PlannedAction plan = plannedAction;
        ActionScheduler.getInstance().submit(this, PRIORITY, client -> {
            if (plan != plannedAction || plannedSchedulerTick != schedulerTick
                || !useClaimed || !UseInputLease.getInstance().owns(BROKER) || !canRun(client)) return;
            BROKER.run(this, () -> executePlan(client, plan));
        });
    }

    private void advanceStartTick(MinecraftClient client, boolean allowNewSequence) {
        if (!canRun(client) || !useHeldAtStart) { resetActiveSequence(); return; }
        if (branch != Branch.NONE && !ownsSequenceContext(client.player)) { resetActiveSequence(); return; }
        for (int attempts = 0; attempts < 2; attempts++) {
            if (branch == Branch.AUTO_HIT) {
                if (tickAutoHitCrystal(client)) return;
                resetActiveSequence();
                continue;
            }
            if (allowNewSequence && canStartAutoHitCrystal(client)) {
                branch = Branch.AUTO_HIT;
                beginSequenceOwnership(client.player, ModuleKind.AUTO_HIT_CRYSTAL);
                continue;
            }
            return;
        }
        resetActiveSequence();
    }

    private boolean tickAutoHitCrystal(MinecraftClient client) {
        ModuleContext context = context(client, ModuleKind.AUTO_HIT_CRYSTAL.moduleName);
        ItemStack mainHand = logicalMainHandStack(client.player);
        if (context == null || !autoHitEntryItem(mainHand)) return false;
        boolean actionTarget = hasAutoHitActionTarget(client);
        boolean yieldToAutoCrystal = enabled(ModuleKind.AUTO_CRYSTAL.moduleName)
            && mainHand.isOf(Items.END_CRYSTAL)
            && pendingObsidian == null && !forcedObsidianPlacement
            && !forcedCrystalPlacement && !actionTarget;
        if (yieldToAutoCrystal || !actionTarget && !mainHand.isOf(Items.END_CRYSTAL)) return false;
        claimUse();
        if (tickCounter < nextAutoHitTick) return true;

        EndCrystalEntity crystal = crosshairCrystal(client, AUTO_HIT_CRYSTAL_RANGE);
        if (crystal != null) {
            plan(new PlannedAction(ActionKind.AUTO_HIT_ATTACK, Branch.AUTO_HIT,
                null, crystal, -1, 0.0D, null));
            return true;
        }
        BlockHitResult hit = reachCheckedBlock(client);
        if (pendingObsidian != null) {
            if (hit != null && hit.getBlockPos().equals(pendingObsidian)
                && client.world.getBlockState(pendingObsidian).isOf(Blocks.OBSIDIAN)) {
                planCrystalPlacement(client, context, hit);
                return true;
            }
            if (hit != null && isCrystalBase(client, hit.getBlockPos())) {
                resetPendingObsidian();
                planCrystalPlacement(client, context, hit);
                return true;
            }
            if (withinPendingWindow(tickCounter, pendingObsidianStartTick)) return true;
            resetPendingObsidian();
        }
        if (hit == null) return true;
        if (isCrystalBase(client, hit.getBlockPos())) planCrystalPlacement(client, context, hit);
        else if (isAutoObsidianSource(client, hit.getBlockPos())) planObsidianPlacement(client, context, hit);
        return true;
    }

    private boolean canStartAutoHitCrystal(MinecraftClient client) {
        ItemStack mainHand = logicalMainHandStack(client.player);
        if (!enabled(ModuleKind.AUTO_HIT_CRYSTAL.moduleName) || !autoHitEntryItem(mainHand)) return false;
        if (hasAutoHitActionTarget(client)) return true;
        return mainHand.isOf(Items.END_CRYSTAL) && !enabled(ModuleKind.AUTO_CRYSTAL.moduleName);
    }

    private boolean hasAutoHitActionTarget(MinecraftClient client) {
        if (pendingObsidian != null || forcedObsidianPlacement || forcedCrystalPlacement) return true;
        if (crosshairCrystal(client, AUTO_HIT_CRYSTAL_RANGE) != null) return true;
        BlockHitResult hit = reachCheckedBlock(client);
        if (hit == null) return false;
        if (isCrystalBase(client, hit.getBlockPos())) return true;
        return isAutoObsidianSource(client, hit.getBlockPos())
            && !logicalMainHandStack(client.player).isOf(Items.OBSIDIAN);
    }

    private void planObsidianPlacement(MinecraftClient client, ModuleContext context,
                                       BlockHitResult hit) {
        BlockPos placementPos = placementPosition(client.player, Items.OBSIDIAN, hit);
        if (placementBlockedByPlayer(client, placementPos)) return;
        int slot = preferredHotbarSlot(client.player, Items.OBSIDIAN);
        if (slot < 0) return;
        plan(new PlannedAction(ActionKind.AUTO_HIT_OBSIDIAN, Branch.AUTO_HIT,
            hit, null, slot, context.slider("2 tick swap chance", 18.0D),
            placementPos.toImmutable()));
    }

    private void planCrystalPlacement(MinecraftClient client, ModuleContext context,
                                      BlockHitResult hit) {
        int slot = preferredHotbarSlot(client.player, Items.END_CRYSTAL);
        if (slot < 0) return;
        plan(new PlannedAction(ActionKind.AUTO_HIT_CRYSTAL, Branch.AUTO_HIT,
            hit, null, slot, context.slider("2 tick swap chance", 18.0D), null));
    }

    private void executePlan(MinecraftClient client, PlannedAction plan) {
        if (branch != plan.branch) return;
        if (!ownsTransportContext(client)) return;
        switch (plan.kind) {
            case AUTO_HIT_OBSIDIAN -> executeAutoHitObsidian(client, plan);
            case AUTO_HIT_CRYSTAL -> executeAutoHitCrystal(client, plan);
            case AUTO_HIT_ATTACK -> executeAutoHitAttack(client, plan);
            default -> { }
        }
    }

    private void executeAutoHitAttack(MinecraftClient client, PlannedAction plan) {
        if (attackCrystal(client, plan.crystal)) {
            resetPendingObsidian();
            forcedCrystalPlacement = false;
            nextAutoHitTick = tickCounter + 1;
        }
    }

    private void executeAutoHitObsidian(MinecraftClient client, PlannedAction plan) {
        boolean swapped = logicalSelectedSlot(client.player) != plan.slot;
        if (!selectOwnedSlot(client, plan.slot)) return;
        if (swapped) {
            forcedObsidianPlacement = true;
            if (rollTwoTick(plan.swapChance)) {
                nextAutoHitTick = tickCounter + 1;
                actionUsedThisTick = true;
                return;
            }
        }
        boolean accepted = validAirLiquidSupport(client, plan.hit)
            && performVanillaUse(client, plan.hit);
        if (accepted) {
            forcedObsidianPlacement = false;
            pendingObsidian = plan.placementPos;
            pendingObsidianStartTick = tickCounter;
            nextAutoHitTick = tickCounter + 1;
        } else {
            forcedObsidianPlacement = false;
            clearOwnedSlot();
        }
    }

    private void executeAutoHitCrystal(MinecraftClient client, PlannedAction plan) {
        boolean swapped = logicalSelectedSlot(client.player) != plan.slot;
        if (!selectOwnedSlot(client, plan.slot)) return;
        if (swapped) {
            forcedCrystalPlacement = true;
            if (rollTwoTick(plan.swapChance)) {
                nextAutoHitTick = tickCounter + 1;
                actionUsedThisTick = true;
                return;
            }
        }
        if (performVanillaUse(client, plan.hit)) {
            forcedCrystalPlacement = false;
            resetPendingObsidian();
            nextAutoHitTick = tickCounter + 1;
        } else {
            forcedCrystalPlacement = false;
            clearOwnedSlot();
        }
    }

    private EndCrystalEntity crosshairCrystal(MinecraftClient client, double range) {
        EntityHitResult hit = raycastEntity(client, range,
            entity -> entity instanceof EndCrystalEntity && entity.isAlive() && entity.canHit());
        return hit != null && hit.getEntity() instanceof EndCrystalEntity crystal ? crystal : null;
    }

    private EntityHitResult raycastEntity(MinecraftClient client, double range,
                                          Predicate<Entity> predicate) {
        Vec3d start = client.player.getEyePos();
        Vec3d direction = client.player.getRotationVec(1.0F);
        double lengthSquared = direction.lengthSquared();
        if (!Double.isFinite(lengthSquared) || lengthSquared <= 1.0E-12D) return null;
        Vec3d unit = direction.multiply(1.0D / Math.sqrt(lengthSquared));
        Vec3d end = start.add(unit.multiply(range));
        BlockHitResult blockHit = client.world.raycast(new RaycastContext(start, end,
            RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, client.player));
        double maximumDistance = blockHit.getType() == HitResult.Type.MISS
            ? range * range : start.squaredDistanceTo(blockHit.getPos());
        Box search = client.player.getBoundingBox().stretch(unit.multiply(range)).expand(1.0D);
        return ProjectileUtil.raycast(client.player, start, end, search, predicate, maximumDistance);
    }

    private boolean attackCrystal(MinecraftClient client, EndCrystalEntity crystal) {
        if (crystal == null || !crystal.isAlive()) return false;
        try {
            return withLogicalProjection(client, () -> {
                BROKER.interacted();
                client.interactionManager.attackEntity(client.player, crystal);
                client.player.swingHand(Hand.MAIN_HAND);
                actionUsedThisTick = true;
                return true;
            });
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private boolean performVanillaUse(MinecraftClient client, BlockHitResult hit) {
        ActionResult result = performVanillaUseResult(client, hit);
        return result != null && result.isAccepted();
    }

    private ActionResult performVanillaUseResult(MinecraftClient client, BlockHitResult hit) {
        try {
            ActionResult[] prediction = new ActionResult[1];
            withLogicalProjection(client, () -> {
                BROKER.interacted();
                ActionResult result = client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
                prediction[0] = result;
                if (result instanceof ActionResult.Success success
                    && success.swingSource() == ActionResult.SwingSource.CLIENT) {
                    client.player.swingHand(Hand.MAIN_HAND);
                }
                boolean accepted = result != null && result.isAccepted();
                if (accepted) actionUsedThisTick = true;
                return accepted;
            });
            return prediction[0];
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private BlockHitResult reachCheckedBlock(MinecraftClient client) {
        BlockHitResult hit = crosshairBlock(client);
        if (hit == null) return null;
        return client.player.getEyePos().squaredDistanceTo(hit.getPos())
            <= BLOCK_INTERACTION_RANGE * BLOCK_INTERACTION_RANGE ? hit : null;
    }

    private static BlockHitResult crosshairBlock(MinecraftClient client) {
        return client.crosshairTarget instanceof BlockHitResult hit
            && hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    private boolean isCrystalBase(MinecraftClient client, BlockPos pos) {
        BlockState state = client.world.getBlockState(pos);
        return state.isOf(Blocks.OBSIDIAN) || state.isOf(Blocks.BEDROCK);
    }

    private boolean isAutoObsidianSource(MinecraftClient client, BlockPos pos) {
        return !client.world.getBlockState(pos).isAir() && client.world.getFluidState(pos).isEmpty();
    }

    private static BlockPos placementPosition(ClientPlayerEntity player, Item item, BlockHitResult hit) {
        return new ItemPlacementContext(player, Hand.MAIN_HAND, new ItemStack(item), hit).getBlockPos();
    }

    private boolean placementBlockedByPlayer(MinecraftClient client, BlockPos placementPos) {
        Box placementBox = new Box(placementPos).contract(1.0E-4D);
        for (PlayerEntity player : client.world.getPlayers()) {
            if (player.isAlive() && !player.isSpectator()
                && player.getBoundingBox().intersects(placementBox)) return true;
        }
        return false;
    }

    private boolean validAirLiquidSupport(MinecraftClient client, BlockHitResult hit) {
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) return false;
        BlockState support = client.world.getBlockState(hit.getBlockPos());
        return !support.isAir() && !(support.getBlock() instanceof FluidBlock);
    }

    private int preferredHotbarSlot(ClientPlayerEntity player, Item item) {
        int selected = logicalSelectedSlot(player);
        if (validHotbarSlot(selected) && player.getInventory().getStack(selected).isOf(item)) return selected;
        return findHotbar(player, stack -> stack.isOf(item));
    }

    private static int findHotbar(ClientPlayerEntity player, Predicate<ItemStack> predicate) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (!stack.isEmpty() && predicate.test(stack)) return slot;
        }
        return -1;
    }

    private static boolean validHotbarSlot(int slot) { return slot >= 0 && slot < 9; }

    private boolean autoHitEntryItem(ItemStack stack) {
        return stack.isOf(Items.END_CRYSTAL) || stack.isOf(Items.OBSIDIAN) || stack.isIn(ItemTags.SWORDS);
    }

    private boolean withinPendingWindow(long currentTick, long startTick) {
        return currentTick >= startTick && currentTick - startTick <= PENDING_OBSIDIAN_WINDOW_TICKS;
    }

    private boolean rollTwoTick(double chance) { return rollPercentage(chance); }

    private boolean rollPercentage(double percentage) {
        if (percentage <= 0.0D) return false;
        if (percentage >= 100.0D) return true;
        double sample = random.nextDouble();
        return Double.isFinite(sample) && sample >= 0.0D && sample < percentage / 100.0D;
    }

    private void beginSequenceOwnership(ClientPlayerEntity player, ModuleKind kind) {
        sequencePlayer = player;
        ownedSlot = player == null ? -1 : logicalSelectedSlot(player);
        sequenceVisibleSlot = player == null ? -1 : player.getInventory().getSelectedSlot();
        ModuleContext context = player == null ? null : context(MinecraftClient.getInstance(), kind.moduleName);
        sequenceSilent = BROKER.silent() || context != null && context.toggle("Silent swap", true);
    }

    private boolean ownsSequenceContext(ClientPlayerEntity player) {
        if (player == null || player != sequencePlayer) return false;
        if (sequenceSilent || BROKER.silent()) {
            return player.getInventory().getSelectedSlot() == sequenceVisibleSlot
                && (ownedSlot < 0 || logicalSelectedSlot(player) == ownedSlot);
        }
        return ownedSlot < 0 || player.getInventory().getSelectedSlot() == ownedSlot;
    }

    private boolean ownsTransportContext(MinecraftClient client) {
        return BROKER.ownsTransport(client);
    }

    private boolean selectOwnedSlot(MinecraftClient client, int slot) {
        if (!validHotbarSlot(slot)) return false;
        if (logicalSelectedSlot(client.player) == slot) return true;
        if (!BROKER.select(client, slot, sequenceSilent)) return false;
        ownedSlot = slot;
        actionUsedThisTick = true;
        return true;
    }

    private boolean renewSilentTransport(MinecraftClient client) {
        return BROKER.renew(client);
    }

    private boolean withLogicalProjection(MinecraftClient client,
                                          java.util.function.BooleanSupplier action) {
        return BROKER.project(client, action);
    }

    private int logicalSelectedSlot(ClientPlayerEntity player) {
        return BROKER.selected(player);
    }

    private ItemStack logicalMainHandStack(ClientPlayerEntity player) {
        return BROKER.held(player);
    }

    private void clearOwnedSlot() { ownedSlot = -1; }

    private void clearSequenceOwnership() {
        sequencePlayer = null;
        ownedSlot = -1;
        sequenceVisibleSlot = -1;
        sequenceSilent = false;
    }

    private void endSilentTransport(MinecraftClient client) {
        // Yielding between policies keeps the same logical slot. Release on an actual takeover/stop.
        if (BROKER.active(this) && (!basicClientState(client) || !UseInputLease.physicalDown(client)
            || gameplayTakeoverActive(client) || BROKER.takeover(client) || !BROKER.ownsTransport(client))) BROKER.releaseTransport(client);
    }

    private void claimUse() { wantsUse = true; }

    private void releaseUse(MinecraftClient client) {
        if (BROKER.active(this) && (useClaimed || UseInputLease.getInstance().owns(BROKER))) {
            UseInputLease.getInstance().release(BROKER, client);
        }
        useClaimed = false;
    }

    private void plan(PlannedAction action) {
        if (plannedAction.kind != ActionKind.NONE) return;
        plannedAction = action;
        plannedSchedulerTick = ActionScheduler.getInstance().tickNumber();
    }

    private void reserveCombatOwnershipIfNeeded() {
        if (plannedAction.kind != ActionKind.NONE || !wantsUse) return;
        if (forcedObsidianPlacement || forcedCrystalPlacement || pendingObsidian != null)
            plan(new PlannedAction(ActionKind.RESERVE, branch, null, null, -1, 0.0D, null));
    }

    private void clearPlan() {
        plannedAction = PlannedAction.NONE;
        plannedSchedulerTick = Long.MIN_VALUE;
        ActionScheduler.getInstance().cancel(this);
        RotationManager.getInstance().release(this);
    }

    private void resetWorldState(ClientWorld world, ClientPlayerEntity player, MinecraftClient client) {
        resetEverything(client);
        observedWorld = world;
        observedPlayer = player;
    }

    private void resetEverything(MinecraftClient client) {
        resetAutoHitState();
        branch = Branch.NONE;
        clearSequenceOwnership();
        observedWorld = null;
        observedPlayer = null;
        useHeldAtStart = false;
        actionUsedThisTick = false;
        silentTakeoverUntilUseRelease = false;
        clearPlan();
        endSilentTransport(client);
        releaseUse(client);
    }

    private void resetActiveSequence() {
        resetAutoHitState();
        branch = Branch.NONE;
        clearSequenceOwnership();
    }

    private void resetAutoHitState() {
        resetPendingObsidian();
        forcedCrystalPlacement = false;
        nextAutoHitTick = 0;
    }

    private void resetPendingObsidian() {
        pendingObsidian = null;
        pendingObsidianStartTick = 0;
        forcedObsidianPlacement = false;
        clearOwnedSlot();
    }

    private static boolean basicClientState(MinecraftClient client) {
        return client != null && client.player != null && client.world != null
            && client.interactionManager != null && client.options != null;
    }

    private static boolean gameplayTakeoverActive(MinecraftClient client) {
        if (client == null || client.options == null) return false;
        if (activeOrQueued(client.options.attackKey)
            || activeOrQueued(client.options.dropKey)
            || activeOrQueued(client.options.swapHandsKey)) return true;
        for (KeyBinding hotbarKey : client.options.hotbarKeys) {
            if (activeOrQueued(hotbarKey)) return true;
        }
        return false;
    }

    private boolean silentTakeoverActive(MinecraftClient client) {
        return BROKER.silent() && (BROKER.takeover(client) || gameplayTakeoverActive(client));
    }

    private static boolean activeOrQueued(KeyBinding binding) {
        return binding != null && (binding.isPressed()
            || MinecraftAccess.queuedPresses(binding) > 0);
    }

    private boolean canRun(MinecraftClient client) {
        return basicClientState(client) && client.currentScreen == null
            && client.getOverlay() == null && client.player.isAlive()
            && !client.player.isSpectator()
            && validHotbarSlot(client.player.getInventory().getSelectedSlot())
            && observedWorld == client.world && observedPlayer == client.player;
    }

    private static boolean enabled(String name) {
        GuiModule module = module(name);
        return module != null && module.enabled();
    }

    private static ModuleContext context(MinecraftClient client, String name) {
        GuiModule module = module(name);
        return module != null && module.enabled() ? new ModuleContext(client, module) : null;
    }

    private static GuiModule module(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        return GuiCatalog.modules().stream()
            .filter(candidate -> candidate.name().toLowerCase(Locale.ROOT).equals(key))
            .findFirst().orElse(null);
    }

    private static final class PlannedAction {
        private static final PlannedAction NONE = new PlannedAction(
            ActionKind.NONE, Branch.NONE, null, null, -1, 0.0D, null);
        private final ActionKind kind;
        private final Branch branch;
        private final BlockHitResult hit;
        private final EndCrystalEntity crystal;
        private final int slot;
        private final double swapChance;
        private final BlockPos placementPos;

        private PlannedAction(ActionKind kind, Branch branch, BlockHitResult hit,
                              EndCrystalEntity crystal, int slot, double swapChance,
                              BlockPos placementPos) {
            this.kind = kind;
            this.branch = branch;
            this.hit = hit;
            this.crystal = crystal;
            this.slot = slot;
            this.swapChance = swapChance;
            this.placementPos = placementPos;
        }
    }
}
