package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.action.UseInputLease;
import dev.c4lam.celeste.gui.model.GuiCatalog;
import dev.c4lam.celeste.gui.model.GuiModule;
import dev.c4lam.celeste.compat.MinecraftAccess;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.expansion.inventory.SilentInventoryScreen;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.Block;
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
import net.minecraft.util.math.Direction;
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

/** Exact Yarn adaptation of Villian Utility's shared crystal-combat runtime. */
final class CrystalCombatCoordinator {
    static final CrystalCombatCoordinator INSTANCE = new CrystalCombatCoordinator();

    enum ModuleKind {
        AUTO_CRYSTAL("Auto Crystal"),
        AUTO_HIT_CRYSTAL("Auto Hit Crystal"),
        ANCHOR_MACRO("Anchor Macro");

        final String moduleName;
        ModuleKind(String moduleName) { this.moduleName = moduleName; }
    }

    private enum Branch { NONE, ANCHOR, AUTO_HIT, AUTO_CRYSTAL }
    private enum CrystalStep { PLACE, BREAK }
    private enum DoubleAnchorStep { NONE, REPLACE, CHARGE, EXPLODE }
    private enum ActionKind {
        NONE, RESERVE, AUTO_CRYSTAL_USE, AUTO_CRYSTAL_ATTACK,
        AUTO_HIT_OBSIDIAN, AUTO_HIT_CRYSTAL, AUTO_HIT_ATTACK,
        ANCHOR_PLACE, ANCHOR_GLOWSTONE, ANCHOR_EXPLODE,
        DOUBLE_ANCHOR_FIRST_EXPLODE, DOUBLE_ANCHOR_REPLACE,
        DOUBLE_ANCHOR_GLOWSTONE, DOUBLE_ANCHOR_FINISH
    }

    private static final int PRIORITY = 190;
    private static final double CRYSTAL_TARGET_RANGE = 8.0D;
    private static final double AUTO_HIT_CRYSTAL_RANGE = 3.35D;
    private static final double BLOCK_INTERACTION_RANGE = 4.45D;
    private static final int PENDING_OBSIDIAN_WINDOW_TICKS = 2;
    private static final int ANCHOR_CONFIRMATION_TICKS = 4;
    private static final int TOTEM_MEMORY_TICKS = 80;

    private final Random random = new Random();
    private final Set<BlockPos> ownedAnchors = new HashSet<>();
    private final Map<BlockPos, Long> recentAnchorCandidates = new HashMap<>();
    private final long[] rememberedTotemUntilTick = new long[9];

    private Branch branch = Branch.NONE;
    private CrystalStep autoCrystalStep = CrystalStep.PLACE;
    private long tickCounter;
    private long nextAutoCrystalTick;
    private UUID trackedCrystalTarget;
    private BlockPos pendingObsidian;
    private long pendingObsidianStartTick;
    private long nextAutoHitTick;
    private boolean forcedObsidianPlacement;
    private boolean forcedCrystalPlacement;
    private long nextAnchorTick;
    private BlockPos activeAnchorPos;
    private BlockPos pendingAnchorPlace;
    private long pendingAnchorStartTick;
    private boolean waitingForAnchor;
    private boolean anchorFinishedUntilRelease;
    private int anchorGlowstoneGoal = 1;
    private int anchorGlowstoneUses;
    private int chargeBeforeUse;
    private long glowstoneUseTick = Long.MIN_VALUE;
    private BlockPos manualAnchorCandidate;
    private long manualAnchorCandidateTick;
    private boolean anchorCycleStarted;
    private boolean doubleAnchorCycle;
    private boolean doubleAnchorHoldConsumed;
    private boolean doubleAnchorUseBlocked;
    private DoubleAnchorStep doubleAnchorStep = DoubleAnchorStep.NONE;
    private BlockHitResult anchorSupportHit;
    private BlockState anchorSupportState;
    private BlockHitResult doubleAnchorHit;
    private long doubleAnchorReplaceTick;
    private boolean doubleAnchorAwaitingReplacement;

    private ClientWorld observedWorld;
    private ClientPlayerEntity observedPlayer;
    private boolean useHeldAtStart;
    private ClientPlayerEntity sequencePlayer;
    private int ownedSlot = -1;
    private int sequenceVisibleSlot = -1;
    private boolean sequenceSilent;

    /* The source's selected slot, separated from its visible presentation. */
    private boolean silentTransport;
    private ClientPlayerEntity transportPlayer;
    private ModuleKind transportModuleKind;
    private int transportVisibleSlot = -1;
    private int logicalSlot = -1;

    private long startSchedulerTick = Long.MIN_VALUE;
    private long actionSchedulerTick = Long.MIN_VALUE;
    private long plannedSchedulerTick = Long.MIN_VALUE;
    private boolean wantsUse;
    private boolean useClaimed;
    private boolean actionUsedThisTick;
    private long interactionAttemptSchedulerTick = Long.MIN_VALUE;
    private boolean silentTakeoverUntilUseRelease;
    private PlannedAction plannedAction = PlannedAction.NONE;

    private CrystalCombatCoordinator() {}

    void onStartTick(ModuleContext caller) {
        long schedulerTick = ActionScheduler.getInstance().tickNumber();
        if (startSchedulerTick == schedulerTick) return;
        startSchedulerTick = schedulerTick;
        tickCounter++;
        actionUsedThisTick = false;
        wantsUse = false;
        clearPlan();

        MinecraftClient client = caller.client();
        // Only an observed physical release rearms the modifier. Cycle, GUI,
        // module and world cleanup must not turn one held key into repeated pairs.
        if (client != null && client.isWindowFocused() && !DoubleAnchorMacroFeature.holdKeyDown(client))
            doubleAnchorHoldConsumed = false;
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
        if (!UseInputLease.physicalDown(client)) doubleAnchorUseBlocked = false;
        if (silentTakeoverActive(client) || branch == Branch.ANCHOR && doubleAnchorCycle
            && (gameplayTakeoverActive(client) || !ownsSequenceContext(client.player))) {
            silentTakeoverUntilUseRelease = true;
        }
        if (silentTakeoverUntilUseRelease) {
            resetActiveSequence();
            endSilentTransport(client);
            if (doubleAnchorUseBlocked && useHeldAtStart) retainDoubleAnchorUse(client);
            else releaseUse(client);
            if (!useHeldAtStart) {
                silentTakeoverUntilUseRelease = false;
                anchorFinishedUntilRelease = false;
            }
            return;
        }
        if (client.currentScreen instanceof SilentInventoryScreen) {
            // Silent Totem temporarily opens a real player inventory so its
            // clicks are vanilla-valid. That screen must pause interactions,
            // but it is not a user takeover: retaining the held-use branch is
            // essential for Auto Crystal's PLACE -> BREAK sequence to resume
            // after the inventory closes. Releasing the synthetic use lease
            // prevents any action from being emitted while the screen is up.
            releaseUse(client);
            return;
        }
        if (silentTransport && !renewSilentTransport(client)) {
            if (branch == Branch.ANCHOR && doubleAnchorCycle) {
                interruptDoubleAnchor(client);
                return;
            }
            resetActiveSequence();
            endSilentTransport(client);
        }
        if (branch != Branch.NONE && !ownsSequenceContext(client.player)) {
            resetActiveSequence();
            endSilentTransport(client);
        }

        rememberVisibleTotems(client.player);
        trackManualAnchorPlacement(client);
        pruneOwnedAnchors(client);
        if (!useHeldAtStart) {
            resetActiveSequence();
            anchorFinishedUntilRelease = false;
            endSilentTransport(client);
            releaseUse(client);
            return;
        }

        advanceStartTick(client, true);
        reserveCombatOwnershipIfNeeded();
        if (wantsUse) {
            useClaimed = UseInputLease.getInstance().acquire(
                this, PRIORITY, schedulerTick, client);
            if (!useClaimed) clearPlan();
        } else {
            if (branch == Branch.NONE) endSilentTransport(client);
            releaseUse(client);
        }
    }

