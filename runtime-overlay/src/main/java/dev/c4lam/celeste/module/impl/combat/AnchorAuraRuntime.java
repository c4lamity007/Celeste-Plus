package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.action.ServerWorldFacts;
import dev.c4lam.celeste.action.SequenceTurnBroker;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.action.GameplayFoundation;
import dev.c4lam.celeste.action.VanillaInputGuard;
import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.compat.MinecraftAccess;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.rotation.MoveFixMode;
import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.rotation.RotationTask;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.Entity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Util;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.attribute.EnvironmentAttributes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Coordinated low-priority CPVP planning; existing held-use combat keeps priority190. */
final class AnchorAuraRuntime {
    private static final int MAX_CANDIDATES = 16;
    private static final int MAX_PLACEMENT_PROBES = 72;
    private static final SequenceTurnBroker CPVP = SequenceTurnBroker.INSTANCE;
    private final Object owner;
    private static final boolean anchors = true;
    private final int priority;
    private final AnchorAuraRules.Confirmation confirmation = new AnchorAuraRules.Confirmation();
    private final AnchorAuraRules.ActionArm arm = new AnchorAuraRules.ActionArm();
    private ClientWorld world;
    private ClientPlayerEntity player;
    private Plan plan;
    private Plan pending;
    private Rotation plannedLook;
    private BlockPos activeAnchor;
    private BlockPos activeCrystal;
    private long nextTick;
    private int targetId = -1;
    private double swordFeet;
    private AnchorAuraFeature.Preview preview;

    private enum Kind { SWORD, OBSIDIAN_PLACE, CRYSTAL_PLACE, CRYSTAL_BREAK, ANCHOR_PLACE, ANCHOR_CHARGE, ANCHOR_BREAK }
    private record Plan(Kind kind, BlockPos position, BlockHitResult hit, Entity entity, int slot,
                        double damage, double selfDamage, int chargeBefore) { }
    private record Candidate(BlockPos position, BlockHitResult hit, EndCrystalEntity crystal,
                             boolean existing, double estimate) { }
    private record Damage(double target, double self, boolean safe) { }

    AnchorAuraRuntime(Object owner) {
        this.owner = owner;
        priority = 170;
    }

    AnchorAuraFeature.Preview preview() { return preview; }

    void clear() {
        CPVP.remove(owner);
        GameplayFoundation.releaseOwner(owner);
        world = null;
        player = null;
        plan = pending = null;
        plannedLook = null;
        activeAnchor = null;
        activeCrystal = null;
        preview = null;
        targetId = -1;
        nextTick = 0;
        confirmation.clear();
        arm.clear();
    }

    void start(ModuleContext context) {
        try { prepare(context); }
        finally { CPVP.offer(owner, ActionScheduler.getInstance().tickNumber(), plan != null, plannedLook, priority); }
    }

