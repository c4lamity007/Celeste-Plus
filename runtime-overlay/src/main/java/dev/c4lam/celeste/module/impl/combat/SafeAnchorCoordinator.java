package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.action.UseInputLease;
import dev.c4lam.celeste.action.VanillaInputGuard;
import dev.c4lam.celeste.compat.MinecraftAccess;
import dev.c4lam.celeste.gui.model.GuiCatalog;
import dev.c4lam.celeste.gui.model.GuiModule;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.rotation.MoveFixMode;
import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.rotation.RotationTask;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.attribute.EnvironmentAttributes;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/** One input/rotation/action owner shared by the visible and silent GUI entries. */
final class SafeAnchorCoordinator {
    private final String moduleName;
    private static final int PRIORITY = 195;
    private static final double REACH = 4.45D;
    private enum Step { IDLE, PLACE, CHARGE, SHIELD, EXPLODE, DONE }

    private final Random random = new Random();
    private final SafeAnchorAim aim = new SafeAnchorAim(random);
    private final Set<BlockPos> owned = new HashSet<>();
    private final Set<BlockPos> abandoned = new HashSet<>();
    private ClientWorld world;
    private ClientPlayerEntity player;
    private GuiModule module;
    private Step step = Step.IDLE;
    private BlockPos anchor;
    private BlockHitResult hit;
    private SafeAnchorPlacement.Plan shield;
    private int visibleSlot = -1;
    private int logicalSlot = -1;
    private int nextSlot = -1;
    private boolean silentSwap;
    private boolean silentLook;
    private boolean holdingUse;
    private boolean releaseAfterTick;
    private long dueTick;
    private long loopAfter;
    private long startTick = Long.MIN_VALUE;
    private long actionTick = Long.MIN_VALUE;
    private long lastUseAttemptTick = Long.MIN_VALUE;
    private BlockPos manualPlacement;
    private long manualPlacementTick;

    SafeAnchorCoordinator(String moduleName) { this.moduleName = moduleName; }

    void onStartTick(ModuleContext caller) {
        long tick = ActionScheduler.getInstance().tickNumber();
        if (startTick == tick) return;
        startTick = tick;
        MinecraftClient client = caller.client();
        if (client.player != player || client.world != world) {
            reset(client);
            player = client.player;
            world = client.world;
        }
        if (!usable(client)) { resetCycle(client); return; }
        if (!UseInputLease.physicalDown(client)) { resetCycle(client); return; }
        if (module != null && !module.enabled()) { resetCycle(client); return; }
        if (manualPlacement != null) {
            if (isAnchor(client, manualPlacement)) {
                owned.add(manualPlacement);
                manualPlacement = null;
            } else if (tick - manualPlacementTick >= 4) manualPlacement = null;
        }
        owned.removeIf(pos -> !isAnchor(client, pos));
        abandoned.removeIf(pos -> !isAnchor(client, pos));

        if (step == Step.DONE) {
            claim(client);
            if (loopAfter > 0 && tick >= loopAfter && !isAnchor(client, anchor)) {
                resetCycle(client);
            } else return;
        }
        if (step == Step.IDLE) {
            module = preferredModule();
            if (module == null || takeover(client)) { module = null; return; }
            if (!start(client, tick)) {
                if (eligibleChargedAnchor(client)) {
                    abandon(client);
                    claim(client);
                    return;
                }
                if (client.player.getInventory().getSelectedStack().isOf(Items.RESPAWN_ANCHOR)
                    && client.crosshairTarget instanceof BlockHitResult target
                    && target.getType() == HitResult.Type.BLOCK && !isAnchor(client, target.getBlockPos())) {
                    manualPlacement = placement(client, Items.RESPAWN_ANCHOR, target);
                    manualPlacementTick = tick;
                }
                module = null;
                return;
            }
        } else if (!ownsContext(client) || takeover(client)) {
            abandon(client);
            claim(client);
            return;
        }
        if (!claim(client)) { abandon(client); return; }
        if (tick > dueTick || !validateTarget(client)) { abandon(client); return; }
        if (silentSwap && SilentSlotManager.getInstance().isOwnedBy(this)
            && !SilentSlotManager.getInstance().acquire(this, logicalSlot, PRIORITY, 2)) {
            abandon(client);
            return;
        }
        updateAim(client, tick >= dueTick, true);
    }