    void onActionTick(ModuleContext ignored) {
        long schedulerTick = ActionScheduler.getInstance().tickNumber();
        if (actionSchedulerTick == schedulerTick) return;
        actionSchedulerTick = schedulerTick;
        if (!useClaimed || !UseInputLease.getInstance().owns(this) || plannedAction.kind == ActionKind.NONE
            || plannedSchedulerTick != schedulerTick) return;
        PlannedAction plan = plannedAction;
        ActionScheduler.getInstance().submit(this, PRIORITY, client -> {
            if (plan != plannedAction || plannedSchedulerTick != schedulerTick
                || !useClaimed || !UseInputLease.getInstance().owns(this) || !canRun(client)) return;
            executePlan(client, plan);
        });
    }

    void onModuleDisabled(ModuleKind kind, MinecraftClient client) {
        boolean ownedActiveBranch = branch != Branch.NONE && moduleKind(branch) == kind;
        boolean ownedSilentTransport = silentTransport && transportModuleKind == kind;
        if (ownedActiveBranch) resetActiveSequence();
        if (ownedActiveBranch || ownedSilentTransport) {
            endSilentTransport(client);
            releaseUse(client);
        }
        if (!enabled(ModuleKind.AUTO_CRYSTAL.moduleName)
            && !enabled(ModuleKind.AUTO_HIT_CRYSTAL.moduleName)
            && !enabled(ModuleKind.ANCHOR_MACRO.moduleName)) {
            endSilentTransport(client);
            releaseUse(client);
        }
        clearPlan();
    }

    void onWorldChanged(MinecraftClient client) { resetEverything(client); }

    void onDoubleAnchorDisabled(MinecraftClient client) {
        if (branch == Branch.ANCHOR && doubleAnchorCycle) interruptDoubleAnchor(client);
    }

    private void advanceStartTick(MinecraftClient client, boolean allowNewSequence) {
        if (!canRun(client) || !useHeldAtStart) {
            resetActiveSequence();
            return;
        }
        if (branch != Branch.NONE && !ownsSequenceContext(client.player)) {
            resetActiveSequence();
            return;
        }
        for (int attempts = 0; attempts < 4; attempts++) {
            if (branch == Branch.ANCHOR) {
                if (tickAnchor(client)) return;
                resetActiveSequence();
                continue;
            }
            if (branch == Branch.AUTO_HIT) {
                if (tickAutoHitCrystal(client)) return;
                resetActiveSequence();
                continue;
            }
            if (branch == Branch.AUTO_CRYSTAL) {
                if (tickAutoCrystal(client)) return;
                resetActiveSequence();
                continue;
            }
            if (!allowNewSequence) return;
            if (canStartAnchor(client)) {
                branch = Branch.ANCHOR;
                beginSequenceOwnership(client.player, ModuleKind.ANCHOR_MACRO);
                continue;
            }
            if (canStartAutoHitCrystal(client)) {
                branch = Branch.AUTO_HIT;
                beginSequenceOwnership(client.player, ModuleKind.AUTO_HIT_CRYSTAL);
                continue;
            }
            if (canStartAutoCrystal(client)) {
                branch = Branch.AUTO_CRYSTAL;
                if (silentTransport) transportModuleKind = ModuleKind.AUTO_CRYSTAL;
                autoCrystalStep = CrystalStep.PLACE;
                nextAutoCrystalTick = tickCounter;
                trackedCrystalTarget = null;
                continue;
            }
            return;
        }
        resetActiveSequence();
    }

    private boolean tickAutoCrystal(MinecraftClient client) {
        ModuleContext context = context(client, ModuleKind.AUTO_CRYSTAL.moduleName);
        if (context == null || !logicalMainHandStack(client.player).isOf(Items.END_CRYSTAL)) return false;
        claimUse();
        PlayerEntity target = findBestCrystalTarget(client);
        if (target != null) trackedCrystalTarget = target.getUuid();
        else if (context.toggle("Stop on kill", true) && trackedCrystalTarget != null
            && trackedTargetDead(client, trackedCrystalTarget)) return true;
        if (context.toggle("Damage tick", true) && target != null && target.hurtTime > 1) return true;
        if (tickCounter < nextAutoCrystalTick) return true;
        if (autoCrystalStep == CrystalStep.PLACE) {
            BlockHitResult hit = crosshairBlock(client);
            if (hit != null) plan(new PlannedAction(ActionKind.AUTO_CRYSTAL_USE,
                Branch.AUTO_CRYSTAL, hit, null, -1, 0.0D, null));
        } else {
            EndCrystalEntity crystal = crosshairCrystal(client, CRYSTAL_TARGET_RANGE);
            if (crystal != null) plan(new PlannedAction(ActionKind.AUTO_CRYSTAL_ATTACK,
                Branch.AUTO_CRYSTAL, null, crystal, -1, 0.0D, null));
        }
        return true;
    }

