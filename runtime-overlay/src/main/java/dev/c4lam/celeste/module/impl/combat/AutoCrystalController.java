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
final class AutoCrystalController implements HeldUseBroker.Policy {
    private static final HeldUseBroker BROKER = HeldUseBroker.INSTANCE;

    enum ModuleKind {
        AUTO_CRYSTAL("Auto Crystal"),
        AUTO_HIT_CRYSTAL("Auto Hit Crystal"),
        ANCHOR_MACRO("Anchor Macro");

        final String moduleName;
        ModuleKind(String moduleName) { this.moduleName = moduleName; }
    }

    private enum Branch { NONE, AUTO_CRYSTAL }
    private enum CrystalStep { PLACE, BREAK }

    private enum ActionKind { NONE, AUTO_CRYSTAL_USE, AUTO_CRYSTAL_ATTACK }

    private static final int PRIORITY = 190;
    private static final double CRYSTAL_TARGET_RANGE = 8.0D;

    private final Random random = new Random();

    private Branch branch = Branch.NONE;
    private CrystalStep autoCrystalStep = CrystalStep.PLACE;
    private long tickCounter;
    private long nextAutoCrystalTick;
    private UUID trackedCrystalTarget;

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
            if (branch == Branch.AUTO_CRYSTAL) {
                if (tickAutoCrystal(client)) return;
                resetActiveSequence();
                continue;
            }
            if (allowNewSequence && canStartAutoCrystal(client)) {
                branch = Branch.AUTO_CRYSTAL;
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

    private void executePlan(MinecraftClient client, PlannedAction plan) {
        if (branch != plan.branch) return;
        if (!ownsTransportContext(client)) return;
        switch (plan.kind) {
            case AUTO_CRYSTAL_USE -> executeAutoCrystalUse(client, plan);
            case AUTO_CRYSTAL_ATTACK -> executeAutoCrystalAttack(client, plan);
            default -> { }
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

    private static BlockHitResult crosshairBlock(MinecraftClient client) {
        return client.crosshairTarget instanceof BlockHitResult hit
            && hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    private static boolean validHotbarSlot(int slot) { return slot >= 0 && slot < 9; }

    private static int sampledDelayTicks(int minimum, int maximum, double sample) {
        int lower = Math.max(1, Math.min(minimum, maximum));
        int upper = Math.max(lower, Math.max(minimum, maximum));
        double finite = Double.isFinite(sample) ? sample : 0.0D;
        double bounded = Math.max(0.0D, Math.min(Math.nextDown(1.0D), finite));
        return Math.max(1, (int) Math.round(lower + bounded * (upper - lower)));
    }

    private boolean ownsSequenceContext(ClientPlayerEntity player) {
        if (branch == Branch.AUTO_CRYSTAL) return ownsTransportContext(MinecraftClient.getInstance());
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
        resetAutoCrystalState();
        branch = Branch.NONE;
        clearSequenceOwnership();
    }

    private void resetAutoCrystalState() {
        autoCrystalStep = CrystalStep.PLACE;
        nextAutoCrystalTick = 0;
        trackedCrystalTarget = null;
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