    private void prepare(ModuleContext context) {
        MinecraftClient client = context.client();
        preview = null;
        RotationManager.getInstance().release(owner);
        ActionScheduler.getInstance().cancel(owner);
        if (!connected(client)) { clear(); return; }
        if (world != client.world || player != client.player) {
            clear(); world = client.world; player = client.player;
        }
        CPVP.register(owner, 1, range(context));
        long tick = ActionScheduler.getInstance().tickNumber();
        Entity shared = client.world.getEntityById(CPVP.target());
        if (!validEnemy(client, shared) || client.player.squaredDistanceTo(shared) > Math.pow(CPVP.maximumRange(), 2)) {
            CPVP.target(-1);
        }
        if (targetId >= 0 && targetId != CPVP.target()) abandonSequence();
        if (confirmation.active()) {
            if (!observePending(client, tick)) {
                // An invisible inventory pauses interaction, but observed server
                // state still retires confirmations and timeouts during it.
                if (ready(client)) publishPreview(context, pending);
                return;
            }
        }
        if (!ready(client)) {
            // User UI/Silent Totem is a pause, not evidence that a pending action failed.
            discardPlan();
            GameplayFoundation.releaseOwner(owner);
            return;
        }
        if (tick < nextTick || !CPVP.available(owner) || VanillaInputGuard.hasInteractionConflict(client) || slotContended()) {
            discardPlan(); return;
        }
        List<PlayerEntity> enemies = enemies(client, range(context));
        if (enemies.isEmpty()) { abandonSequence(); targetId = -1; return; }
        if (CPVP.target() < 0) CPVP.target(enemies.get(0).getId());
        // A sticky target can fall outside the bounded nearest-four shortlist
        // when another player approaches; that is not target loss.
        Entity selectedTarget = client.world.getEntityById(CPVP.target());
        PlayerEntity target = validEnemy(client, selectedTarget)
            && client.player.squaredDistanceTo(selectedTarget) <= range(context) * range(context)
            ? (PlayerEntity) selectedTarget : null;
        if (target == null) { abandonSequence(); return; }
        if (targetId != target.getId()) { abandonSequence(); targetId = target.getId(); }
        // Both auras score one shared opponent. Friends remain independently
        // excluded by the blast safety check.
        List<PlayerEntity> targets = List.of(target);
        if (plan != null && !planStillUsable(client, context, plan)) discardPlan();
        if (plan == null) {
            BlockPos sequencePosition = anchors ? activeAnchor : activeCrystal;
            plan = anchorPlan(client, context, targets);
            if (CPVP.owns(owner) && sequencePosition != null
                && (plan == null || !sequencePosition.equals(plan.position))) {
                // If this explosion stops being viable, yield rather than keep
                // reserving CPVP while preparing unrelated new blocks forever.
                finishSequence();
                discardPlan();
                return;
            }
            if (plan != null) {
                Vec3d aim = plan.entity != null ? plan.entity.getBoundingBox().getCenter() : plan.hit.getPos();
                plannedLook = Rotation.lookingAt(client.player, aim);
                arm.clear();
            }
        }
        publishPreview(context, plan);
        if (plan == null) finishSequence();
    }

    void action(ModuleContext context) {
        Plan selected = plan;
        if (selected == null || CPVP.winner(ActionScheduler.getInstance().tickNumber()) != owner || !ready(context.client()) || !arm.ready(ActionScheduler.getInstance().tickNumber(),
            RotationManager.getInstance().isOwnedBy(owner), VanillaInputGuard.hasInteractionConflict(context.client()), slotContended())) return;
        ActionScheduler.getInstance().submit(owner, priority, client -> {
            if (selected != plan || !ready(client) || client.world != world || client.player != player
                || !RotationManager.getInstance().isOwnedBy(owner)) return;
            discardPlan();
            execute(client, context, selected);
        });
    }

    void end() {
        arm.observeEnd(ActionScheduler.getInstance().tickNumber(), plan != null && RotationManager.getInstance().isOwnedBy(owner));
    }

    private void discardPlan() { plan = null; plannedLook = null; arm.clear(); }
    private void finishSequence() {
        CPVP.finish(owner);
        activeAnchor = activeCrystal = null;
    }
    private void abandonSequence() {
        discardPlan();
        pending = null;
        confirmation.clear();
        finishSequence();
        // ServerWorldFacts keeps unresolved prediction markers until vanilla
        // receives/reconciles an actual server update; cancellation is not ACK.
    }
    private boolean slotContended() { return SilentSlotManager.getInstance().isActive() && !SilentSlotManager.getInstance().isOwnedBy(owner); }