    private boolean canStartAutoCrystal(MinecraftClient client) {
        return enabled(ModuleKind.AUTO_CRYSTAL.moduleName)
            && logicalMainHandStack(client.player).isOf(Items.END_CRYSTAL);
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

    private boolean tickAnchor(MinecraftClient client) {
        ModuleContext context = context(client, ModuleKind.ANCHOR_MACRO.moduleName);
        if (context == null) {
            resetAnchorState();
            anchorFinishedUntilRelease = false;
            return false;
        }
        if (anchorFinishedUntilRelease) {
            claimUse();
            return true;
        }
        if (doubleAnchorStep != DoubleAnchorStep.NONE) return tickDoubleAnchorFollowup(client, context);
        BlockHitResult hit = crosshairBlock(client);
        if (!shouldRunAnchor(client, context, hit)) {
            if (doubleAnchorCycle) {
                interruptDoubleAnchor(client);
                return true;
            }
            resetAnchorState();
            anchorFinishedUntilRelease = false;
            return false;
        }
        claimUse();
        if (!anchorCycleStarted) {
            anchorCycleStarted = true;
            doubleAnchorCycle = !doubleAnchorHoldConsumed && DoubleAnchorMacroFeature.held(client);
            if (doubleAnchorCycle) {
                doubleAnchorHoldConsumed = true;
                doubleAnchorUseBlocked = true;
            }
        }
        if (doubleAnchorCycle && !doubleAnchorContextAllowed(client)) return true;

        if (waitingForAnchor) {
            if (pendingAnchorPlace != null && isAnchor(client, pendingAnchorPlace)) {
                ownedAnchors.add(pendingAnchorPlace);
                activeAnchorPos = pendingAnchorPlace;
            }
            boolean confirmed = hit != null && pendingAnchorPlace != null
                && hit.getBlockPos().equals(pendingAnchorPlace) && isAnchor(client, pendingAnchorPlace);
            if (!confirmed) {
                if (tickCounter - pendingAnchorStartTick >= ANCHOR_CONFIRMATION_TICKS) {
                    if (pendingAnchorPlace != null && !isAnchor(client, pendingAnchorPlace)) {
                        ownedAnchors.remove(pendingAnchorPlace);
                    }
                    if (doubleAnchorCycle) {
                        interruptDoubleAnchor(client);
                        return true;
                    }
                    resetAnchorState();
                    nextAnchorTick = tickCounter + 1;
                }
                return true;
            }
            waitingForAnchor = false;
            pendingAnchorStartTick = 0;
            pendingAnchorPlace = null;
            initializeAnchorCharge(client, hit.getBlockPos(), context);
        }

        if (hit == null) {
            resetAnchorState();
            return false;
        }
        if (tickCounter < nextAnchorTick) return true;
        if (doubleAnchorCycle && activeAnchorPos != null
            && (!activeAnchorPos.equals(hit.getBlockPos()) || !isAnchor(client, activeAnchorPos))) {
            interruptDoubleAnchor(client);
            return true;
        }
        if (!isAnchor(client, hit.getBlockPos())) {
            planAnchorPlacement(client, context, hit);
            return true;
        }
        BlockPos anchorPos = hit.getBlockPos();
        if (!anchorPos.equals(activeAnchorPos)) initializeAnchorCharge(client, anchorPos, context);
        int charges = anchorCharges(client, anchorPos);
        if (glowstoneUseTick != Long.MIN_VALUE) {
            if (charges > chargeBeforeUse) glowstoneUseTick = Long.MIN_VALUE;
            else if (tickCounter - glowstoneUseTick < ANCHOR_CONFIRMATION_TICKS) return true;
            else if (doubleAnchorCycle) {
                interruptDoubleAnchor(client);
                return true;
            }
            else glowstoneUseTick = Long.MIN_VALUE;
        }
        if (charges < anchorGlowstoneGoal) {
            // A delayed/rolled-back charge update must not emit a second
            // glowstone use for the first anchor in a double sequence.
            if (doubleAnchorCycle && anchorGlowstoneUses > 0) {
                interruptDoubleAnchor(client);
                return true;
            }
            int slot = findHotbar(client.player, stack -> stack.isOf(Items.GLOWSTONE));
            if (slot < 0) return true;
            plan(new PlannedAction(ActionKind.ANCHOR_GLOWSTONE, Branch.ANCHOR,
                hit, null, slot, context.slider("2 tick swap chance", 18.0D), null));
            return true;
        }
        if (doubleAnchorCycle) {
            int anchorSlot = preferredHotbarSlot(client.player, Items.RESPAWN_ANCHOR);
            if (anchorSlot < 0 || !hasDoubleAnchorFinishItems(client)) return true;
            BlockHitResult support = replacementSupport(client, anchorPos);
            if (support == null) return true;
            anchorSupportHit = support;
            anchorSupportState = client.world.getBlockState(support.getBlockPos());
            plan(new PlannedAction(ActionKind.DOUBLE_ANCHOR_FIRST_EXPLODE, Branch.ANCHOR,
                hit, null, anchorSlot, context.slider("2 tick swap chance", 18.0D), anchorPos.toImmutable()));
            return true;
        }
        int explosionSlot = findTotemSlot(client.player);
        if (explosionSlot < 0 && !canExplodeWithCurrentHand(client.player)) return true;
        plan(new PlannedAction(ActionKind.ANCHOR_EXPLODE, Branch.ANCHOR,
            hit, null, explosionSlot, context.slider("2 tick swap chance", 18.0D), null));
        return true;
    }

    private boolean canStartAnchor(MinecraftClient client) {
        ModuleContext context = context(client, ModuleKind.ANCHOR_MACRO.moduleName);
        return context != null && shouldRunAnchor(client, context, crosshairBlock(client));
    }

    private boolean shouldRunAnchor(MinecraftClient client, ModuleContext context,
                                    BlockHitResult hit) {
        if (hit == null || !anchorEntryItem(client.player)) return false;
        BlockPos hitPos = hit.getBlockPos();
        if (isAnchor(client, hitPos)) {
            return !context.toggle("Only own anchors", true) || ownedAnchors.contains(hitPos);
        }
        BlockPos placementPos = placementPosition(client.player, Items.RESPAWN_ANCHOR, hit);
        ownedAnchors.remove(hitPos);
        if (placementBlockedByPlayer(client, placementPos)) return false;
        return context.toggle("Auto swap anchor", true)
            && findHotbar(client.player, stack -> stack.isOf(Items.RESPAWN_ANCHOR)) >= 0;
    }

    private void planAnchorPlacement(MinecraftClient client, ModuleContext context,
                                     BlockHitResult hit) {
        if (!context.toggle("Auto swap anchor", true)) {
            resetAnchorState();
            return;
        }
        if (doubleAnchorCycle && (hotbarCount(client.player, Items.RESPAWN_ANCHOR) < 2
            || hotbarCount(client.player, Items.GLOWSTONE) < 2
            || findHotbar(client.player, stack -> stack.isOf(Items.TOTEM_OF_UNDYING)) < 0)) return;
        int slot = findHotbar(client.player, stack -> stack.isOf(Items.RESPAWN_ANCHOR));
        if (slot < 0) {
            resetAnchorState();
            return;
        }
        BlockPos placement = placementPosition(client.player, Items.RESPAWN_ANCHOR, hit).toImmutable();
        plan(new PlannedAction(ActionKind.ANCHOR_PLACE, Branch.ANCHOR,
            hit, null, slot, context.slider("2 tick swap chance", 18.0D), placement));
    }

    private void executePlan(MinecraftClient client, PlannedAction plan) {
        if (branch != plan.branch) return;
        if (!ownsTransportContext(client)) {
            if (branch == Branch.ANCHOR && doubleAnchorCycle) interruptDoubleAnchor(client);
            return;
        }
        if (plan.branch == Branch.ANCHOR && doubleAnchorCycle
            && (!doubleAnchorContextAllowed(client) || !ownsSequenceContext(client.player))) {
            interruptDoubleAnchor(client);
            return;
        }
        switch (plan.kind) {
            case RESERVE -> { }
            case AUTO_CRYSTAL_USE -> executeAutoCrystalUse(client, plan);
            case AUTO_CRYSTAL_ATTACK -> executeAutoCrystalAttack(client, plan);
            case AUTO_HIT_OBSIDIAN -> executeAutoHitObsidian(client, plan);
            case AUTO_HIT_CRYSTAL -> executeAutoHitCrystal(client, plan);
            case AUTO_HIT_ATTACK -> executeAutoHitAttack(client, plan);
            case ANCHOR_PLACE -> executeAnchorPlace(client, plan);
            case ANCHOR_GLOWSTONE -> executeAnchorGlowstone(client, plan);
            case ANCHOR_EXPLODE -> executeAnchorExplode(client, plan);
            case DOUBLE_ANCHOR_FIRST_EXPLODE -> executeDoubleAnchorFirstExplode(client, plan);
            case DOUBLE_ANCHOR_REPLACE, DOUBLE_ANCHOR_GLOWSTONE, DOUBLE_ANCHOR_FINISH ->
                executeDoubleAnchorFollowup(client, plan);
            case NONE -> { }
        }
    }

    private void executeAutoCrystalUse(MinecraftClient client, PlannedAction plan) {
        if (autoCrystalStep == CrystalStep.PLACE && performVanillaUse(client, plan.hit)) {
            autoCrystalStep = CrystalStep.BREAK;
            ModuleContext context = context(client, ModuleKind.AUTO_CRYSTAL.moduleName);
            nextAutoCrystalTick = tickCounter + sampledDelayTicks(
                settingInt(context, "Place delay min", 1),
                settingInt(context, "Place delay max", 2), random.nextDouble());
        }
    }

    private void executeAutoCrystalAttack(MinecraftClient client, PlannedAction plan) {
        if (autoCrystalStep == CrystalStep.BREAK && attackCrystal(client, plan.crystal)) {
            autoCrystalStep = CrystalStep.PLACE;
            ModuleContext context = context(client, ModuleKind.AUTO_CRYSTAL.moduleName);
            nextAutoCrystalTick = tickCounter + sampledDelayTicks(
                settingInt(context, "Break delay min", 1),
                settingInt(context, "Break delay max", 2), random.nextDouble());
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

    private void executeAnchorPlace(MinecraftClient client, PlannedAction plan) {
        if (doubleAnchorCycle && (!client.player.getInventory().getStack(plan.slot).isOf(Items.RESPAWN_ANCHOR)
            || hotbarCount(client.player, Items.RESPAWN_ANCHOR) < 2
            || hotbarCount(client.player, Items.GLOWSTONE) < 2 || !hasDoubleAnchorFinishItems(client)
            || client.world.getEnvironmentAttributes().getAttributeValue(
                EnvironmentAttributes.RESPAWN_ANCHOR_WORKS_GAMEPLAY, plan.placementPos))) {
            interruptDoubleAnchor(client);
            return;
        }
        pendingAnchorPlace = plan.placementPos;
        boolean swapped = logicalSelectedSlot(client.player) != plan.slot;
        if (!selectOwnedSlot(client, plan.slot)) return;
        if (swapped && rollTwoTick(plan.swapChance)) {
            nextAnchorTick = tickCounter + 1;
            actionUsedThisTick = true;
            return;
        }
        if (validAirLiquidSupport(client, plan.hit) && performVanillaUse(client, plan.hit)) {
            anchorSupportHit = plan.hit;
            anchorSupportState = client.world.getBlockState(plan.hit.getBlockPos());
            activeAnchorPos = pendingAnchorPlace;
            recentAnchorCandidates.put(pendingAnchorPlace, tickCounter);
            waitingForAnchor = true;
            pendingAnchorStartTick = tickCounter;
            nextAnchorTick = tickCounter + 1;
        } else {
            pendingAnchorPlace = null;
            waitingForAnchor = false;
            pendingAnchorStartTick = 0;
            if (doubleAnchorCycle) interruptDoubleAnchor(client);
        }
    }

    private void executeAnchorGlowstone(MinecraftClient client, PlannedAction plan) {
        if (!validAirLiquidSupport(client, plan.hit) || !isAnchor(client, plan.hit.getBlockPos())
            || doubleAnchorCycle && (!validDoubleAnchorHit(client, plan.hit)
                || anchorCharges(client, plan.hit.getBlockPos()) != 0 || anchorGlowstoneUses > 0
                || !client.player.getInventory().getStack(plan.slot).isOf(Items.GLOWSTONE))) {
            if (doubleAnchorCycle) interruptDoubleAnchor(client);
            return;
        }
        boolean swapped = logicalSelectedSlot(client.player) != plan.slot;
        if (!selectOwnedSlot(client, plan.slot)) return;
        if (swapped && rollTwoTick(plan.swapChance)) {
            nextAnchorTick = tickCounter + 1;
            actionUsedThisTick = true;
            return;
        }
        int charges = anchorCharges(client, plan.hit.getBlockPos());
        if (performVanillaUse(client, plan.hit)) {
            anchorGlowstoneUses++;
            chargeBeforeUse = charges;
            glowstoneUseTick = tickCounter;
            nextAnchorTick = tickCounter + 1;
        } else if (doubleAnchorCycle) interruptDoubleAnchor(client);
    }

    private void executeAnchorExplode(MinecraftClient client, PlannedAction plan) {
        if (!validAirLiquidSupport(client, plan.hit) || !isAnchor(client, plan.hit.getBlockPos())
            || anchorCharges(client, plan.hit.getBlockPos()) <= 0) return;
        boolean swapped = plan.slot >= 0
            && logicalSelectedSlot(client.player) != plan.slot;
        if (plan.slot >= 0 && !selectOwnedSlot(client, plan.slot)) return;
        if (swapped && rollTwoTick(plan.swapChance)) {
            nextAnchorTick = tickCounter + 1;
            actionUsedThisTick = true;
            return;
        }
        if (performVanillaUse(client, plan.hit)) finishAnchorAfterUse(client);
    }

    private void initializeAnchorCharge(MinecraftClient client, BlockPos anchorPos,
                                        ModuleContext context) {
        activeAnchorPos = anchorPos.toImmutable();
        anchorGlowstoneUses = 0;
        glowstoneUseTick = Long.MIN_VALUE;
        int existingCharges = anchorCharges(client, anchorPos);
        anchorGlowstoneGoal = doubleAnchorCycle ? 1 : existingCharges <= 0
            && rollPercentage(context.slider("Double glowstone", 0.0D))
            ? 2 : Math.max(1, existingCharges);
    }

    private boolean tickDoubleAnchorFollowup(MinecraftClient client, ModuleContext context) {
        claimUse();
        // Never replay a missed replacement on a later tick (GUI, inventory takeover,
        // scheduler contention, and lost focus can legitimately interrupt a macro).
        if (!doubleAnchorContextAllowed(client) || doubleAnchorHit == null
            || !validDoubleAnchorHit(client, doubleAnchorHit)
            || doubleAnchorStep == DoubleAnchorStep.REPLACE && tickCounter > doubleAnchorReplaceTick) {
            interruptDoubleAnchor(client);
            return true;
        }
        if (tickCounter < nextAnchorTick) return true;
        if (doubleAnchorAwaitingReplacement) {
            // The explosion is server-side. Its old charged block can make the
            // following placement fail local prediction even after USE was sent.
            // Wait for the real replacement; never replay that placement click.
            BlockPos replacement = doubleAnchorHit.getBlockPos();
            if (isAnchor(client, replacement) && anchorCharges(client, replacement) == 0) {
                doubleAnchorAwaitingReplacement = false;
                recentAnchorCandidates.put(replacement, tickCounter);
            } else {
                if (tickCounter - doubleAnchorReplaceTick >= ANCHOR_CONFIRMATION_TICKS)
                    interruptDoubleAnchor(client);
                return true;
            }
        }
        Item required = switch (doubleAnchorStep) {
            case REPLACE -> Items.RESPAWN_ANCHOR;
            case CHARGE -> Items.GLOWSTONE;
            case EXPLODE -> Items.TOTEM_OF_UNDYING;
            case NONE -> throw new IllegalStateException("No double-anchor continuation");
        };
        int slot = preferredHotbarSlot(client.player, required);
        if (slot < 0 || doubleAnchorStep == DoubleAnchorStep.REPLACE
            && (!validDoubleAnchorSupport(client, anchorSupportHit) || !hasDoubleAnchorFinishItems(client))) {
            interruptDoubleAnchor(client);
            return true;
        }
        ActionKind kind = switch (doubleAnchorStep) {
            case REPLACE -> ActionKind.DOUBLE_ANCHOR_REPLACE;
            case CHARGE -> ActionKind.DOUBLE_ANCHOR_GLOWSTONE;
            case EXPLODE -> ActionKind.DOUBLE_ANCHOR_FINISH;
            case NONE -> throw new IllegalStateException("No double-anchor continuation");
        };
        plan(new PlannedAction(kind, Branch.ANCHOR,
            doubleAnchorStep == DoubleAnchorStep.REPLACE ? anchorSupportHit : doubleAnchorHit,
            null, slot, doubleAnchorStep == DoubleAnchorStep.REPLACE ? 0.0D
                : context.slider("2 tick swap chance", 18.0D), doubleAnchorHit.getBlockPos()));
        return true;
    }

    private void executeDoubleAnchorFirstExplode(MinecraftClient client, PlannedAction plan) {
        if (!isAnchor(client, plan.placementPos) || anchorCharges(client, plan.placementPos) < 1
            || !validDoubleAnchorHit(client, plan.hit) || !validDoubleAnchorSupport(client, anchorSupportHit)
            || !client.player.getInventory().getStack(plan.slot).isOf(Items.RESPAWN_ANCHOR)
            || !hasDoubleAnchorFinishItems(client)) {
            interruptDoubleAnchor(client);
            return;
        }
        boolean swapped = logicalSelectedSlot(client.player) != plan.slot;
        if (!selectOwnedSlot(client, plan.slot)) return;
        if (swapped && rollTwoTick(plan.swapChance)) {
            nextAnchorTick = tickCounter + 1;
            actionUsedThisTick = true;
            return;
        }
        if (!performVanillaUse(client, plan.hit)) {
            interruptDoubleAnchor(client);
            return;
        }
        doubleAnchorHit = plan.hit;
        doubleAnchorStep = DoubleAnchorStep.REPLACE;
        doubleAnchorReplaceTick = tickCounter + 1;
        nextAnchorTick = doubleAnchorReplaceTick;
        waitingForAnchor = false;
        pendingAnchorPlace = null;
        glowstoneUseTick = Long.MIN_VALUE;
        ownedAnchors.remove(plan.placementPos);
        recentAnchorCandidates.remove(plan.placementPos);
    }

    private void executeDoubleAnchorFollowup(MinecraftClient client, PlannedAction plan) {
        boolean replacement = plan.kind == ActionKind.DOUBLE_ANCHOR_REPLACE;
        Item required = replacement ? Items.RESPAWN_ANCHOR
            : plan.kind == ActionKind.DOUBLE_ANCHOR_GLOWSTONE ? Items.GLOWSTONE : Items.TOTEM_OF_UNDYING;
        if (!validDoubleAnchorHit(client, plan.hit)
            || !client.player.getInventory().getStack(plan.slot).isOf(required)
            || replacement && (tickCounter != doubleAnchorReplaceTick
                || !validDoubleAnchorSupport(client, plan.hit) || !hasDoubleAnchorFinishItems(client))
            || !replacement && (!isAnchor(client, plan.hit.getBlockPos())
                || (plan.kind == ActionKind.DOUBLE_ANCHOR_GLOWSTONE
                    ? anchorCharges(client, plan.hit.getBlockPos()) != 0
                    : anchorCharges(client, plan.hit.getBlockPos()) <= 0))) {
            interruptDoubleAnchor(client);
            return;
        }
        boolean swapped = logicalSelectedSlot(client.player) != plan.slot;
        if (!selectOwnedSlot(client, plan.slot)) {
            if (replacement) interruptDoubleAnchor(client);
            return;
        }
        // The replacement's deadline never samples the two-tick swap chance,
        // including when its anchor has moved to another hotbar slot.
        if (!replacement && swapped && rollTwoTick(plan.swapChance)) {
            nextAnchorTick = tickCounter + 1;
            actionUsedThisTick = true;
            return;
        }
        ActionResult result = performVanillaUseResult(client, plan.hit);
        if (result == null || !replacement && !result.isAccepted()) {
            interruptDoubleAnchor(client);
            return;
        }
        if (replacement) {
            doubleAnchorStep = DoubleAnchorStep.CHARGE;
            activeAnchorPos = plan.placementPos;
            doubleAnchorAwaitingReplacement = !result.isAccepted()
                || !isAnchor(client, plan.placementPos) || anchorCharges(client, plan.placementPos) != 0;
            if (!doubleAnchorAwaitingReplacement) recentAnchorCandidates.put(plan.placementPos, tickCounter);
        } else if (plan.kind == ActionKind.DOUBLE_ANCHOR_GLOWSTONE) {
            doubleAnchorStep = DoubleAnchorStep.EXPLODE;
        } else {
            finishAnchorAfterUse(client);
            return;
        }
        nextAnchorTick = tickCounter + 1;
    }

    private boolean doubleAnchorContextAllowed(MinecraftClient client) {
        BlockPos target = doubleAnchorHit != null ? doubleAnchorHit.getBlockPos()
            : activeAnchorPos != null ? activeAnchorPos : pendingAnchorPlace != null ? pendingAnchorPlace
                : crosshairBlock(client) != null ? crosshairBlock(client).getBlockPos() : null;
        return enabled("Double Anchor Macro") && enabled(ModuleKind.ANCHOR_MACRO.moduleName)
            && canRun(client) && client.isWindowFocused()
            && useHeldAtStart && UseInputLease.physicalDown(client)
            && !gameplayTakeoverActive(client)
            && !client.player.shouldCancelInteraction()
            && !client.player.getOffHandStack().isOf(Items.GLOWSTONE)
            && target != null
            && !client.world.getEnvironmentAttributes().getAttributeValue(
                EnvironmentAttributes.RESPAWN_ANCHOR_WORKS_GAMEPLAY, target);
    }

    private boolean hasDoubleAnchorFinishItems(MinecraftClient client) {
        return findHotbar(client.player, stack -> stack.isOf(Items.GLOWSTONE)) >= 0
            && findHotbar(client.player, stack -> stack.isOf(Items.TOTEM_OF_UNDYING)) >= 0;
    }

    private boolean validDoubleAnchorHit(MinecraftClient client, BlockHitResult hit) {
        return hit != null && hit.getType() == HitResult.Type.BLOCK
            && client.world.getWorldBorder().contains(hit.getBlockPos())
            && client.world.isChunkLoaded(hit.getBlockPos().getX() >> 4, hit.getBlockPos().getZ() >> 4)
            && !client.world.getEnvironmentAttributes().getAttributeValue(
                EnvironmentAttributes.RESPAWN_ANCHOR_WORKS_GAMEPLAY, hit.getBlockPos())
            && client.player.getEyePos().squaredDistanceTo(hit.getPos())
                <= BLOCK_INTERACTION_RANGE * BLOCK_INTERACTION_RANGE;
    }

    private boolean validDoubleAnchorSupport(MinecraftClient client, BlockHitResult hit) {
        return validDoubleAnchorHit(client, hit) && validAirLiquidSupport(client, hit)
            && (hit != anchorSupportHit || anchorSupportState == null
                || client.world.getBlockState(hit.getBlockPos()) == anchorSupportState);
    }

    private BlockHitResult replacementSupport(MinecraftClient client, BlockPos anchorPos) {
        if (validDoubleAnchorSupport(client, anchorSupportHit)
            && anchorSupportHit.getBlockPos().offset(anchorSupportHit.getSide()).equals(anchorPos)
            && replacementFaceVisible(client, anchorSupportHit, anchorPos)) return anchorSupportHit;
        for (Direction side : new Direction[]{Direction.UP, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST, Direction.DOWN}) {
            BlockPos support = anchorPos.offset(side.getOpposite());
            Vec3d point = anchorPos.toCenterPos().add(-side.getOffsetX() * .5,
                -side.getOffsetY() * .5, -side.getOffsetZ() * .5);
            BlockHitResult hit = new BlockHitResult(point, side, support, false);
            // For an anchor placed outside this macro, only infer a support whose
            // block has vanilla's inert base behavior. A chest/button/dispenser
            // could consume this click instead of placing the second anchor.
            if (client.world.getBlockState(support).getBlock().getClass() != Block.class
                || !validDoubleAnchorSupport(client, hit)) continue;
            if (replacementFaceVisible(client, hit, anchorPos)) return hit;
        }
        return null;
    }

    private boolean replacementFaceVisible(MinecraftClient client, BlockHitResult support, BlockPos anchorPos) {
        BlockHitResult obstruction = client.world.raycast(new RaycastContext(client.player.getEyePos(),
            support.getPos(), RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, client.player));
        return obstruction.getType() == HitResult.Type.MISS || obstruction.getBlockPos().equals(anchorPos)
            || obstruction.getBlockPos().equals(support.getBlockPos());
    }

    private static int hotbarCount(ClientPlayerEntity player, Item item) {
        int count = 0;
        for (int i = 0; i < 9; i++) if (player.getInventory().getStack(i).isOf(item))
            count += player.getInventory().getStack(i).getCount();
        return count;
    }

    private void interruptDoubleAnchor(MinecraftClient client) {
        resetAnchorState();
        anchorFinishedUntilRelease = true;
        clearPlan();
        clearOwnedSlot();
        endSilentTransport(client);
        retainDoubleAnchorUse(client);
    }

    private void retainDoubleAnchorUse(MinecraftClient client) {
        doubleAnchorUseBlocked = true;
        claimUse();
        useClaimed = UseInputLease.getInstance().acquire(this, PRIORITY,
            ActionScheduler.getInstance().tickNumber(), client);
    }

    private void finishAnchorAfterUse(MinecraftClient client) {
        doubleAnchorUseBlocked = false;
        forgetActiveAnchor();
        resetAnchorState();
        ModuleContext context = context(client, ModuleKind.ANCHOR_MACRO.moduleName);
        if (context != null && context.toggle("Hold to loop", false)) {
            anchorFinishedUntilRelease = false;
            nextAnchorTick = tickCounter + 2;
        } else {
            anchorFinishedUntilRelease = true;
            nextAnchorTick = tickCounter + 4;
            clearOwnedSlot();
        }
    }

    private void trackManualAnchorPlacement(MinecraftClient client) {
        recentAnchorCandidates.entrySet().removeIf(candidate -> {
            if (isAnchor(client, candidate.getKey())) {
                ownedAnchors.add(candidate.getKey());
                return true;
            }
            return tickCounter - candidate.getValue() >= ANCHOR_CONFIRMATION_TICKS;
        });
        if (manualAnchorCandidate != null) {
            if (isAnchor(client, manualAnchorCandidate)) {
                ownedAnchors.add(manualAnchorCandidate);
                manualAnchorCandidate = null;
            } else if (tickCounter - manualAnchorCandidateTick >= ANCHOR_CONFIRMATION_TICKS) {
                manualAnchorCandidate = null;
            }
        }
        if (waitingForAnchor) return;
        if (!useHeldAtStart || !logicalMainHandStack(client.player).isOf(Items.RESPAWN_ANCHOR)) return;
        BlockHitResult hit = crosshairBlock(client);
        if (hit == null) return;
        BlockPos placementPos = placementPosition(client.player, Items.RESPAWN_ANCHOR, hit);
        if (placementBlockedByPlayer(client, placementPos)) return;
        manualAnchorCandidate = placementPos.toImmutable();
        manualAnchorCandidateTick = tickCounter;
    }

    private void pruneOwnedAnchors(MinecraftClient client) {
        ownedAnchors.removeIf(pos -> !isAnchor(client, pos)
            && !pos.equals(pendingAnchorPlace) && !pos.equals(manualAnchorCandidate)
            && !recentAnchorCandidates.containsKey(pos));
    }

    private void rememberVisibleTotems(ClientPlayerEntity player) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getStack(slot).isOf(Items.TOTEM_OF_UNDYING)) {
                rememberedTotemUntilTick[slot] = tickCounter + TOTEM_MEMORY_TICKS;
            }
        }
    }

