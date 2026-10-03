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
final class DoubleAnchorController implements HeldUseBroker.Policy {
    private static final HeldUseBroker BROKER = HeldUseBroker.INSTANCE;

    enum ModuleKind {
        AUTO_CRYSTAL("Auto Crystal"),
        AUTO_HIT_CRYSTAL("Auto Hit Crystal"),
        ANCHOR_MACRO("Anchor Macro");

        final String moduleName;
        ModuleKind(String moduleName) { this.moduleName = moduleName; }
    }

    private enum Branch { NONE, ANCHOR }

    private enum DoubleAnchorStep { NONE, CHARGE, EXPLODE }
    private enum ActionKind { NONE, RESERVE, ANCHOR_PLACE, ANCHOR_GLOWSTONE, DOUBLE_ANCHOR_EXPLODE_REPLACE, DOUBLE_ANCHOR_GLOWSTONE, DOUBLE_ANCHOR_FINISH }

    private static final int PRIORITY = 190;
    private static final double BLOCK_INTERACTION_RANGE = 4.45D;
    private static final int ANCHOR_CONFIRMATION_TICKS = 4;
    private static final int TOTEM_MEMORY_TICKS = 80;

    private final Random random = new Random();
    private final Set<BlockPos> ownedAnchors = new HashSet<>();
    private final Map<BlockPos, Long> recentAnchorCandidates = new HashMap<>();
    private final long[] rememberedTotemUntilTick = new long[9];

    private Branch branch = Branch.NONE;
    private long tickCounter;
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
    private boolean doubleAnchorUseBlocked;
    private DoubleAnchorStep doubleAnchorStep = DoubleAnchorStep.NONE;
    private BlockHitResult doubleAnchorHit;

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

    private boolean finishedDouble;