    void onActionTick(ModuleContext caller) {
        long tick = ActionScheduler.getInstance().tickNumber();
        if (actionTick == tick) return;
        actionTick = tick;
        if (step == Step.IDLE || !holdingUse || !UseInputLease.getInstance().owns(this)) return;
        Step expected = step;
        ActionScheduler.getInstance().submit(this, PRIORITY, client -> {
            if (step != expected || !UseInputLease.getInstance().owns(this)) return;
            if (step == Step.DONE) return; // Keep other held-use macros out until release.
            if (!usable(client) || module == null || !module.enabled()
                || !UseInputLease.physicalDown(client) || !ownsContext(client)
                || takeover(client) || !validateTarget(client)) {
                abandon(client);
                return;
            }
            if (tick > dueTick || !selectSlot(client)) { abandon(client); return; }
            if (!silentLook && RotationManager.getInstance().isOwnedBy(this))
                presentVisible(client, RotationManager.getInstance().serverRotation());
            if (tick < dueTick) return; // The selected swap gets its own tick.
            if (!RotationManager.getInstance().isOwnedBy(this) || !aimHits(client)) {
                abandon(client);
                return;
            }
            ActionResult result;
            try {
                result = interact(client);
            } catch (RuntimeException failure) {
                abandon(client);
                throw failure;
            }
            if (result == null || !result.isAccepted()) { abandon(client); return; }
            if (result instanceof ActionResult.Success success
                && success.swingSource() == ActionResult.SwingSource.CLIENT) {
                client.player.swingHand(Hand.MAIN_HAND);
            }
            advance(client, tick);
        });
    }

    void onRenderFrame(ModuleContext caller) {
        MinecraftClient client = caller.client();
        if (module == null || caller.module() != module || !activeStep()) return;
        if (!usable(client) || !module.enabled() || !UseInputLease.physicalDown(client)
            || !ownsContext(client) || takeover(client) || !UseInputLease.getInstance().owns(this)) {
            abandon(client);
            return;
        }
        // If another owner preempted our look, never write the visible camera for it.
        if (!RotationManager.getInstance().isOwnedBy(this)) { abandon(client); return; }
        if (step == Step.EXPLODE && !validateTarget(client)) { abandon(client); return; }
        updateAim(client, false, false);
    }

    void onEndTick(ModuleContext caller) {
        // Vanilla has completed the movement packet for this action tick.
        lastUseAttemptTick = Long.MIN_VALUE;
        if (activeStep() && ActionScheduler.getInstance().tickNumber() >= dueTick
            && !ActionScheduler.getInstance().executedFor(this)) abandon(caller.client());
        if (!releaseAfterTick) return;
        releaseAfterTick = false;
        // The successful final look/slot survives through vanilla's movement packet.
        releaseAimAndSlot(caller.client());
    }

    void onDisable(String name, MinecraftClient client) {
        if (module != null && module.name().equals(name)) resetCycle(client);
    }

    void onWorldChanged(MinecraftClient client) { reset(client); }