    private int findTotemSlot(ClientPlayerEntity player) {
        int live = findHotbar(player, stack -> stack.isOf(Items.TOTEM_OF_UNDYING));
        if (live >= 0) return live;
        int selected = logicalSelectedSlot(player);
        if (rememberedTotemSlot(player, selected)) return selected;
        for (int slot = 0; slot < 9; slot++) if (rememberedTotemSlot(player, slot)) return slot;
        if (fallbackAirSlot(player, selected)) return selected;
        for (int slot = 0; slot < 9; slot++) if (fallbackAirSlot(player, slot)) return slot;
        return -1;
    }

    private boolean rememberedTotemSlot(ClientPlayerEntity player, int slot) {
        return validHotbarSlot(slot) && rememberedTotemUntilTick[slot] >= tickCounter
            && (player.getInventory().getStack(slot).isEmpty()
                || player.getInventory().getStack(slot).isOf(Items.TOTEM_OF_UNDYING));
    }

    private boolean fallbackAirSlot(ClientPlayerEntity player, int slot) {
        return validHotbarSlot(slot) && !hasHotbarTotem(player)
            && player.getInventory().getStack(slot).isEmpty();
    }

    private boolean hasHotbarTotem(ClientPlayerEntity player) {
        return findHotbar(player, stack -> stack.isOf(Items.TOTEM_OF_UNDYING)) >= 0;
    }