    private void execute(MinecraftClient client, ModuleContext context, Plan selected) {
        if (selected.slot < 0 || selected.slot > 8 || CPVP.winner(ActionScheduler.getInstance().tickNumber()) != owner) return;
        Entity target = client.world.getEntityById(targetId);
        if (!validEnemy(client, target) || targetId != CPVP.target()
            || client.player.squaredDistanceTo(target) > range(context) * range(context)) { abandonSequence(); return; }
        if (selected.kind != Kind.SWORD) {
            Vec3d explosion = selected.kind == Kind.CRYSTAL_BREAK && selected.entity != null
                ? selected.entity.getEntityPos() : explosion(selected.position, anchors);
            if (!damage(client, context, explosion, anchors ? selected.position : null, List.of((PlayerEntity) target)).safe) return;
        }
        if (selected.kind == Kind.SWORD || selected.kind == Kind.CRYSTAL_BREAK) {
            if (!entityHit(client, selected.entity, selected.slot, context)) return;
            if (selected.kind == Kind.SWORD && (!validEnemy(client, selected.entity)
                || !client.player.getInventory().getStack(selected.slot).isIn(ItemTags.SWORDS)
                || AnchorAuraRules.crystalAction(client.player.getY(), selected.entity.getY(), false, true) != AnchorAuraRules.CrystalAction.SWORD
                || !swordReady(client, selected.slot))) return;
        } else {
            if (!ServerWorldFacts.canPredict(client.world) || !validUse(client, selected)) return;
        }
        long revision = ServerWorldFacts.revision(client.world);
        boolean[] sent = {false};
        boolean scoped = SilentSlotManager.getInstance().runWithSlot(owner, selected.slot, priority, true, () -> {
            if (selected.entity != null) {
                // Crystals have no weapon charge requirement. Applying the
                // visible spear/mace attack component here suppressed breaks.
                if (selected.kind == Kind.SWORD && client.player.isBelowMinimumAttackCharge(client.player.getMainHandStack(), 0)) return;
                client.interactionManager.attackEntity(client.player, selected.entity);
                client.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
                sent[0] = true;
            } else {
                BlockHitResult hit = serverBlockHit(client, selected.hit, context);
                if (hit == null) return;
                ActionResult result = client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
                if (result != null && result.isAccepted())
                    client.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
                sent[0] = result != null && result.isAccepted();
            }
        });
        if (!scoped || !sent[0]) return;
        CPVP.begin(owner, ActionScheduler.getInstance().tickNumber());
        if (selected.kind == Kind.OBSIDIAN_PLACE || selected.kind == Kind.ANCHOR_PLACE
            || selected.kind == Kind.ANCHOR_CHARGE || selected.kind == Kind.ANCHOR_BREAK)
            ServerWorldFacts.predict(client.world, selected.position, revision);
        long tick = ActionScheduler.getInstance().tickNumber();
        pending = selected;
        if (anchors) activeAnchor = selected.position;
        else if (selected.position != null) activeCrystal = selected.position;
        if (selected.kind == Kind.SWORD) {
            swordFeet = selected.entity.getY();
            confirmation.begin(tick, tick, 8);
        } else confirmation.begin(revision, tick);
        nextTick = tick + delay(context);
    }

    private boolean observePending(MinecraftClient client, long tick) {
        if (pending == null) { confirmation.clear(); return true; }
        long revision = 0;
        boolean matches = false;
        if (pending.kind == Kind.SWORD) {
            Entity target = client.world.getEntityById(targetId);
            revision = tick;
            matches = validEnemy(client, target) && target.getY() > swordFeet + 0.18D;
        } else if (pending.kind == Kind.CRYSTAL_PLACE) {
            EndCrystalEntity crystal = crystalAt(client, pending.position);
            ServerWorldFacts.EntityObservation event = crystal == null ? null : ServerWorldFacts.entity(client.world, crystal.getId());
            if (event != null) { revision = event.revision(); matches = event.present(); }
        } else if (pending.kind == Kind.CRYSTAL_BREAK) {
            ServerWorldFacts.EntityObservation event = ServerWorldFacts.entity(client.world, pending.entity.getId());
            if (event != null) { revision = event.revision(); matches = !event.present(); }
        } else {
            ServerWorldFacts.BlockObservation event = ServerWorldFacts.block(client.world, pending.position);
            if (event != null) {
                revision = event.revision();
                BlockState state = event.state();
                matches = switch (pending.kind) {
                    case OBSIDIAN_PLACE -> state.isOf(Blocks.OBSIDIAN);
                    case ANCHOR_PLACE -> state.isOf(Blocks.RESPAWN_ANCHOR);
                    case ANCHOR_CHARGE -> state.isOf(Blocks.RESPAWN_ANCHOR)
                        && state.get(RespawnAnchorBlock.CHARGES) > pending.chargeBefore;
                    case ANCHOR_BREAK -> !state.isOf(Blocks.RESPAWN_ANCHOR);
                    default -> false;
                };
                // Wait for vanilla to finish reconciling prediction/rollback as well.
                matches &= state.equals(client.world.getBlockState(pending.position));
            }
        }
        AnchorAuraRules.ConfirmationResult result = confirmation.observe(revision, tick, matches);
        if (result == AnchorAuraRules.ConfirmationResult.WAITING) return false;
        if (result == AnchorAuraRules.ConfirmationResult.CONFIRMED && pending.position != null)
            ServerWorldFacts.blockReady(client.world, pending.position);
        boolean swordObservation = pending.kind == Kind.SWORD;
        if (AnchorAuraRules.endsSequence(swordObservation, pending.kind == Kind.ANCHOR_BREAK || pending.kind == Kind.CRYSTAL_BREAK,
            result)) finishSequence();
        pending = null;
        if (result == AnchorAuraRules.ConfirmationResult.TIMED_OUT && !swordObservation) nextTick = tick + 4;
        return true;
    }