    private boolean start(MinecraftClient client, long tick) {
        ItemStack selected = client.player.getInventory().getSelectedStack();
        if (!(selected.isOf(Items.RESPAWN_ANCHOR) || selected.isOf(Items.GLOWSTONE)
            || selected.isOf(Items.TOTEM_OF_UNDYING) || selected.isEmpty())) return false;
        if (!(client.crosshairTarget instanceof BlockHitResult target)
            || target.getType() != HitResult.Type.BLOCK || !hitVisible(client, target)) return false;
        if (find(client, Items.TOTEM_OF_UNDYING) < 0) return false;
        ModuleContext context = context(client);
        silentLook = module.name().equals("Silent Safe Anchor");
        silentSwap = context.toggle("Silent swap", true);
        visibleSlot = client.player.getInventory().getSelectedSlot();
        logicalSlot = visibleSlot;
        shield = null;
        if (isAnchor(client, target.getBlockPos())) {
            anchor = target.getBlockPos().toImmutable();
            if (abandoned.contains(anchor) || context.toggle("Only own anchors", true) && !owned.contains(anchor)
                || anchorWorks(client)) return false;
            int needed = charges(client) == 0 ? 2 : 1;
            if (count(client, Items.GLOWSTONE) < needed) return false;
            if (charges(client) > 0) {
                shield = SafeAnchorPlacement.find(client, anchor, random);
                if (shield == null) return false;
                schedule(client, Step.SHIELD, shield.placementHit(), tick, false);
            } else schedule(client, Step.CHARGE, target, tick, false);
        } else {
            if (!context.toggle("Auto swap anchor", true) || find(client, Items.RESPAWN_ANCHOR) < 0
                || count(client, Items.GLOWSTONE) < 2) return false;
            anchor = placement(client, Items.RESPAWN_ANCHOR, target);
            if (anchorWorks(client) || !placeableAnchor(client, target)) return false;
            schedule(client, Step.PLACE, target, tick, false);
        }
        return activeStep();
    }

    private boolean eligibleChargedAnchor(MinecraftClient client) {
        ItemStack held = client.player.getInventory().getSelectedStack();
        if (!(held.isOf(Items.RESPAWN_ANCHOR) || held.isOf(Items.GLOWSTONE)
            || held.isOf(Items.TOTEM_OF_UNDYING) || held.isEmpty())
            || !(client.crosshairTarget instanceof BlockHitResult target)
            || target.getType() != HitResult.Type.BLOCK || !isAnchor(client, target.getBlockPos())) return false;
        BlockPos pos = target.getBlockPos();
        if (context(client).toggle("Only own anchors", true) && !owned.contains(pos) && !abandoned.contains(pos))
            return false;
        anchor = pos.toImmutable();
        // An eligible charged anchor must never fall through to vanilla when
        // shield planning/material checks fail: that held click would detonate it.
        return charges(client) > 0 && !anchorWorks(client);
    }

    private void advance(MinecraftClient client, long tick) {
        switch (step) {
            case PLACE -> {
                owned.add(anchor);
                BlockHitResult target = SafeAnchorPlacement.visibleAnchor(client, anchor, random);
                if (target == null) { abandon(client); return; }
                schedule(client, Step.CHARGE, target, tick, true);
            }
            case CHARGE -> {
                shield = SafeAnchorPlacement.find(client, anchor, random);
                if (shield == null) { abandon(client); return; }
                schedule(client, Step.SHIELD, shield.placementHit(), tick, true);
            }
            case SHIELD -> schedule(client, Step.EXPLODE, shield.anchorHit(), tick, true);
            case EXPLODE -> {
                owned.remove(anchor);
                step = Step.DONE;
                loopAfter = context(client).toggle("Hold to loop", false) ? tick + 2 : 0;
                releaseAfterTick = true;
                if (silentSwap) SilentSlotManager.getInstance().releaseAfterAction(this);
                aim.clear();
            }
            default -> { }
        }
    }

    private void schedule(MinecraftClient client, Step next, BlockHitResult target, long tick, boolean following) {
        step = next;
        hit = target;
        nextSlot = find(client, item(next));
        if (nextSlot < 0 || hit == null) { abandon(client); return; }
        double chance = context(client).slider("2 tick swap chance", 18.0D);
        boolean extra = nextSlot != logicalSlot && random.nextDouble() * 100.0D < chance;
        int gap = (following ? 1 : 0) + (extra ? 1 : 0);
        dueTick = tick + gap;
        Rotation rotations = RotationManager.getInstance().isOwnedBy(this)
            ? RotationManager.getInstance().serverRotation()
            : new Rotation(client.player.getYaw(), client.player.getPitch());
        aim.start(rotations, System.nanoTime(), Math.max(1, gap));
    }