    private boolean anchorEntryItem(ClientPlayerEntity player) {
        ItemStack held = logicalMainHandStack(player);
        return held.isOf(Items.TOTEM_OF_UNDYING) || held.isOf(Items.GLOWSTONE)
            || held.isOf(Items.RESPAWN_ANCHOR) || held.isEmpty() && canUseSelectedAir(player);
    }

    private boolean canExplodeWithCurrentHand(ClientPlayerEntity player) {
        ItemStack held = logicalMainHandStack(player);
        return held.isOf(Items.TOTEM_OF_UNDYING) || held.isOf(Items.RESPAWN_ANCHOR)
            || held.isEmpty() && canUseSelectedAir(player);
    }

    private boolean canUseSelectedAir(ClientPlayerEntity player) {
        int selected = logicalSelectedSlot(player);
        return rememberedTotemSlot(player, selected) || fallbackAirSlot(player, selected);
    }

    private PlayerEntity findBestCrystalTarget(MinecraftClient client) {
        PlayerEntity crosshair = crosshairPlayer(client, CRYSTAL_TARGET_RANGE);
        if (crosshair != null) return crosshair;
        PlayerEntity best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (PlayerEntity candidate : client.world.getPlayers()) {
            if (!validCrystalTarget(client.player, candidate)) continue;
            double angularError = crystalTargetAngularError(client.player, candidate);
            if (!Double.isFinite(angularError) || angularError > 110.0D) continue;
            double score = angularError * 0.15D + Math.sqrt(client.player.squaredDistanceTo(candidate));
            if (score < bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private boolean validCrystalTarget(ClientPlayerEntity self, PlayerEntity target) {
        return target != null && target != self && target.isAlive()
            && !target.isSpectator() && !target.isCreative()
            && target.canHit() && target.isAttackable()
            && self.squaredDistanceTo(target) <= CRYSTAL_TARGET_RANGE * CRYSTAL_TARGET_RANGE;
    }

    private double crystalTargetAngularError(ClientPlayerEntity self, PlayerEntity target) {
        Vec3d delta = target.getEyePos().subtract(self.getEyePos());
        double horizontal = Math.hypot(delta.x, delta.z);
        float yaw = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));
        return Math.abs(MathHelper.wrapDegrees(yaw - self.getYaw()))
            + Math.abs(MathHelper.wrapDegrees(pitch - self.getPitch())) * 0.7D;
    }

    private PlayerEntity crosshairPlayer(MinecraftClient client, double range) {
        if (client.targetedEntity instanceof PlayerEntity direct
            && validCrystalTarget(client.player, direct)
            && client.player.squaredDistanceTo(direct) <= range * range) return direct;
        EntityHitResult hit = raycastEntity(client, range,
            entity -> entity instanceof PlayerEntity player && validCrystalTarget(client.player, player));
        return hit != null && hit.getEntity() instanceof PlayerEntity player ? player : null;
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

    private boolean trackedTargetDead(MinecraftClient client, UUID targetId) {
        for (PlayerEntity player : client.world.getPlayers()) {
            if (!targetId.equals(player.getUuid())) continue;
            return !player.isAlive() || player.isRemoved();
        }
        return true;
    }

    private boolean attackCrystal(MinecraftClient client, EndCrystalEntity crystal) {
        if (crystal == null || !crystal.isAlive()) return false;
        try {
            return withLogicalProjection(client, () -> {
                interactionAttemptSchedulerTick = ActionScheduler.getInstance().tickNumber();
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
                interactionAttemptSchedulerTick = ActionScheduler.getInstance().tickNumber();
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

    private boolean isAnchor(MinecraftClient client, BlockPos pos) {
        return pos != null && client.world.getBlockState(pos).isOf(Blocks.RESPAWN_ANCHOR);
    }

    private int anchorCharges(MinecraftClient client, BlockPos pos) {
        BlockState state = client.world.getBlockState(pos);
        return state.isOf(Blocks.RESPAWN_ANCHOR) ? state.get(RespawnAnchorBlock.CHARGES) : 0;
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

    /* Action-time equivalent of Grim AirLiquidPlace's invalid support test. */
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

    private static int sampledDelayTicks(int minimum, int maximum, double sample) {
        int lower = Math.max(1, Math.min(minimum, maximum));
        int upper = Math.max(lower, Math.max(minimum, maximum));
        double finite = Double.isFinite(sample) ? sample : 0.0D;
        double bounded = Math.max(0.0D, Math.min(Math.nextDown(1.0D), finite));
        return Math.max(1, (int) Math.round(lower + bounded * (upper - lower)));
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
        sequenceSilent = silentTransport || context != null && context.toggle("Silent swap", true);
        if (silentTransport) transportModuleKind = kind;
    }

    private boolean ownsSequenceContext(ClientPlayerEntity player) {
        if (branch == Branch.AUTO_CRYSTAL) return ownsTransportContext(MinecraftClient.getInstance());
        if (player == null || player != sequencePlayer) return false;
        if (sequenceSilent || silentTransport) {
            return player.getInventory().getSelectedSlot() == sequenceVisibleSlot
                && (ownedSlot < 0 || logicalSelectedSlot(player) == ownedSlot);
        }
        return ownedSlot < 0 || player.getInventory().getSelectedSlot() == ownedSlot;
    }

    private boolean ownsTransportContext(MinecraftClient client) {
        if (!silentTransport) return true;
        return client.player == transportPlayer
            && client.player.getInventory().getSelectedSlot() == transportVisibleSlot
            && SilentSlotManager.getInstance().isOwnedBy(this)
            && SilentSlotManager.getInstance().requestedSlot() == logicalSlot;
    }

    private boolean selectOwnedSlot(MinecraftClient client, int slot) {
        if (!validHotbarSlot(slot)) return false;
        if (logicalSelectedSlot(client.player) == slot) return true;
        if (sequenceSilent || silentTransport) {
            SilentSlotManager slots = SilentSlotManager.getInstance();
            if (!silentTransport) {
                silentTransport = true;
                transportPlayer = client.player;
                transportModuleKind = moduleKind(branch);
                transportVisibleSlot = client.player.getInventory().getSelectedSlot();
                logicalSlot = transportVisibleSlot;
                if (sequencePlayer == client.player) sequenceVisibleSlot = transportVisibleSlot;
            }
            transportModuleKind = moduleKind(branch);
            if (!slots.acquire(this, slot, PRIORITY, 2)) return false;
            logicalSlot = slot;
        } else if (!SilentSlotManager.getInstance().selectVisibleSlot(client, slot)) return false;
        ownedSlot = slot;
        actionUsedThisTick = true;
        return true;
    }

    private boolean renewSilentTransport(MinecraftClient client) {
        if (!silentTransport) return true;
        if (client.player != transportPlayer
            || client.player.getInventory().getSelectedSlot() != transportVisibleSlot
            || !validHotbarSlot(logicalSlot)) return false;
        SilentSlotManager slots = SilentSlotManager.getInstance();
        return slots.isOwnedBy(this) && slots.acquire(this, logicalSlot, PRIORITY, 2);
    }

    private boolean withLogicalProjection(MinecraftClient client,
                                          java.util.function.BooleanSupplier action) {
        if (!silentTransport) return action.getAsBoolean();
        if (!ownsTransportContext(client)) return false;
        SilentSlotManager slots = SilentSlotManager.getInstance();
        PlayerInventory inventory = client.player.getInventory();
        int projection = slots.beginLocalProjection(inventory);
        try { return action.getAsBoolean(); }
        finally { slots.endLocalProjection(inventory, projection); }
    }

    private int logicalSelectedSlot(ClientPlayerEntity player) {
        return silentTransport && player == transportPlayer && validHotbarSlot(logicalSlot)
            ? logicalSlot : player.getInventory().getSelectedSlot();
    }

    private ItemStack logicalMainHandStack(ClientPlayerEntity player) {
        int slot = logicalSelectedSlot(player);
        return validHotbarSlot(slot) ? player.getInventory().getStack(slot) : ItemStack.EMPTY;
    }

    private void clearOwnedSlot() { ownedSlot = -1; }
    private void clearSequenceOwnership() {
        sequencePlayer = null;
        ownedSlot = -1;
        sequenceVisibleSlot = -1;
        sequenceSilent = false;
    }

    private void endSilentTransport(MinecraftClient client) {
        if (silentTransport && SilentSlotManager.getInstance().isOwnedBy(this)
            && client != null && client.isOnThread()) {
            SilentSlotManager slots = SilentSlotManager.getInstance();
            if (interactionAttemptSchedulerTick == ActionScheduler.getInstance().tickNumber())
                slots.releaseAfterAction(this);
            slots.release(this);
        }
        clearSilentTransportState();
    }

    private void clearSilentTransportState() {
        silentTransport = false;
        transportPlayer = null;
        transportModuleKind = null;
        transportVisibleSlot = -1;
        logicalSlot = -1;
    }

    private void claimUse() { wantsUse = true; }
    private void releaseUse(MinecraftClient client) {
        if (useClaimed || UseInputLease.getInstance().owns(this)) {
            UseInputLease.getInstance().release(this, client);
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
        boolean ownsWait = branch == Branch.ANCHOR && !anchorFinishedUntilRelease
            || branch == Branch.AUTO_HIT
                && (forcedObsidianPlacement || forcedCrystalPlacement || pendingObsidian != null);
        if (ownsWait) {
            plan(new PlannedAction(ActionKind.RESERVE, branch,
                null, null, -1, 0.0D, null));
        }
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
        resetAutoCrystalState();
        resetAutoHitState();
        resetAnchorState();
        branch = Branch.NONE;
        clearSequenceOwnership();
        nextAnchorTick = 0;
        anchorFinishedUntilRelease = false;
        ownedAnchors.clear();
        for (int slot = 0; slot < rememberedTotemUntilTick.length; slot++) rememberedTotemUntilTick[slot] = 0;
        manualAnchorCandidate = null;
        manualAnchorCandidateTick = 0;
        recentAnchorCandidates.clear();
        observedWorld = null;
        observedPlayer = null;
        useHeldAtStart = false;
        doubleAnchorUseBlocked = false;
        actionUsedThisTick = false;
        silentTakeoverUntilUseRelease = false;
        clearPlan();
        endSilentTransport(client);
        releaseUse(client);
    }

    private void resetActiveSequence() {
        if (branch == Branch.ANCHOR) resetAnchorState();
        if (branch == Branch.AUTO_HIT) resetAutoHitState();
        if (branch == Branch.AUTO_CRYSTAL) resetAutoCrystalState();
        branch = Branch.NONE;
        clearSequenceOwnership();
    }

    private void resetAutoCrystalState() {
        autoCrystalStep = CrystalStep.PLACE;
        nextAutoCrystalTick = 0;
        trackedCrystalTarget = null;
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

    private void resetAnchorState() {
        anchorCycleStarted = false;
        doubleAnchorCycle = false;
        doubleAnchorStep = DoubleAnchorStep.NONE;
        doubleAnchorReplaceTick = 0;
        doubleAnchorAwaitingReplacement = false;
        anchorSupportHit = null;
        anchorSupportState = null;
        doubleAnchorHit = null;
        activeAnchorPos = null;
        anchorGlowstoneUses = 0;
        anchorGlowstoneGoal = 1;
        waitingForAnchor = false;
        pendingAnchorStartTick = 0;
        pendingAnchorPlace = null;
        glowstoneUseTick = Long.MIN_VALUE;
        chargeBeforeUse = 0;
    }

    private void forgetActiveAnchor() { if (activeAnchorPos != null) ownedAnchors.remove(activeAnchorPos); }

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
        return silentTransport && (client.player != transportPlayer
            || client.player.getInventory().getSelectedSlot() != transportVisibleSlot
            || gameplayTakeoverActive(client));
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

    private static ModuleKind moduleKind(Branch branch) {
        return switch (branch) {
            case ANCHOR -> ModuleKind.ANCHOR_MACRO;
            case AUTO_HIT -> ModuleKind.AUTO_HIT_CRYSTAL;
            case AUTO_CRYSTAL -> ModuleKind.AUTO_CRYSTAL;
            case NONE -> throw new IllegalStateException("No module owns the idle branch");
        };
    }

    private static int settingInt(ModuleContext context, String label, int fallback) {
        return context == null ? fallback : (int) Math.round(context.slider(label, fallback));
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