    @Override public boolean wantsUse() { return wantsUse; }
    @Override public void stop(MinecraftClient client) { resetEverything(client); wantsUse = false; useClaimed = false; }
    @Override public boolean blockOnRetire() { return doubleAnchorUseBlocked; }

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
        if (!UseInputLease.physicalDown(client)) { doubleAnchorUseBlocked = false; finishedDouble = false; }
        if (finishedDouble && branch == Branch.NONE && !BROKER.consumedKey(DoubleAnchorMacroFeature.holdKeyCode())) finishedDouble = false;
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
            if (branch == Branch.ANCHOR) {
                if (tickAnchor(client)) return;
                resetActiveSequence();
                continue;
            }
            if (allowNewSequence && canStartAnchor(client)) {
                branch = Branch.ANCHOR;
                beginSequenceOwnership(client.player, ModuleKind.ANCHOR_MACRO);
                continue;
            }
            return;
        }
        resetActiveSequence();
    }

    private boolean tickAnchor(MinecraftClient client) {
        ModuleContext context = context(client, ModuleKind.ANCHOR_MACRO.moduleName);
        if (context == null) {
            resetAnchorState();
            anchorFinishedUntilRelease = false;
            return false;
        }
        if (finishedDouble && context.toggle("Hold to loop", false)) return false;
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
            doubleAnchorCycle = DoubleAnchorMacroFeature.held(client) && BROKER.consumeKey(DoubleAnchorMacroFeature.holdKeyCode(), true);
            if (doubleAnchorCycle) {
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
            plan(new PlannedAction(ActionKind.DOUBLE_ANCHOR_EXPLODE_REPLACE, Branch.ANCHOR,
                hit, null, anchorSlot, context.slider("2 tick swap chance", 18.0D), anchorPos.toImmutable()));
            return true;
        }
        return false;
    }

    private boolean canStartAnchor(MinecraftClient client) {
        ModuleContext context = context(client, ModuleKind.ANCHOR_MACRO.moduleName);
        return context != null && !finishedDouble && !BROKER.consumedKey(DoubleAnchorMacroFeature.holdKeyCode())
            && DoubleAnchorMacroFeature.held(client) && shouldRunAnchor(client, context, crosshairBlock(client));
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
        if (!ownsTransportContext(client)) { interruptDoubleAnchor(client); return; }
        if (doubleAnchorCycle && (!doubleAnchorContextAllowed(client) || !ownsSequenceContext(client.player))) {
            interruptDoubleAnchor(client); return;
        }
        switch (plan.kind) {
            case ANCHOR_PLACE -> executeAnchorPlace(client, plan);
            case ANCHOR_GLOWSTONE -> executeAnchorGlowstone(client, plan);
            case DOUBLE_ANCHOR_EXPLODE_REPLACE -> executeDoubleAnchorExplodeReplace(client, plan);
            case DOUBLE_ANCHOR_GLOWSTONE, DOUBLE_ANCHOR_FINISH -> executeDoubleAnchorFollowup(client, plan);
            default -> { }
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

    private void initializeAnchorCharge(MinecraftClient client, BlockPos anchorPos,
                                        ModuleContext context) {
        activeAnchorPos = anchorPos.toImmutable();
        anchorGlowstoneUses = 0;
        glowstoneUseTick = Long.MIN_VALUE;
        anchorGlowstoneGoal = 1;
    }

    private boolean tickDoubleAnchorFollowup(MinecraftClient client, ModuleContext context) {
        claimUse();
        if (!doubleAnchorContextAllowed(client) || doubleAnchorHit == null
            || !validDoubleAnchorHit(client, doubleAnchorHit)) {
            interruptDoubleAnchor(client);
            return true;
        }
        if (tickCounter < nextAnchorTick) return true;
        Item required = switch (doubleAnchorStep) {
            case CHARGE -> Items.GLOWSTONE;
            case EXPLODE -> Items.TOTEM_OF_UNDYING;
            case NONE -> throw new IllegalStateException("No double-anchor continuation");
        };
        int slot = preferredHotbarSlot(client.player, required);
        if (slot < 0) {
            interruptDoubleAnchor(client);
            return true;
        }
        ActionKind kind = switch (doubleAnchorStep) {
            case CHARGE -> ActionKind.DOUBLE_ANCHOR_GLOWSTONE;
            case EXPLODE -> ActionKind.DOUBLE_ANCHOR_FINISH;
            case NONE -> throw new IllegalStateException("No double-anchor continuation");
        };
        plan(new PlannedAction(kind, Branch.ANCHOR,
            doubleAnchorHit, null, slot, context.slider("2 tick swap chance", 18.0D),
            doubleAnchorHit.getBlockPos()));
        return true;
    }

    private void executeDoubleAnchorExplodeReplace(MinecraftClient client, PlannedAction plan) {
        if (!isAnchor(client, plan.placementPos) || anchorCharges(client, plan.placementPos) < 1
            || !validDoubleAnchorHit(client, plan.hit)
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
        // Explode and replace in this same action, holding the anchor slot for
        // both uses. Reuse the original hit: after the server removes the anchor,
        // its now-replaceable position accepts the new block without a support.
        // The client still predicts the old charged anchor until the server replies;
        // interactBlock sends a sequenced use even when that prediction rejects it.
        if (performVanillaUseResult(client, plan.hit) == null) {
            interruptDoubleAnchor(client);
            return;
        }
        doubleAnchorHit = plan.hit;
        doubleAnchorStep = DoubleAnchorStep.CHARGE;
        nextAnchorTick = tickCounter + 1;
        waitingForAnchor = false;
        pendingAnchorPlace = null;
        glowstoneUseTick = Long.MIN_VALUE;
        ownedAnchors.remove(plan.placementPos);
        recentAnchorCandidates.remove(plan.placementPos);
    }

    private void executeDoubleAnchorFollowup(MinecraftClient client, PlannedAction plan) {
        Item required = plan.kind == ActionKind.DOUBLE_ANCHOR_GLOWSTONE
            ? Items.GLOWSTONE : Items.TOTEM_OF_UNDYING;
        if (!validDoubleAnchorHit(client, plan.hit)
            || !client.player.getInventory().getStack(plan.slot).isOf(required)) {
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
        // These ordered uses follow the replacement already sent to the server.
        // Do not wait for local block/charge acknowledgements or retry a rejected
        // prediction: that would stall the sequence or charge the anchor twice.
        if (performVanillaUseResult(client, plan.hit) == null) {
            interruptDoubleAnchor(client);
            return;
        }
        if (plan.kind == ActionKind.DOUBLE_ANCHOR_GLOWSTONE) {
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
        useClaimed = UseInputLease.getInstance().acquire(BROKER, PRIORITY,
            ActionScheduler.getInstance().tickNumber(), client);
    }

    private void finishAnchorAfterUse(MinecraftClient client) {
        doubleAnchorUseBlocked = false;
        finishedDouble = true;
        forgetActiveAnchor();
        resetAnchorState();
        ModuleContext context = context(client, ModuleKind.ANCHOR_MACRO.moduleName);
        if (context != null && context.toggle("Hold to loop", false)) {
            anchorFinishedUntilRelease = false;
            nextAnchorTick = tickCounter + 2;
            BROKER.continueAfter(nextAnchorTick);
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

    private boolean canUseSelectedAir(ClientPlayerEntity player) {
        int selected = logicalSelectedSlot(player);
        return rememberedTotemSlot(player, selected) || fallbackAirSlot(player, selected);
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

    private static BlockHitResult crosshairBlock(MinecraftClient client) {
        return client.crosshairTarget instanceof BlockHitResult hit
            && hit.getType() == HitResult.Type.BLOCK ? hit : null;
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
        if (plannedAction.kind == ActionKind.NONE && wantsUse && !anchorFinishedUntilRelease)
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
        resetAnchorState();
        finishedDouble = false;
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
        resetAnchorState();
        branch = Branch.NONE;
        clearSequenceOwnership();
    }

    private void resetAnchorState() {
        anchorCycleStarted = false;
        doubleAnchorCycle = false;
        doubleAnchorStep = DoubleAnchorStep.NONE;
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