    private boolean validateTarget(MinecraftClient client) {
        if (anchor == null || hit == null || anchorWorks(client)
            || !client.world.isChunkLoaded(anchor.getX() >> 4, anchor.getZ() >> 4)
            || !client.world.getWorldBorder().contains(anchor)
            || nextSlot < 0 || !client.player.getInventory().getStack(nextSlot).isOf(item(step))) return false;
        return switch (step) {
            case PLACE -> count(client, Items.GLOWSTONE) >= 2 && find(client, Items.TOTEM_OF_UNDYING) >= 0
                && placeableAnchor(client, hit) && hitVisible(client, hit);
            case CHARGE -> isAnchor(client, anchor) && charges(client) == 0
                && count(client, Items.GLOWSTONE) >= 2 && find(client, Items.TOTEM_OF_UNDYING) >= 0
                && hitVisible(client, hit);
            case SHIELD -> isAnchor(client, anchor) && charges(client) > 0
                && find(client, Items.TOTEM_OF_UNDYING) >= 0
                && shield != null && SafeAnchorPlacement.validPlacement(client, shield);
            case EXPLODE -> {
                if (!isAnchor(client, anchor) || charges(client) <= 0 || shield == null
                    || !client.world.getBlockState(shield.shieldPos()).isOf(Blocks.GLOWSTONE)) yield false;
                if (!hitVisible(client, hit)) hit = SafeAnchorPlacement.visibleAnchor(client, anchor, random);
                yield hit != null;
            }
            default -> false;
        };
    }

    private void updateAim(MinecraftClient client, boolean due, boolean submit) {
        if (!activeStep() || hit == null) return;
        Rotation look = aim.sample(client.player.getEyePos(), hit.getPos(), System.nanoTime(), due);
        RotationManager rotations = RotationManager.getInstance();
        MoveFixMode moveFix = silentLook ? MoveFixMode.STRICT : MoveFixMode.OFF;
        if (submit) {
            boolean winner = rotations.submit(RotationTask.builder(this, look).priority(PRIORITY)
                .lifetimeTicks(2).smoothing(180.0F, 180.0F).sensitivityQuantized(false)
                .moveFix(moveFix).build());
            if (!winner) { abandon(client); return; }
        } else rotations.hold(this, look, moveFix);
        if (!submit && !silentLook) presentVisible(client, look);
    }

    private static void presentVisible(MinecraftClient client, Rotation look) {
        client.player.setYaw(client.player.getYaw() + MathHelper.wrapDegrees(look.yaw() - client.player.getYaw()));
        client.player.setPitch(look.pitch());
    }

    private boolean aimHits(MinecraftClient client) {
        Vec3d eye = client.player.getEyePos();
        Vec3d end = eye.add(RotationManager.getInstance().serverRotation().direction()
            .multiply(Math.min(REACH, client.player.getBlockInteractionRange())));
        BlockHitResult actual = client.world.raycast(new RaycastContext(eye, end,
            RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, client.player));
        return actual.getType() == HitResult.Type.BLOCK && actual.getBlockPos().equals(hit.getBlockPos())
            && actual.getSide() == hit.getSide();
    }

    private ActionResult interact(MinecraftClient client) {
        SilentSlotManager slots = SilentSlotManager.getInstance();
        int token = silentSwap ? slots.beginLocalProjection(client.player.getInventory()) : -1;
        try {
            // Prediction can fail after vanilla has sent the sequenced use.
            lastUseAttemptTick = ActionScheduler.getInstance().tickNumber();
            // Keep the normal sequenced use -> vanilla movement packet ordering.
            return client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        } finally {
            if (silentSwap) slots.endLocalProjection(client.player.getInventory(), token);
        }
    }