    private boolean planStillUsable(MinecraftClient client, ModuleContext context, Plan selected) {
        if (plannedLook == null || selected.slot < 0 || selected.slot > 8) return false;
        Vec3d eyes = client.player.getEyePos();
        if (selected.entity != null) {
            if (selected.entity.isRemoved() || !selected.entity.isAlive()
                || selected.entity.getEntityWorld() != client.world) return false;
            if (selected.kind == Kind.SWORD && (!validEnemy(client, selected.entity)
                || !client.player.getInventory().getStack(selected.slot).isIn(ItemTags.SWORDS))) return false;
            double reach = Math.min(Math.min(4.5D, range(context)), AnchorAuraVanillaCombatReach.heldHitRange(client));
            Vec3d end = eyes.add(plannedLook.direction().multiply(reach));
            Vec3d intercept = selected.entity.getBoundingBox().contains(eyes) ? eyes
                : selected.entity.getBoundingBox().raycast(eyes, end).orElse(null);
            return intercept != null && clearRay(client, eyes, intercept);
        }
        // Keep the already-sent aim if it still intersects this face. If player
        // motion changes the ray, re-aim for one tick before attempting use.
        return validUse(client, selected) && blockHit(client, selected.hit, context, plannedLook) != null;
    }

    private static boolean clearRay(MinecraftClient client, Vec3d from, Vec3d to) {
        BlockHitResult hit = client.world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE, client.player));
        return hit.getType() == HitResult.Type.MISS || from.squaredDistanceTo(hit.getPos()) + 1.0E-7 >= from.squaredDistanceTo(to);
    }

    private Plan anchorPlan(MinecraftClient client, ModuleContext context, List<PlayerEntity> enemies) {
        int anchorSlot = slot(client, Items.RESPAWN_ANCHOR, false);
        int glowstoneSlot = slot(client, Items.GLOWSTONE, false);
        int triggerSlot = triggerSlot(client);
        if (triggerSlot < 0) return null;
        if (activeAnchor != null && ServerWorldFacts.blockReady(client.world, activeAnchor)) {
            BlockState state = client.world.getBlockState(activeAnchor);
            BlockHitResult hit = hitBlock(client, activeAnchor, reach(client, context));
            if (state.isOf(Blocks.RESPAWN_ANCHOR) && hit != null && !anchorsWork(client, activeAnchor)) {
                Damage damage = damage(client, context, explosion(activeAnchor, true), activeAnchor, enemies);
                int charges = state.get(RespawnAnchorBlock.CHARGES);
                if (damage.safe && (charges > 0 || glowstoneSlot >= 0))
                    return new Plan(charges == 0 ? Kind.ANCHOR_CHARGE : Kind.ANCHOR_BREAK, activeAnchor, hit, null,
                        charges == 0 ? glowstoneSlot : triggerSlot, damage.target, damage.self, charges);
            }
            activeAnchor = null;
        }
        int radius = (int) Math.ceil(reach(client, context));
        BlockPos origin = client.player.getBlockPos();
        List<Candidate> candidates = new ArrayList<>();
        List<Candidate> shortlist = new ArrayList<>();
        for (BlockPos mutable : BlockPos.iterate(origin.add(-radius, -radius, -radius), origin.add(radius, radius, radius))) {
            BlockPos pos = mutable.toImmutable();
            if (!client.world.isInBuildLimit(pos) || !client.world.isChunkLoaded(pos)
                || anchorsWork(client, pos)) continue;
            if (!ServerWorldFacts.blockReady(client.world, pos)) continue;
            BlockState state = client.world.getBlockState(pos);
            boolean existing = state.isOf(Blocks.RESPAWN_ANCHOR);
            if (!existing && (!state.isAir() || anchorSlot < 0 || glowstoneSlot < 0)) continue;
            if (existing && state.get(RespawnAnchorBlock.CHARGES) == 0 && glowstoneSlot < 0) continue;
            if (new Box(pos).squaredMagnitude(client.player.getEyePos()) > Math.pow(reach(client, context), 2)) continue;
            double estimate = estimate(client, explosion(pos, true), enemies);
            if (estimate < minimum(context)) continue;
            shortlist.add(new Candidate(pos, null, null, existing, estimate));
            shortlist.sort(Comparator.comparingDouble(Candidate::estimate).reversed());
            if (shortlist.size() > MAX_PLACEMENT_PROBES) shortlist.remove(shortlist.size() - 1);
        }
        for (Candidate candidate : shortlist) {
            BlockPos pos = candidate.position;
            BlockHitResult hit = candidate.existing ? hitBlock(client, pos, reach(client, context)) : placementHit(client, pos, context, Items.RESPAWN_ANCHOR);
            if (hit == null) continue;
            offer(candidates, new Candidate(pos, hit, null, candidate.existing, candidate.estimate));
        }
        Plan best = null;
        for (Candidate candidate : candidates) {
            Damage damage = damage(client, context, explosion(candidate.position, true), candidate.position, enemies);
            if (!damage.safe || best != null && damage.target <= best.damage) continue;
            int charge = candidate.existing ? client.world.getBlockState(candidate.position).get(RespawnAnchorBlock.CHARGES) : 0;
            Kind kind = !candidate.existing ? Kind.ANCHOR_PLACE : charge == 0 ? Kind.ANCHOR_CHARGE : Kind.ANCHOR_BREAK;
            best = new Plan(kind, candidate.position, candidate.hit, null,
                kind == Kind.ANCHOR_PLACE ? anchorSlot : kind == Kind.ANCHOR_CHARGE ? glowstoneSlot : triggerSlot,
                damage.target, damage.self, charge);
        }
        return best;
    }

    private boolean validUse(MinecraftClient client, Plan selected) {
        if (!ServerWorldFacts.blockReady(client.world, selected.position)) return false;
        ItemStack stack = client.player.getInventory().getStack(selected.slot);
        BlockState state = client.world.getBlockState(selected.position);
        if ((selected.kind == Kind.OBSIDIAN_PLACE || selected.kind == Kind.ANCHOR_PLACE)
            && (!safeSupport(client.world.getBlockState(selected.hit.getBlockPos()))
            || !new ItemPlacementContext(client.player, Hand.MAIN_HAND, stack, selected.hit).getBlockPos().equals(selected.position))) return false;
        return switch (selected.kind) {
            case OBSIDIAN_PLACE -> stack.isOf(Items.OBSIDIAN) && freePlacement(client, selected.position) && crystalSpace(client, selected.position);
            case CRYSTAL_PLACE -> stack.isOf(Items.END_CRYSTAL) && crystalBase(state) && crystalSpace(client, selected.position);
            case ANCHOR_PLACE -> stack.isOf(Items.RESPAWN_ANCHOR) && state.isAir() && !anchorsWork(client, selected.position)
                && freePlacement(client, selected.position);
            case ANCHOR_CHARGE -> stack.isOf(Items.GLOWSTONE) && state.isOf(Blocks.RESPAWN_ANCHOR)
                && state.get(RespawnAnchorBlock.CHARGES) == selected.chargeBefore && !anchorsWork(client, selected.position);
            case ANCHOR_BREAK -> !stack.isOf(Items.GLOWSTONE) && !client.player.getOffHandStack().isOf(Items.GLOWSTONE)
                && state.isOf(Blocks.RESPAWN_ANCHOR) && state.get(RespawnAnchorBlock.CHARGES) > 0 && !anchorsWork(client, selected.position);
            default -> false;
        };
    }

    private boolean entityHit(MinecraftClient client, Entity target, int slot, ModuleContext context) {
        if (target == null || target.isRemoved() || !target.isAlive() || !AnchorAuraVanillaCombatReach.canAttack(client, target, slot,
            Math.min(4.5D, range(context)), true)) return false;
        Vec3d eyes = client.player.getEyePos();
        Vec3d direction = RotationManager.getInstance().serverRotation().direction();
        double range = Math.min(Math.min(4.5D, range(context)), AnchorAuraVanillaCombatReach.heldHitRange(client));
        Vec3d end = eyes.add(direction.multiply(range));
        EntityHitResult hit = ProjectileUtil.raycast(client.player, eyes, end,
            client.player.getBoundingBox().stretch(direction.multiply(range)).expand(1),
            entity -> entity != client.player && entity.isAlive() && !entity.isSpectator() && entity.canHit(), range * range);
        return hit != null && hit.getEntity() == target;
    }

    private static BlockHitResult serverBlockHit(MinecraftClient client, BlockHitResult planned, ModuleContext context) {
        return blockHit(client, planned, context, RotationManager.getInstance().serverRotation());
    }

    private static BlockHitResult blockHit(MinecraftClient client, BlockHitResult planned, ModuleContext context, Rotation look) {
        if (planned == null || look == null) return null;
        Vec3d eyes = client.player.getEyePos();
        Vec3d end = eyes.add(look.direction().multiply(reach(client, context)));
        BlockHitResult actual = client.world.raycast(new RaycastContext(eyes, end, RaycastContext.ShapeType.OUTLINE,
            RaycastContext.FluidHandling.NONE, client.player));
        return actual.getType() == HitResult.Type.BLOCK && actual.getBlockPos().equals(planned.getBlockPos())
            && actual.getSide() == planned.getSide() ? actual : null;
    }

    private static BlockHitResult hitBlock(MinecraftClient client, BlockPos pos, double range) {
        Vec3d eyes = client.player.getEyePos();
        for (Direction face : Direction.values()) {
            Vec3d end = pos.toCenterPos().add(face.getOffsetX() * 0.4999D, face.getOffsetY() * 0.4999D, face.getOffsetZ() * 0.4999D);
            if (eyes.squaredDistanceTo(end) > range * range) continue;
            BlockHitResult hit = client.world.raycast(new RaycastContext(eyes, end, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, client.player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) return hit;
        }
        return null;
    }

    private static BlockHitResult placementHit(MinecraftClient client, BlockPos pos, ModuleContext context, Item item) {
        if (!freePlacement(client, pos)) return null;
        for (Direction direction : Direction.values()) {
            BlockPos support = pos.offset(direction);
            BlockState state = client.world.getBlockState(support);
            // Do not accidentally open a container/workstation instead of placing.
            if (!safeSupport(state)) continue;
            Vec3d point = support.toCenterPos().add(-direction.getOffsetX() * 0.4999D,
                -direction.getOffsetY() * 0.4999D, -direction.getOffsetZ() * 0.4999D);
            if (client.player.getEyePos().squaredDistanceTo(point) > Math.pow(reach(client, context), 2)) continue;
            BlockHitResult hit = client.world.raycast(new RaycastContext(client.player.getEyePos(), point,
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, client.player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(support)
                && new ItemPlacementContext(client.player, Hand.MAIN_HAND, new ItemStack(item), hit)
                    .getBlockPos().equals(pos)) return hit;
        }
        return null;
    }

    private static boolean freePlacement(MinecraftClient client, BlockPos pos) {
        return client.world.getBlockState(pos).isAir() && client.world.getFluidState(pos).isEmpty()
            && client.world.getOtherEntities(null, new Box(pos).contract(0.0001D), entity -> entity.isAlive() && !entity.isSpectator()).isEmpty();
    }

    private static boolean safeSupport(BlockState state) {
        return state.getBlock().getClass() == Block.class || state.isOf(Blocks.OBSIDIAN) || state.isOf(Blocks.BEDROCK)
            || state.isOf(Blocks.STONE) || state.isOf(Blocks.DIRT) || state.isOf(Blocks.GRASS_BLOCK)
            || state.isOf(Blocks.DEEPSLATE) || state.isOf(Blocks.NETHERRACK) || state.isOf(Blocks.END_STONE);
    }

    private static boolean crystalSpace(MinecraftClient client, BlockPos pos) {
        // 1.21.11 EndCrystalItem checks one air block above the base, but a
        // two-block-high entity box. A second air-block test rejected valid use.
        return client.world.getBlockState(pos.up()).isAir()
            && client.world.getOtherEntities(null, new Box(pos.up()).stretch(0, 1, 0)).isEmpty();
    }

    private static EndCrystalEntity crystalAt(MinecraftClient client, BlockPos pos) {
        for (Entity entity : client.world.getOtherEntities(null, new Box(pos.up()).expand(0.1D))) {
            if (entity instanceof EndCrystalEntity crystal && crystal.isAlive() && !crystal.isRemoved()
                && Math.abs(crystal.getX() - pos.getX() - 0.5D) < 0.3D && Math.abs(crystal.getZ() - pos.getZ() - 0.5D) < 0.3D)
                return crystal;
        }
        return null;
    }

    private Damage damage(MinecraftClient client, ModuleContext context, Vec3d explosion, BlockPos ignored,
                          List<PlayerEntity> enemies) {
        double power = anchors ? 5 : 6;
        double best = 0;
        for (PlayerEntity enemy : enemies) best = Math.max(best, damageAt(client, enemy, explosion, ignored, power, false));
        double self = damageAt(client, client.player, explosion, ignored, power, true);
        boolean friend = false;
        for (PlayerEntity other : client.world.getPlayers()) {
            if (other != client.player && other.isAlive() && !other.isSpectator() && FriendManager.getInstance().isFriend(other)
                && other.squaredDistanceTo(explosion) < power * power * 4) { friend = true; break; }
        }
        boolean safe = AnchorAuraRules.safe(best, self, client.player.getHealth() + client.player.getAbsorptionAmount(),
            minimum(context), MathHelper.clamp(context.slider("Max self damage", 6), 0, 20), friend);
        return new Damage(best, self, safe);
    }

    private double estimate(MinecraftClient client, Vec3d explosion, List<PlayerEntity> enemies) {
        double best = 0;
        for (PlayerEntity enemy : enemies) best = Math.max(best, AnchorAuraRules.damage(enemy.getEntityPos().distanceTo(explosion),
            anchors ? 5 : 6, 1, enemy.getArmor(), enemy.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS), client.world.getDifficulty().getId()));
        return best;
    }

    private static double damageAt(MinecraftClient client, PlayerEntity entity, Vec3d origin, BlockPos ignored,
                                   double power, boolean self) {
        if (entity.squaredDistanceTo(origin) > power * power * 4) return 0;
        Box box = entity.getBoundingBox();
        int visible = 0;
        for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 3; z++) {
            Vec3d point = new Vec3d(MathHelper.lerp(x * 0.5D, box.minX + 0.01D, box.maxX - 0.01D),
                MathHelper.lerp(y * 0.5D, box.minY + 0.01D, box.maxY - 0.01D),
                MathHelper.lerp(z * 0.5D, box.minZ + 0.01D, box.maxZ - 0.01D));
            RaycastContext ray = new RaycastContext(point, origin, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, entity) {
                @Override public VoxelShape getBlockShape(BlockState state, BlockView view, BlockPos pos) {
                    return pos.equals(ignored) ? VoxelShapes.empty() : super.getBlockShape(state, view, pos);
                }
            };
            if (client.world.raycast(ray).getType() == HitResult.Type.MISS) visible++;
        }
        double exposure = visible / 27.0D;
        // Conservative self estimate: hard difficulty, padded exposure, no blast
        // protection/resistance credit. This remains an estimate, not a guarantee.
        if (self) exposure = Math.min(1, exposure + 0.15D);
        double damage = AnchorAuraRules.damage(entity.getEntityPos().distanceTo(origin), power, exposure, entity.getArmor(),
            entity.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS), self ? 3 : client.world.getDifficulty().getId());
        if (self) return damage;
        int protection = 0;
        var registry = client.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            var enchants = entity.getEquippedStack(slot).get(DataComponentTypes.ENCHANTMENTS);
            if (enchants != null) protection += enchants.getLevel(registry.getOrThrow(Enchantments.PROTECTION))
                + 2 * enchants.getLevel(registry.getOrThrow(Enchantments.BLAST_PROTECTION));
        }
        return AnchorAuraRules.protectedDamage(damage, protection);
    }

    private void publishPreview(ModuleContext context, Plan selected) {
        if (anchors && context.toggle("Preview", true) && selected != null && selected.position != null)
            preview = new AnchorAuraFeature.Preview(context.client().world, selected.position.toImmutable(),
                selected.damage, selected.selfDamage, selected.kind != Kind.ANCHOR_PLACE, Util.getMeasuringTimeMs());
    }

    private static List<PlayerEntity> enemies(MinecraftClient client, double range) {
        List<PlayerEntity> result = new ArrayList<>();
        for (PlayerEntity entity : client.world.getPlayers()) {
            if (validEnemy(client, entity) && client.player.squaredDistanceTo(entity) <= range * range) {
                result.add(entity);
                result.sort(Comparator.comparingDouble(other -> client.player.squaredDistanceTo(other)));
                if (result.size() > 4) result.remove(4);
            }
        }
        result.sort(Comparator.comparingDouble(entity -> client.player.squaredDistanceTo(entity)));
        return result;
    }

    private static boolean validEnemy(MinecraftClient client, Entity entity) {
        return entity instanceof PlayerEntity enemy && enemy != client.player && enemy.getEntityWorld() == client.world
            && enemy.isAlive() && !enemy.isRemoved() && !enemy.isSpectator() && !enemy.isCreative()
            && enemy.isAttackable() && !FriendManager.getInstance().isFriend(enemy);
    }

    private static boolean ready(MinecraftClient client) {
        return connected(client)
            && client.currentScreen == null && client.getOverlay() == null && client.isWindowFocused()
            && !client.player.hasVehicle();
    }

    private static boolean connected(MinecraftClient client) {
        return client != null && client.player != null && client.world != null && client.interactionManager != null
            && client.player.isAlive() && !client.player.isSpectator();
    }

    private static void offer(List<Candidate> candidates, Candidate candidate) {
        candidates.add(candidate);
        candidates.sort(Comparator.comparingDouble(Candidate::estimate).reversed());
        if (candidates.size() > MAX_CANDIDATES) candidates.remove(candidates.size() - 1);
    }

    private static int slot(MinecraftClient client, Item item, boolean sword) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (!stack.isEmpty() && (sword ? stack.isIn(ItemTags.SWORDS) : stack.isOf(item))) return slot;
        }
        return -1;
    }

    private static boolean swordReady(MinecraftClient client, int slot) {
        if (slot < 0 || slot > 8) return false;
        var live = client.player.getAttributeInstance(EntityAttributes.ATTACK_SPEED);
        if (live == null) return false;
        // Detached copy retains local effects, replacing only the visible hand's item modifiers.
        var scratch = new EntityAttributeInstance(EntityAttributes.ATTACK_SPEED, ignored -> {});
        scratch.setFrom(live);
        client.player.getMainHandStack().applyAttributeModifiers(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.equals(EntityAttributes.ATTACK_SPEED)) scratch.removeModifier(modifier);
        });
        client.player.getInventory().getStack(slot).applyAttributeModifiers(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.equals(EntityAttributes.ATTACK_SPEED)) scratch.updateModifier(modifier);
        });
        return AnchorAuraRules.swordReady(MinecraftAccess.ticksSinceAttack(client.player), scratch.getValue());
    }

    private static int triggerSlot(MinecraftClient client) {
        if (client.player.getOffHandStack().isOf(Items.GLOWSTONE)) return -1;
        for (int slot = 0; slot < 9; slot++) if (!client.player.getInventory().getStack(slot).isOf(Items.GLOWSTONE)) return slot;
        return -1;
    }

    private static boolean crystalBase(BlockState state) { return state.isOf(Blocks.OBSIDIAN) || state.isOf(Blocks.BEDROCK); }
    private static Vec3d explosion(BlockPos pos, boolean anchor) { return anchor ? pos.toCenterPos() : new Vec3d(pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D); }
    private static boolean anchorsWork(MinecraftClient client, BlockPos pos) { return client.world.getEnvironmentAttributes().getAttributeValue(EnvironmentAttributes.RESPAWN_ANCHOR_WORKS_GAMEPLAY, pos); }
    private static double range(ModuleContext context) { return MathHelper.clamp(context.slider("Range", 6), 2, 10); }
    private static double reach(MinecraftClient client, ModuleContext context) { return Math.min(Math.min(range(context), 4.5D), client.player.getBlockInteractionRange()); }
    private static double minimum(ModuleContext context) { return MathHelper.clamp(context.slider("Min damage", 4), 0, 20); }
    private static int delay(ModuleContext context) { return MathHelper.clamp((int) Math.round(context.slider("Delay", 2)), 1, 10); }
}
