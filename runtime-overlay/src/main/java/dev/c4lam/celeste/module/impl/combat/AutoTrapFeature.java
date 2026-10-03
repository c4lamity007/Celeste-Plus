package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.action.VanillaInputGuard;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the smallest target-relative cobweb cage that safely contains the
 * target's horizontal hitbox: 1x1 when centered, otherwise 2x2. Planning,
 * rotation arbitration, and the one selected vanilla interaction happen during
 * START_CLIENT_TICK before vanilla emits that tick's flying packet.
 */
public final class AutoTrapFeature extends AutoTrapCombatFeature {
    private static final int PRIORITY = 66;
    private static final int ONE_BY_ONE_BASE_STEPS = 10;
    private static final int ONE_BY_ONE_ROOF_STEPS = 1;
    private static final int TWO_BY_TWO_BASE_STEPS = 24;
    private static final int TWO_BY_TWO_ROOF_STEPS = 4;
    private static final double FOOTPRINT_EPSILON = 1.0E-4D;
    private static final double ONE_BY_ONE_CLEARANCE = 0.05D;
    private static final Material OBSIDIAN = new Material(Items.OBSIDIAN, Blocks.OBSIDIAN);
    private static final Material COBWEB = new Material(Items.COBWEB, Blocks.COBWEB);
    private static final Direction[] SUPPORT_FACES = {
        Direction.UP, Direction.NORTH, Direction.EAST,
        Direction.SOUTH, Direction.WEST, Direction.DOWN
    };
    private static final double FACE_INSET = 0.375D;
    private static final double[][] FACE_POINT_OFFSETS = {
        {0.0D, 0.0D},
        {-FACE_INSET, 0.0D}, {FACE_INSET, 0.0D},
        {0.0D, -FACE_INSET}, {0.0D, FACE_INSET},
        {-FACE_INSET, -FACE_INSET}, {-FACE_INSET, FACE_INSET},
        {FACE_INSET, -FACE_INSET}, {FACE_INSET, FACE_INSET}
    };

    private PlacementPlan plan;
    private PostPlacementHold postPlacementHold;
    private ClientWorld sequenceWorld;
    private ClientPlayerEntity sequencePlayer;
    private PlayerEntity sequenceTarget;
    private SequenceDefinition sequenceDefinition;
    private int stepIndex;
    private boolean roofRequested;
    private boolean sequenceSilent;
    private boolean slotLeaseActive;
    private int silentVisibleSlot = -1;
    private int visibleOriginalSlot = -1;
    private int visibleActiveSlot = -1;
    private long lastPlaceTick = Long.MIN_VALUE / 2L;

    @Override
    public String name() {
        return "Auto Trap";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        plan = null;
        MinecraftClient client = context.client();

        if (postPlacementHold != null) {
            if (client.player != postPlacementHold.player()
                || client.world != postPlacementHold.world()) {
                postPlacementHold = null;
                cancelSequence(client);
            } else {
                observeRoofRequest(client, baseStepsComplete(client));
                requestRotation(postPlacementHold.lookPoint(), PRIORITY);
            }
            return;
        }

        if (!basicStateValid(client)) {
            cancelSequence(client);
            return;
        }

        double range = context.slider("Range", 4.5D);
        if (sequenceActive() && !validateSequence(client, range)) {
            cancelSequence(client);
        }
        if (!sequenceActive() && !beginSequence(client, context, range)) {
            releaseIntent();
            return;
        }

        int visibleSlot = client.player.getInventory().getSelectedSlot();
        boolean selectionChanged = slotLeaseActive
            && ((sequenceSilent && silentVisibleSlot >= 0 && visibleSlot != silentVisibleSlot)
                || (!sequenceSilent && visibleSlot != visibleActiveSlot));
        if (selectionChanged || VanillaInputGuard.hasInteractionConflict(client)) {
            // Release before vanilla handles a physical attack/use/hotbar edge
            // later in this tick; never let it use Auto Trap's hidden stack.
            releaseSlotLease(client, false);
            releaseIntent();
            return;
        }

        int baseCount = sequenceDefinition.baseStepCount();
        List<TrapStep> steps = sequenceDefinition.steps();
        int baseMissing = firstIncompleteStep(client, baseCount);
        boolean baseComplete = baseMissing >= baseCount;
        observeRoofRequest(client, baseComplete);
        int allowedSteps = roofRequested ? steps.size() : baseCount;
        // Reconcile from the start every tick. Server resyncs or a target
        // breaking an earlier cell therefore rewind to the first new hole.
        stepIndex = firstIncompleteStep(client, allowedSteps);

        if (stepIndex >= allowedSteps) {
            releaseSlotLease(client, false);
            releaseIntent();
            if (roofRequested && stepIndex >= steps.size()) {
                clearSequenceState();
            }
            return;
        }

        if (ActionScheduler.getInstance().tickNumber() <= lastPlaceTick) {
            return;
        }

        TrapStep step = steps.get(stepIndex);
        int slot = findHotbarSlot(client, step.material().item());
        if (slot < 0) {
            releaseSlotLease(client, false);
            releaseIntent();
            return;
        }

        PlacementPlan candidate = createPlan(client, step, slot, range, stepIndex);
        if (candidate == null) {
            cancelSequence(client);
            return;
        }
        if (!prepareSlot(client, slot)) {
            releaseIntent();
            return;
        }

        plan = candidate;
        requestRotation(candidate.hit().getPos(), PRIORITY);
    }