    private boolean selectSlot(MinecraftClient client) {
        if (nextSlot < 0 || !client.player.getInventory().getStack(nextSlot).isOf(item(step))) return false;
        SilentSlotManager slots = SilentSlotManager.getInstance();
        if (silentSwap) {
            if (!slots.acquire(this, nextSlot, PRIORITY, 2)) return false;
        } else if (!slots.selectVisibleSlot(client, nextSlot)) return false;
        logicalSlot = nextSlot;
        if (!silentSwap) visibleSlot = nextSlot;
        return true;
    }

    private boolean claim(MinecraftClient client) {
        holdingUse = UseInputLease.getInstance().acquire(this, PRIORITY,
            ActionScheduler.getInstance().tickNumber(), client);
        return holdingUse;
    }

    private void abandon(MinecraftClient client) {
        if (anchor != null) { abandoned.add(anchor); owned.remove(anchor); }
        step = Step.DONE;
        loopAfter = 0;
        releaseAfterTick = false;
        ActionScheduler.getInstance().cancel(this);
        releaseAimAndSlot(client);
        // Keep held Use consumed until release so a failed safe attempt cannot
        // turn into an unprotected vanilla click or another macro's detonation.
    }

    private void releaseAimAndSlot(MinecraftClient client) {
        RotationManager rotations = RotationManager.getInstance();
        SilentSlotManager slots = SilentSlotManager.getInstance();
        if (lastUseAttemptTick == ActionScheduler.getInstance().tickNumber()) {
            // Keep the action's look for post-movement placement validation and
            // prevent a held-slot restore after use within the same packet tick.
            rotations.releaseAfterAction(this);
            slots.releaseAfterAction(this);
        }
        rotations.release(this);
        slots.release(this);
        aim.clear();
    }

    private void resetCycle(MinecraftClient client) {
        releaseAimAndSlot(client);
        UseInputLease.getInstance().release(this, client);
        ActionScheduler.getInstance().cancel(this);
        step = Step.IDLE;
        module = null;
        anchor = null;
        hit = null;
        shield = null;
        holdingUse = false;
        releaseAfterTick = false;
        visibleSlot = logicalSlot = nextSlot = -1;
        loopAfter = 0;
    }

    private void reset(MinecraftClient client) {
        resetCycle(client);
        owned.clear();
        abandoned.clear();
        manualPlacement = null;
        player = null;
        world = null;
    }

    private boolean ownsContext(MinecraftClient client) {
        if (client.player != player || client.world != world
            || client.player.getInventory().getSelectedSlot() != visibleSlot) return false;
        SilentSlotManager slots = SilentSlotManager.getInstance();
        return !silentSwap || !slots.isActive() || slots.isOwnedBy(this);
    }

    private boolean activeStep() { return step != Step.IDLE && step != Step.DONE; }
    private ModuleContext context(MinecraftClient client) { return new ModuleContext(client, module); }

    private GuiModule preferredModule() {
        GuiModule chosen = null;
        for (GuiModule candidate : GuiCatalog.modules()) {
            if (!candidate.enabled()) continue;
            // Preserve the silent presentation's priority when both entries are enabled.
            if (moduleName.equals("Safe Anchor") && candidate.name().equals("Silent Safe Anchor")) return null;
            if (candidate.name().equals(moduleName)) chosen = candidate;
        }
        return chosen;
    }

    private static boolean usable(MinecraftClient client) {
        return client != null && client.player != null && client.world != null
            && client.interactionManager != null && client.options != null && client.currentScreen == null
            && client.getOverlay() == null && client.isWindowFocused() && client.player.isAlive()
            && !client.player.isSpectator() && !client.player.hasVehicle()
            && !client.player.shouldCancelInteraction() && !client.player.isUsingItem()
            && !client.player.getOffHandStack().isOf(Items.GLOWSTONE);
    }

    private static boolean takeover(MinecraftClient client) {
        return VanillaInputGuard.hasRotationConflict(client) || pressed(client.options.attackKey)
            || client.interactionManager.isBreakingBlock();
    }
    private static boolean pressed(KeyBinding key) { return key.isPressed() || MinecraftAccess.queuedPresses(key) > 0; }