    @Override
    public void onActionTick(ModuleContext context) {
        if (postPlacementHold != null) {
            ActionScheduler.getInstance().submit(this, PRIORITY, client -> {
            });
            return;
        }

        PlacementPlan selected = plan;
        if (selected == null || !sequenceActive()) {
            return;
        }

        ActionScheduler.getInstance().submit(this, PRIORITY, client -> {
            ModuleContext current = new ModuleContext(client, context.module());
            if (!validateExecution(client, current, selected)) {
                cancelSequence(client);
                return;
            }
            // interactBlock emits the sequenced PlayerInteractBlock packet.
            // Running here preserves vanilla's action -> flying packet order.
            executePlacement(client, selected);
        });
    }

    @Override
    public void onEndTick(ModuleContext context) {
        if (postPlacementHold != null) {
            if (ActionScheduler.getInstance().executedFor(this)
                && RotationManager.getInstance().isOwnedBy(this)) {
                postPlacementHold = null;
                releaseIntent();
            }
        }
    }

    private void executePlacement(MinecraftClient client, PlacementPlan selected) {
        PlayerInventory inventory = client.player.getInventory();
        SilentSlotManager slots = SilentSlotManager.getInstance();
        int projectionToken = sequenceSilent
            ? slots.beginLocalProjection(inventory)
            : -1;
        ActionResult result = null;
        RuntimeException interactionFailure = null;
        try {
            result = client.interactionManager.interactBlock(
                client.player, Hand.MAIN_HAND, selected.hit());
        } catch (RuntimeException exception) {
            interactionFailure = exception;
        } finally {
            if (sequenceSilent) {
                slots.endLocalProjection(inventory, projectionToken);
            }
        }

        if (interactionFailure != null) {
            finishAfterInteraction(client, selected.hit().getPos(), true);
            throw interactionFailure;
        }

        if (result instanceof ActionResult.Success success) {
            // Success can also mean the clicked support consumed the use. Only
            // count a tick after vanilla prediction produced the requested cell.
            if (stepState(client, selected.step()) != StepState.COMPLETE) {
                finishAfterInteraction(client, selected.hit().getPos(), true);
                return;
            }
            if (success.swingSource() == ActionResult.SwingSource.CLIENT) {
                client.player.swingHand(Hand.MAIN_HAND);
            }
            lastPlaceTick = ActionScheduler.getInstance().tickNumber();
            stepIndex = selected.stepIndex() + 1;

            boolean baseFinished = baseStepsComplete(client) && !roofRequested;
            boolean trapFinished = allStepsComplete(client, sequenceDefinition.steps());
            if (!baseFinished && !trapFinished) {
                return;
            }
            finishAfterInteraction(client, selected.hit().getPos(), trapFinished);
            return;
        }

        // A vanilla use was attempted but rejected. Restore on the next tick
        // and rebuild cleanly instead of blindly advancing the pattern.
        finishAfterInteraction(client, selected.hit().getPos(), true);
    }

    @Override
    protected void clearTarget() {
        plan = null;
    }

    @Override
    protected void resetFeatureState() {
        postPlacementHold = null;
        cancelSequence(MinecraftClient.getInstance());
        lastPlaceTick = Long.MIN_VALUE / 2L;
    }

    private boolean beginSequence(MinecraftClient client, ModuleContext context, double range) {
        if (client.player == null || client.world == null
            || findHotbarSlot(client, Items.OBSIDIAN) < 0
            || findHotbarSlot(client, Items.COBWEB) < 0) {
            return false;
        }

        TargetCandidate candidate = selectTarget(client, range);
        if (candidate == null) {
            return false;
        }

        sequenceWorld = client.world;
        sequencePlayer = client.player;
        sequenceTarget = candidate.target();
        sequenceDefinition = candidate.definition();
        stepIndex = 0;
        roofRequested = false;
        sequenceSilent = context.toggle("Silent swap", true);
        slotLeaseActive = false;
        silentVisibleSlot = -1;
        visibleOriginalSlot = -1;
        visibleActiveSlot = -1;
        return true;
    }

    private TargetCandidate selectTarget(MinecraftClient client, double range) {
        if (client.player == null || client.world == null) {
            return null;
        }
        List<PlayerEntity> targets = client.world.getPlayers().stream()
            .map(PlayerEntity.class::cast)
            .filter(target -> AutoTrapTargeting.validPlayerTarget(
                client, target, friends, range, false))
            .sorted(Comparator.comparingDouble(client.player::squaredDistanceTo))
            .toList();
        for (PlayerEntity target : targets) {
            SequenceDefinition definition = definitionForTarget(client, target, range);
            if (definition != null) {
                return new TargetCandidate(target, definition);
            }
        }
        return null;
    }

    private boolean validateSequence(MinecraftClient client, double range) {
        int allowedSteps = sequenceDefinition == null ? 0
            : (roofRequested ? sequenceDefinition.steps().size()
                : sequenceDefinition.baseStepCount());
        return sequenceActive()
            && client.player == sequencePlayer
            && client.world == sequenceWorld
            && sequenceDefinition.bounds().contains(sequenceTarget)
            && !localIntersectsAnyWebCell(client, sequenceDefinition.steps())
            && !hasBlockedStep(client, sequenceDefinition.steps(), allowedSteps)
            && AutoTrapTargeting.validPlayerTarget(
                client, sequenceTarget, friends, range, false);
    }

    private SequenceDefinition definitionForTarget(MinecraftClient client,
                                                   PlayerEntity target,
                                                   double range) {
        OccupiedSpan span = occupiedSpan(target);
        if (span == null) {
            return null;
        }

        if (span.widthX() == 1 && span.widthZ() == 1) {
            SequenceDefinition compact = buildOneByOneDefinition(client, target, span);
            if (compact.bounds().containsWithClearance(target)) {
                if (allStepsComplete(client, compact.steps())) {
                    return null;
                }
                if (baseObsidianCollisionCount(client, compact) == 0
                    && layoutCanStart(client, compact, range)) {
                    return compact;
                }
            }
        }

        for (SequenceDefinition expanded : buildTwoByTwoDefinitions(client, target, span)) {
            if (allStepsComplete(client, expanded.steps())) {
                return null;
            }
            if (layoutCanStart(client, expanded, range)) {
                return expanded;
            }
        }
        return null;
    }

    private boolean layoutCanStart(MinecraftClient client, SequenceDefinition definition,
                                   double range) {
        List<TrapStep> steps = definition.steps();
        int baseCount = definition.baseStepCount();
        if (localIntersectsAnyWebCell(client, steps)
            || hasBlockedStep(client, steps, baseCount)) {
            return false;
        }

        int missingBase = firstIncompleteStep(client, steps, baseCount);
        if (missingBase < baseCount) {
            return findHit(client, steps.get(missingBase), range) != null;
        }
        if (!isJumping(client)) {
            // A finished base remains eligible so it can wait for the user's
            // jump without repeatedly dropping and reacquiring the target.
            return true;
        }
        int missingRoof = firstIncompleteStep(client, steps, steps.size());
        return missingRoof >= steps.size()
            || findHit(client, steps.get(missingRoof), range) != null;
    }

    private boolean validateExecution(MinecraftClient client, ModuleContext context,
                                      PlacementPlan selected) {
        if (!basicStateValid(client)
            || !validateSequence(client, context.slider("Range", 4.5D))
            || client.world != selected.world()
            || selected.targetEntity() != sequenceTarget
            || selected.definition() != sequenceDefinition
            || selected.stepIndex() != stepIndex
            || selected.stepIndex() >= (roofRequested
                ? sequenceDefinition.steps().size()
                : sequenceDefinition.baseStepCount())
            || ActionScheduler.getInstance().tickNumber() <= lastPlaceTick
            || stepState(client, selected.step()) != StepState.MISSING
            || !validSlot(client, selected)
            || !validBlockInteraction(client, selected.hit(),
                context.slider("Range", 4.5D))
            || !rotationReady(selected.hit().getPos(), 7.5D)) {
            return false;
        }
        return validTarget(client, selected.step())
            && validSupport(client, selected.step().position(), selected.hit())
            && visibleSupport(client, selected.hit());
    }

    private PlacementPlan createPlan(MinecraftClient client, TrapStep step, int slot,
                                     double range, int selectedStepIndex) {
        BlockHitResult hit = findHit(client, step, range);
        return hit == null ? null : new PlacementPlan(
            client.world, sequenceTarget, sequenceDefinition,
            step, slot, hit, selectedStepIndex);
    }