    private static Item item(Step step) {
        return switch (step) {
            case PLACE -> Items.RESPAWN_ANCHOR;
            case CHARGE, SHIELD -> Items.GLOWSTONE;
            default -> Items.TOTEM_OF_UNDYING;
        };
    }
    private int find(MinecraftClient client, Item item) {
        if (logicalSlot >= 0 && client.player.getInventory().getStack(logicalSlot).isOf(item)) return logicalSlot;
        for (int i = 0; i < 9; i++) if (client.player.getInventory().getStack(i).isOf(item)) return i;
        return -1;
    }
    private static int count(MinecraftClient client, Item item) {
        int count = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (stack.isOf(item)) count += stack.getCount();
        }
        return count;
    }
    private static boolean isAnchor(MinecraftClient client, BlockPos pos) {
        return pos != null && client.world.getBlockState(pos).isOf(Blocks.RESPAWN_ANCHOR);
    }
    private int charges(MinecraftClient client) {
        return isAnchor(client, anchor) ? client.world.getBlockState(anchor).get(RespawnAnchorBlock.CHARGES) : 0;
    }
    private boolean anchorWorks(MinecraftClient client) {
        return client.world.getEnvironmentAttributes().getAttributeValue(
            EnvironmentAttributes.RESPAWN_ANCHOR_WORKS_GAMEPLAY, anchor);
    }
    private static BlockPos placement(MinecraftClient client, Item item, BlockHitResult target) {
        return new ItemPlacementContext(client.player, Hand.MAIN_HAND, new ItemStack(item), target).getBlockPos().toImmutable();
    }
    private boolean placeableAnchor(MinecraftClient client, BlockHitResult target) {
        if (!(SafeAnchorPlacement.inertSupport(client.world.getBlockState(target.getBlockPos()))
                || client.world.getBlockState(target.getBlockPos()).isOf(Blocks.FIRE)
                || client.world.getBlockState(target.getBlockPos()).isOf(Blocks.SOUL_FIRE))
            || !client.world.isInBuildLimit(anchor) || !client.world.getFluidState(anchor).isEmpty()) return false;
        ItemPlacementContext placement = new ItemPlacementContext(client.player, Hand.MAIN_HAND,
            new ItemStack(Items.RESPAWN_ANCHOR), target);
        return placement.getBlockPos().equals(anchor) && placement.canPlace()
            && client.world.canPlace(Blocks.RESPAWN_ANCHOR.getDefaultState(), anchor,
                net.minecraft.block.ShapeContext.ofPlacement(client.player))
            && client.world.getOtherEntities(null, new Box(anchor).contract(0.0001D),
                entity -> entity.isAlive() && !entity.isSpectator() && !(entity instanceof ItemEntity)).isEmpty();
    }
    private static boolean hitVisible(MinecraftClient client, BlockHitResult target) {
        Vec3d eye = client.player.getEyePos();
        double reach = Math.min(REACH, client.player.getBlockInteractionRange());
        if (eye.squaredDistanceTo(target.getPos()) > reach * reach
            || !client.world.isInBuildLimit(target.getBlockPos())
            || !client.world.isChunkLoaded(target.getBlockPos().getX() >> 4, target.getBlockPos().getZ() >> 4)
            || !client.world.getWorldBorder().contains(target.getBlockPos())) return false;
        Vec3d delta = target.getPos().subtract(eye);
        BlockHitResult actual = client.world.raycast(new RaycastContext(eye,
            target.getPos().add(delta.normalize().multiply(0.002D)), RaycastContext.ShapeType.OUTLINE,
            RaycastContext.FluidHandling.NONE, client.player));
        return actual.getType() == HitResult.Type.BLOCK && actual.getBlockPos().equals(target.getBlockPos())
            && actual.getSide() == target.getSide();
    }
}