    private static BlockHitResult findHit(MinecraftClient client, TrapStep step,
                                          double range) {
        if (!validTarget(client, step) || client.player == null) {
            return null;
        }
        double reach = client.player == null
            ? 0.0D
            : Math.min(range, client.player.getBlockInteractionRange());
        for (Direction face : SUPPORT_FACES) {
            BlockPos support = step.position().offset(face.getOpposite());
            for (double[] offset : FACE_POINT_OFFSETS) {
                Vec3d point = facePoint(support, face, offset[0], offset[1]);
                BlockHitResult hit = new BlockHitResult(point, face, support, false);
                if (client.player.getEyePos().squaredDistanceTo(point) <= reach * reach
                    && client.player.canInteractWithBlockAt(support, 0.0D)
                    && validSupport(client, step.position(), hit)
                    && visibleSupport(client, hit)) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static Vec3d facePoint(BlockPos support, Direction face,
                                   double firstOffset, double secondOffset) {
        Vec3d center = Vec3d.ofCenter(support).add(
            face.getOffsetX() * 0.5D,
            face.getOffsetY() * 0.5D,
            face.getOffsetZ() * 0.5D
        );
        return switch (face.getAxis()) {
            case X -> center.add(0.0D, firstOffset, secondOffset);
            case Y -> center.add(firstOffset, 0.0D, secondOffset);
            case Z -> center.add(firstOffset, secondOffset, 0.0D);
        };
    }

    private boolean prepareSlot(MinecraftClient client, int slot) {
        if (client.player == null) {
            return false;
        }
        SilentSlotManager slots = SilentSlotManager.getInstance();
        if (sequenceSilent) {
            int selected = client.player.getInventory().getSelectedSlot();
            if (slotLeaseActive && selected != silentVisibleSlot) {
                return false;
            }
            if (!slotLeaseActive) {
                silentVisibleSlot = selected;
            }
            slotLeaseActive = slots.acquire(this, slot, PRIORITY, 2);
            if (!slotLeaseActive) {
                silentVisibleSlot = -1;
            }
            return slotLeaseActive;
        }

        int selected = client.player.getInventory().getSelectedSlot();
        if (!slotLeaseActive) {
            visibleOriginalSlot = selected;
            visibleActiveSlot = selected;
            slotLeaseActive = true;
        } else if (selected != visibleActiveSlot) {
            return false;
        }
        if (selected != slot && !slots.selectVisibleSlot(client, slot)) {
            slotLeaseActive = false;
            visibleOriginalSlot = -1;
            visibleActiveSlot = -1;
            return false;
        }
        visibleActiveSlot = slot;
        return true;
    }

    private boolean validSlot(MinecraftClient client, PlacementPlan selected) {
        if (!slotLeaseActive || client.player == null
            || !client.player.getInventory().getStack(selected.slot())
                .isOf(selected.step().material().item())) {
            return false;
        }
        if (sequenceSilent) {
            SilentSlotManager slots = SilentSlotManager.getInstance();
            return slots.isOwnedBy(this) && slots.requestedSlot() == selected.slot();
        }
        return visibleActiveSlot == selected.slot()
            && client.player.getInventory().getSelectedSlot() == selected.slot();
    }

    private void finishAfterInteraction(MinecraftClient client, Vec3d lookPoint,
                                        boolean clearSequence) {
        releaseSlotLease(client, true);
        if (clearSequence) {
            clearSequenceState();
        }
        postPlacementHold = new PostPlacementHold(client.world, client.player, lookPoint);
    }

    private void releaseSlotLease(MinecraftClient client, boolean afterAction) {
        if (!slotLeaseActive) {
            return;
        }
        SilentSlotManager slots = SilentSlotManager.getInstance();
        if (sequenceSilent) {
            if (afterAction) {
                slots.releaseAfterAction(this);
            } else {
                slots.release(this);
            }
        } else if (client != null && client.player == sequencePlayer
            && client.player.getInventory().getSelectedSlot() == visibleActiveSlot
            && visibleOriginalSlot >= 0 && visibleOriginalSlot != visibleActiveSlot) {
            if (afterAction) {
                slots.restoreVisibleNextTick(
                    sequencePlayer, visibleOriginalSlot, visibleActiveSlot);
            } else {
                slots.selectVisibleSlot(client, visibleOriginalSlot);
            }
        }
        slotLeaseActive = false;
        silentVisibleSlot = -1;
        visibleOriginalSlot = -1;
        visibleActiveSlot = -1;
    }

    private void cancelSequence(MinecraftClient client) {
        postPlacementHold = null;
        releaseSlotLease(client, false);
        clearSequenceState();
        releaseIntent();
    }

    private void clearSequenceState() {
        plan = null;
        sequenceWorld = null;
        sequencePlayer = null;
        sequenceTarget = null;
        sequenceDefinition = null;
        stepIndex = 0;
        roofRequested = false;
        sequenceSilent = false;
        slotLeaseActive = false;
        silentVisibleSlot = -1;
        visibleOriginalSlot = -1;
        visibleActiveSlot = -1;
    }

    private boolean sequenceActive() {
        return sequenceWorld != null && sequencePlayer != null && sequenceTarget != null
            && sequenceDefinition != null && sequenceDefinition.valid();
    }

    private void observeRoofRequest(MinecraftClient client, boolean baseComplete) {
        if (!sequenceActive() || client.player == null || roofRequested || !baseComplete) {
            return;
        }
        roofRequested = isJumping(client);
    }

    private static boolean isJumping(MinecraftClient client) {
        return client.player != null && !client.player.isOnGround()
            && (client.player.getVelocity().y > 0.0D
                || client.options.jumpKey.isPressed());
    }

    private static OccupiedSpan occupiedSpan(PlayerEntity target) {
        Box bounds = target.getBoundingBox();
        int minX = (int) Math.floor(bounds.minX + FOOTPRINT_EPSILON);
        int maxX = (int) Math.floor(bounds.maxX - FOOTPRINT_EPSILON);
        int minZ = (int) Math.floor(bounds.minZ + FOOTPRINT_EPSILON);
        int maxZ = (int) Math.floor(bounds.maxZ - FOOTPRINT_EPSILON);
        int widthX = maxX - minX + 1;
        int widthZ = maxZ - minZ + 1;
        if (widthX < 1 || widthX > 2 || widthZ < 1 || widthZ > 2) {
            return null;
        }
        return new OccupiedSpan(minX, maxX, minZ, maxZ, target.getBlockPos().getY());
    }

    private static SequenceDefinition buildOneByOneDefinition(MinecraftClient client,
                                                              PlayerEntity target,
                                                              OccupiedSpan span) {
        BlockPos center = new BlockPos(span.minX(), span.baseY(), span.minZ());
        Direction far = farDirection(client.player, target);
        Direction near = far.getOpposite();
        Direction left = counterClockwise(far);
        Direction right = clockwise(far);
        Direction inside = Vec3d.ofCenter(center.offset(left)).squaredDistanceTo(
            client.player.getEntityPos())
            < Vec3d.ofCenter(center.offset(right)).squaredDistanceTo(
                client.player.getEntityPos()) ? left : right;
        Direction outside = inside.getOpposite();

        List<TrapStep> order = new ArrayList<>(
            ONE_BY_ONE_BASE_STEPS + ONE_BY_ONE_ROOF_STEPS);
        addFarthestFirst(order, client.player,
            new TrapStep(center.offset(far), OBSIDIAN),
            new TrapStep(center.offset(outside), OBSIDIAN));
        order.add(new TrapStep(center, COBWEB));
        addFarthestFirst(order, client.player,
            new TrapStep(center.offset(far).up(), OBSIDIAN),
            new TrapStep(center.offset(outside).up(), OBSIDIAN));
        order.add(new TrapStep(center.up(), COBWEB));
        addFarthestFirst(order, client.player,
            new TrapStep(center.offset(near), OBSIDIAN),
            new TrapStep(center.offset(inside), OBSIDIAN));
        addFarthestFirst(order, client.player,
            new TrapStep(center.offset(near).up(), OBSIDIAN),
            new TrapStep(center.offset(inside).up(), OBSIDIAN));
        order.add(new TrapStep(center.up(2), OBSIDIAN));
        return new SequenceDefinition(
            List.copyOf(order), ONE_BY_ONE_BASE_STEPS,
            new InteriorBounds(span.minX(), span.minX() + 1,
                span.minZ(), span.minZ() + 1, span.baseY(), TrapKind.ONE_BY_ONE));
    }

    private static List<SequenceDefinition> buildTwoByTwoDefinitions(
        MinecraftClient client, PlayerEntity target, OccupiedSpan span
    ) {
        int[] xMins = containingMinimums(
            span.minX(), span.widthX(), client.player.getX(), target.getX());
        int[] zMins = containingMinimums(
            span.minZ(), span.widthZ(), client.player.getZ(), target.getZ());
        List<SequenceDefinition> definitions = new ArrayList<>(xMins.length * zMins.length);
        for (int minX : xMins) {
            for (int minZ : zMins) {
                InteriorBounds bounds = new InteriorBounds(
                    minX, minX + 2, minZ, minZ + 2, span.baseY(), TrapKind.TWO_BY_TWO);
                if (!bounds.contains(target)) {
                    continue;
                }

                int anchorX = fartherOccupiedCell(
                    span.minX(), span.maxX(), client.player.getX());
                int anchorZ = fartherOccupiedCell(
                    span.minZ(), span.maxZ(), client.player.getZ());
                Direction intoX = anchorX == minX ? Direction.EAST : Direction.WEST;
                Direction intoZ = anchorZ == minZ ? Direction.SOUTH : Direction.NORTH;
                boolean xPrimary = Math.abs(client.player.getX() - target.getX())
                    >= Math.abs(client.player.getZ() - target.getZ());
                Direction near = xPrimary ? intoX : intoZ;
                Direction inside = xPrimary ? intoZ : intoX;
                BlockPos farA = new BlockPos(anchorX, span.baseY(), anchorZ);
                definitions.add(new SequenceDefinition(
                    buildTwoByTwoSteps(farA, near, inside, client.player),
                    TWO_BY_TWO_BASE_STEPS, bounds));
            }
        }
        definitions.sort(Comparator
            .comparingInt((SequenceDefinition definition) ->
                baseObsidianCollisionCount(client, definition))
            .thenComparingDouble(definition ->
                definition.bounds().squaredCenterDistanceTo(client.player)));
        return List.copyOf(definitions);
    }

    private static int baseObsidianCollisionCount(MinecraftClient client,
                                                   SequenceDefinition definition) {
        int collisions = 0;
        for (int index = 0; index < definition.baseStepCount(); index++) {
            TrapStep step = definition.steps().get(index);
            if (step.material().block() == Blocks.OBSIDIAN
                && playerIntersectsObsidianCell(client, step.position())) {
                collisions++;
            }
        }
        return collisions;
    }

    private static int[] containingMinimums(int occupiedMin, int occupiedWidth,
                                            double localCoordinate,
                                            double targetCoordinate) {
        if (occupiedWidth == 2) {
            return new int[] {occupiedMin};
        }
        boolean localOnNegativeSide = localCoordinate < targetCoordinate;
        int preferred = localOnNegativeSide ? occupiedMin - 1 : occupiedMin;
        int alternate = localOnNegativeSide ? occupiedMin : occupiedMin - 1;
        return new int[] {preferred, alternate};
    }

    private static int fartherOccupiedCell(int minimum, int maximum,
                                           double localCoordinate) {
        double minimumDistance = Math.abs(minimum + 0.5D - localCoordinate);
        double maximumDistance = Math.abs(maximum + 0.5D - localCoordinate);
        return minimumDistance >= maximumDistance ? minimum : maximum;
    }

    private static List<TrapStep> buildTwoByTwoSteps(BlockPos farA,
                                                      Direction near,
                                                      Direction insideSide,
                                                      ClientPlayerEntity player) {
        Direction far = near.getOpposite();
        Direction outsideSide = insideSide.getOpposite();
        BlockPos farB = farA.offset(insideSide);
        BlockPos nearA = farA.offset(near);
        BlockPos nearB = farB.offset(near);
        BlockPos farWallA = farA.offset(far);
        BlockPos farWallB = farB.offset(far);
        BlockPos farSideA = farA.offset(outsideSide);
        BlockPos farSideB = farB.offset(insideSide);
        BlockPos nearSideA = nearA.offset(outsideSide);
        BlockPos nearSideB = nearB.offset(insideSide);
        BlockPos nearWallA = nearA.offset(near);
        BlockPos nearWallB = nearB.offset(near);
        List<TrapStep> order = new ArrayList<>(
            TWO_BY_TWO_BASE_STEPS + TWO_BY_TWO_ROOF_STEPS);

        // Reference frames 1-4: make the two perpendicular blocks around the
        // target corner, web the target's feet, then repeat the obsidian pair
        // directly above it. The head web is deliberately left open here.
        addFarthestFirst(order, player,
            new TrapStep(farWallA, OBSIDIAN),
            new TrapStep(farSideA, OBSIDIAN));
        order.add(new TrapStep(farA, COBWEB));
        addFarthestFirst(order, player,
            new TrapStep(farWallA.up(), OBSIDIAN),
            new TrapStep(farSideA.up(), OBSIDIAN));

        // Reference frames 5-6: expand the four middle shell cells at the
        // feet first, then their four supported upper cells. Farthest-first
        // ordering inside each frame keeps nearer shell blocks from screening
        // a later click.
        addFarthestFirst(order, player,
            new TrapStep(farWallB, OBSIDIAN),
            new TrapStep(nearSideA, OBSIDIAN),
            new TrapStep(farSideB, OBSIDIAN),
            new TrapStep(nearWallA, OBSIDIAN));
        addFarthestFirst(order, player,
            new TrapStep(farWallB.up(), OBSIDIAN),
            new TrapStep(nearSideA.up(), OBSIDIAN),
            new TrapStep(farSideB.up(), OBSIDIAN),
            new TrapStep(nearWallA.up(), OBSIDIAN));

        // Reference frame 7: now add the target's head web, then fill the
        // other three web columns. Keep each column bottom-to-top and do the
        // farther of B/C before the other; nearest diagonal D is always last.
        order.add(new TrapStep(farA.up(), COBWEB));
        addWebColumnsFarthestFirst(order, player, farB, nearA);
        order.add(new TrapStep(nearB, COBWEB));
        order.add(new TrapStep(nearB.up(), COBWEB));

        // Reference frame 8: close the nearest outside corner only after all
        // eight interior webs are present.
        addFarthestFirst(order, player,
            new TrapStep(nearSideB, OBSIDIAN),
            new TrapStep(nearWallB, OBSIDIAN));
        addFarthestFirst(order, player,
            new TrapStep(nearSideB.up(), OBSIDIAN),
            new TrapStep(nearWallB.up(), OBSIDIAN));

        // Reference frame 9: jump-gated 2x2 roof, far corner to near corner.
        order.add(new TrapStep(farA.up(2), OBSIDIAN));
        addFarthestFirst(order, player,
            new TrapStep(farB.up(2), OBSIDIAN),
            new TrapStep(nearA.up(2), OBSIDIAN));
        order.add(new TrapStep(nearB.up(2), OBSIDIAN));
        return List.copyOf(order);
    }

    private static void addWebColumnsFarthestFirst(List<TrapStep> order,
                                                    ClientPlayerEntity player,
                                                    BlockPos first,
                                                    BlockPos second) {
        Vec3d eyes = player.getEyePos();
        BlockPos farther = eyes.squaredDistanceTo(Vec3d.ofCenter(first))
            >= eyes.squaredDistanceTo(Vec3d.ofCenter(second)) ? first : second;
        BlockPos nearer = farther == first ? second : first;
        order.add(new TrapStep(farther, COBWEB));
        order.add(new TrapStep(farther.up(), COBWEB));
        order.add(new TrapStep(nearer, COBWEB));
        order.add(new TrapStep(nearer.up(), COBWEB));
    }

    private static void addFarthestFirst(List<TrapStep> order,
                                         ClientPlayerEntity player,
                                         TrapStep... tier) {
        List<TrapStep> sorted = new ArrayList<>(List.of(tier));
        Vec3d eyes = player.getEyePos();
        sorted.sort(Comparator.comparingDouble((TrapStep step) ->
            eyes.squaredDistanceTo(Vec3d.ofCenter(step.position()))).reversed());
        order.addAll(sorted);
    }

    private static Direction farDirection(ClientPlayerEntity player, PlayerEntity target) {
        double deltaX = target.getX() - player.getX();
        double deltaZ = target.getZ() - player.getZ();
        if (Math.abs(deltaX) > Math.abs(deltaZ)) {
            return deltaX >= 0.0D ? Direction.EAST : Direction.WEST;
        }
        return deltaZ >= 0.0D ? Direction.SOUTH : Direction.NORTH;
    }

    private static Direction clockwise(Direction direction) {
        return switch (direction) {
            case NORTH -> Direction.EAST;
            case EAST -> Direction.SOUTH;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.NORTH;
            default -> throw new IllegalArgumentException("Horizontal direction required");
        };
    }

    private static Direction counterClockwise(Direction direction) {
        return clockwise(direction).getOpposite();
    }

    private int firstIncompleteStep(MinecraftClient client, int limit) {
        return firstIncompleteStep(client, sequenceDefinition.steps(), limit);
    }

    private static int firstIncompleteStep(MinecraftClient client, List<TrapStep> steps,
                                           int limit) {
        int cappedLimit = Math.min(limit, steps.size());
        for (int index = 0; index < cappedLimit; index++) {
            if (stepState(client, steps.get(index)) != StepState.COMPLETE) {
                return index;
            }
        }
        return cappedLimit;
    }

    private boolean baseStepsComplete(MinecraftClient client) {
        if (!sequenceActive()) {
            return false;
        }
        for (int index = 0; index < sequenceDefinition.baseStepCount(); index++) {
            if (stepState(client, sequenceDefinition.steps().get(index)) != StepState.COMPLETE) {
                return false;
            }
        }
        return true;
    }

    private static boolean localIntersectsWebCell(MinecraftClient client,
                                                  BlockPos position) {
        if (client.player == null) {
            return true;
        }
        return new Box(position).contract(1.0E-4D)
            .intersects(client.player.getBoundingBox());
    }

    private static boolean localIntersectsAnyWebCell(MinecraftClient client,
                                                      List<TrapStep> steps) {
        for (TrapStep step : steps) {
            if (step.material().block() == Blocks.COBWEB
                && localIntersectsWebCell(client, step.position())) {
                return true;
            }
        }
        return false;
    }

    private static boolean playerIntersectsObsidianCell(MinecraftClient client,
                                                         BlockPos position) {
        if (client.world == null) {
            return true;
        }
        Box destination = new Box(position);
        for (PlayerEntity player : client.world.getPlayers()) {
            if (!player.isRemoved() && player.isAlive() && !player.isSpectator()
                && destination.intersects(player.getBoundingBox())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasBlockedStep(MinecraftClient client, List<TrapStep> steps,
                                          int limit) {
        int cappedLimit = Math.min(limit, steps.size());
        for (int index = 0; index < cappedLimit; index++) {
            if (stepState(client, steps.get(index)) == StepState.BLOCKED) {
                return true;
            }
        }
        return false;
    }

    private static boolean allStepsComplete(MinecraftClient client, List<TrapStep> steps) {
        for (TrapStep step : steps) {
            if (stepState(client, step) != StepState.COMPLETE) {
                return false;
            }
        }
        return true;
    }

    private static StepState stepState(MinecraftClient client, TrapStep step) {
        if (client.world == null || !client.world.isInBuildLimit(step.position())
            || !client.world.getWorldBorder().contains(step.position())) {
            return StepState.BLOCKED;
        }
        BlockState state = client.world.getBlockState(step.position());
        if (state.isOf(step.material().block())) {
            return StepState.COMPLETE;
        }
        return state.isReplaceable() ? StepState.MISSING : StepState.BLOCKED;
    }

    private static boolean validTarget(MinecraftClient client, TrapStep step) {
        if (client.player == null || client.world == null
            || stepState(client, step) != StepState.MISSING) {
            return false;
        }
        if (step.material().block() == Blocks.COBWEB
            && localIntersectsWebCell(client, step.position())) {
            return false;
        }
        if (step.material().block() == Blocks.OBSIDIAN
            && playerIntersectsObsidianCell(client, step.position())) {
            return false;
        }
        return client.world.canPlace(step.material().block().getDefaultState(),
            step.position(), ShapeContext.ofPlacement(client.player));
    }

    private static boolean validBlockInteraction(MinecraftClient client,
                                                 BlockHitResult expected,
                                                 double configuredLimit) {
        if (client.player == null || client.world == null || expected == null
            || configuredLimit <= 0.0D
            || !client.player.canInteractWithBlockAt(expected.getBlockPos(), 0.0D)) {
            return false;
        }
        double cap = Math.min(configuredLimit, client.player.getBlockInteractionRange());
        Vec3d eyes = client.player.getEyePos();
        if (eyes.squaredDistanceTo(expected.getPos()) > cap * cap + 1.0E-7D) {
            return false;
        }
        Vec3d end = eyes.add(
            RotationManager.getInstance().serverRotation().direction().multiply(cap));
        HitResult result = client.world.raycast(new RaycastContext(
            eyes, end, RaycastContext.ShapeType.OUTLINE,
            RaycastContext.FluidHandling.NONE, client.player
        ));
        return result instanceof BlockHitResult hit
            && hit.getType() == HitResult.Type.BLOCK
            && hit.getBlockPos().equals(expected.getBlockPos())
            && hit.getSide() == expected.getSide()
            && hit.getPos().squaredDistanceTo(expected.getPos()) <= 0.04D;
    }

    private static boolean validSupport(MinecraftClient client, BlockPos target,
                                        BlockHitResult hit) {
        if (client.world == null || hit == null
            || !hit.getBlockPos().offset(hit.getSide()).equals(target)) {
            return false;
        }
        BlockState support = client.world.getBlockState(hit.getBlockPos());
        return !support.isReplaceable()
            && (support.isOf(Blocks.COBWEB)
                || support.isSideSolidFullSquare(
                    client.world, hit.getBlockPos(), hit.getSide()));
    }

    private static boolean visibleSupport(MinecraftClient client, BlockHitResult expected) {
        if (client.player == null || client.world == null) {
            return false;
        }
        Direction face = expected.getSide();
        Vec3d end = expected.getPos().subtract(
            face.getOffsetX() * 0.01D,
            face.getOffsetY() * 0.01D,
            face.getOffsetZ() * 0.01D
        );
        BlockHitResult ray = client.world.raycast(new RaycastContext(
            client.player.getEyePos(), end,
            RaycastContext.ShapeType.OUTLINE,
            RaycastContext.FluidHandling.NONE,
            client.player
        ));
        return ray.getType() == HitResult.Type.BLOCK
            && ray.getBlockPos().equals(expected.getBlockPos())
            && ray.getSide() == expected.getSide();
    }

    private static boolean basicStateValid(MinecraftClient client) {
        return client.player != null
            && client.world != null
            && client.interactionManager != null
            && client.currentScreen == null
            && client.getCameraEntity() == client.player
            && client.player.networkHandler.isLoaded()
            && client.player.isAlive()
            && !client.player.isSpectator()
            && !client.player.hasVehicle()
            && !client.player.isUsingItem()
            && !client.interactionManager.isBreakingBlock();
    }

    private static int findHotbarSlot(MinecraftClient client, Item item) {
        if (client.player == null) {
            return -1;
        }
        int selected = client.player.getInventory().getSelectedSlot();
        if (client.player.getInventory().getStack(selected).isOf(item)) {
            return selected;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (client.player.getInventory().getStack(slot).isOf(item)) {
                return slot;
            }
        }
        return -1;
    }

    private enum StepState {
        COMPLETE,
        MISSING,
        BLOCKED
    }

    private record Material(Item item, Block block) {
    }

    private record TrapStep(BlockPos position, Material material) {
    }

    private enum TrapKind {
        ONE_BY_ONE,
        TWO_BY_TWO
    }

    private record OccupiedSpan(int minX, int maxX, int minZ, int maxZ, int baseY) {
        private int widthX() {
            return maxX - minX + 1;
        }

        private int widthZ() {
            return maxZ - minZ + 1;
        }
    }

    private record InteriorBounds(int minX, int maxX, int minZ, int maxZ,
                                  int baseY, TrapKind kind) {
        private boolean contains(PlayerEntity target) {
            if (target == null) {
                return false;
            }
            Box box = target.getBoundingBox();
            return box.minX >= minX - FOOTPRINT_EPSILON
                && box.maxX <= maxX + FOOTPRINT_EPSILON
                && box.minZ >= minZ - FOOTPRINT_EPSILON
                && box.maxZ <= maxZ + FOOTPRINT_EPSILON
                && box.maxY > baseY + FOOTPRINT_EPSILON
                && box.minY < baseY + 2.0D - FOOTPRINT_EPSILON;
        }

        private boolean containsWithClearance(PlayerEntity target) {
            if (!contains(target) || kind != TrapKind.ONE_BY_ONE) {
                return contains(target);
            }
            Box box = target.getBoundingBox();
            return box.minX >= minX + ONE_BY_ONE_CLEARANCE - FOOTPRINT_EPSILON
                && box.maxX <= maxX - ONE_BY_ONE_CLEARANCE + FOOTPRINT_EPSILON
                && box.minZ >= minZ + ONE_BY_ONE_CLEARANCE - FOOTPRINT_EPSILON
                && box.maxZ <= maxZ - ONE_BY_ONE_CLEARANCE + FOOTPRINT_EPSILON;
        }

        private double squaredCenterDistanceTo(PlayerEntity player) {
            double deltaX = player.getX() - (minX + maxX) * 0.5D;
            double deltaZ = player.getZ() - (minZ + maxZ) * 0.5D;
            return deltaX * deltaX + deltaZ * deltaZ;
        }
    }

    private record SequenceDefinition(List<TrapStep> steps, int baseStepCount,
                                      InteriorBounds bounds) {
        private SequenceDefinition {
            steps = List.copyOf(steps);
        }

        private boolean valid() {
            if (bounds == null || baseStepCount <= 0 || baseStepCount >= steps.size()) {
                return false;
            }
            return switch (bounds.kind()) {
                case ONE_BY_ONE -> baseStepCount == ONE_BY_ONE_BASE_STEPS
                    && steps.size() == ONE_BY_ONE_BASE_STEPS + ONE_BY_ONE_ROOF_STEPS;
                case TWO_BY_TWO -> baseStepCount == TWO_BY_TWO_BASE_STEPS
                    && steps.size() == TWO_BY_TWO_BASE_STEPS + TWO_BY_TWO_ROOF_STEPS;
            };
        }
    }

    private record TargetCandidate(PlayerEntity target, SequenceDefinition definition) {
    }

    private record PlacementPlan(ClientWorld world, PlayerEntity targetEntity,
                                 SequenceDefinition definition, TrapStep step, int slot,
                                 BlockHitResult hit, int stepIndex) {
    }

    private record PostPlacementHold(ClientWorld world, ClientPlayerEntity player,
                                     Vec3d lookPoint) {
    }
}
