package dev.c4lam.celeste.module.impl.combat;

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

/** Independent low-priority aura planning; existing held-use combat owns priority190. */
final class ExplosiveAuraRuntime {
    private static final int MAX_CANDIDATES = 16;
    private static final int MAX_PLACEMENT_PROBES = 72;
    private static final AuraRules.PendingLease PENDING_LEASE = new AuraRules.PendingLease();
    private final Object owner;
    private final boolean anchors;
    private final int priority;
    private final AuraRules.Confirmation confirmation = new AuraRules.Confirmation();
    private final AuraRules.ActionArm arm = new AuraRules.ActionArm();
    private ClientWorld world;
    private ClientPlayerEntity player;
    private Plan plan;
    private Plan pending;
    private Rotation plannedLook;
    private BlockPos activeAnchor;
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

    ExplosiveAuraRuntime(Object owner, boolean anchors) {
        this.owner = owner;
        this.anchors = anchors;
        priority = anchors ? 171 : 170;
    }

    AnchorAuraFeature.Preview preview() { return preview; }

    void clear() {
        PENDING_LEASE.release(owner);
        GameplayFoundation.releaseOwner(owner);
        world = null;
        player = null;
        plan = pending = null;
        plannedLook = null;
        activeAnchor = null;
        preview = null;
        targetId = -1;
        nextTick = 0;
        confirmation.clear();
        arm.clear();
    }

    void start(ModuleContext context) {
        MinecraftClient client = context.client();
        preview = null;
        RotationManager.getInstance().release(owner);
        ActionScheduler.getInstance().cancel(owner);
        if (!connected(client)) { clear(); return; }
        if (world != client.world || player != client.player) {
            clear(); world = client.world; player = client.player;
        }
        if (!ready(client)) {
            // User UI/Silent Totem is a pause, not evidence that a pending action failed.
            discardPlan();
            GameplayFoundation.releaseOwner(owner);
            return;
        }
        long tick = ActionScheduler.getInstance().tickNumber();
        if (confirmation.active()) {
            if (!observePending(client, tick)) {
                publishPreview(context, pending);
                return;
            }
        }
        if (tick < nextTick || !PENDING_LEASE.available(owner) || VanillaInputGuard.hasInteractionConflict(client) || slotContended()) {
            discardPlan(); return;
        }
        List<PlayerEntity> enemies = enemies(client, range(context));
        if (enemies.isEmpty()) { targetId = -1; discardPlan(); return; }
        if (plan == null) {
            if (anchors) {
                plan = anchorPlan(client, context, enemies);
            } else {
                PlayerEntity target = enemies.stream().filter(entity -> entity.getId() == targetId).findFirst().orElse(enemies.get(0));
                targetId = target.getId();
                plan = crystalPlan(client, context, target, enemies);
            }
            if (plan != null) {
                Vec3d aim = plan.entity != null ? plan.entity.getBoundingBox().getCenter() : plan.hit.getPos();
                plannedLook = Rotation.lookingAt(client.player, aim);
                arm.clear();
            }
        }
        publishPreview(context, plan);
        if (plan != null) {
            RotationManager.getInstance().submit(RotationTask.builder(owner, plannedLook)
                .priority(priority).lifetimeTicks(1).smoothing(180, 180).sensitivityQuantized(false)
                .moveFix(MoveFixMode.STRICT).build());
        }
    }

    void action(ModuleContext context) {
        Plan selected = plan;
        if (selected == null || !PENDING_LEASE.available(owner) || !ready(context.client()) || !arm.ready(ActionScheduler.getInstance().tickNumber(),
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
    private boolean slotContended() { return SilentSlotManager.getInstance().isActive() && !SilentSlotManager.getInstance().isOwnedBy(owner); }

    private void execute(MinecraftClient client, ModuleContext context, Plan selected) {
        if (selected.slot < 0 || selected.slot > 8 || !PENDING_LEASE.available(owner)) return;
        if (!anchors && selected.kind != Kind.SWORD) {
            Entity target = client.world.getEntityById(targetId);
            if (!validEnemy(client, target) || target.getY() <= client.player.getY() + 0.18D) return;
        }
        if (selected.kind != Kind.SWORD) {
            Vec3d explosion = selected.kind == Kind.CRYSTAL_BREAK && selected.entity != null
                ? selected.entity.getEntityPos() : explosion(selected.position, anchors);
            if (!damage(client, context, explosion, anchors ? selected.position : null, enemies(client, range(context))).safe) return;
        }
        if (selected.kind == Kind.SWORD || selected.kind == Kind.CRYSTAL_BREAK) {
            if (!entityHit(client, selected.entity, selected.slot, context)) return;
            if (selected.kind == Kind.SWORD && (!validEnemy(client, selected.entity)
                || !client.player.getInventory().getStack(selected.slot).isIn(ItemTags.SWORDS)
                || AuraRules.crystalAction(client.player.getY(), selected.entity.getY(), false, true) != AuraRules.CrystalAction.SWORD
                || !swordReady(client, selected.slot))) return;
        } else {
            if (!AuraServerState.canPredict(client.world) || !validUse(client, selected)) return;
        }
        long revision = AuraServerState.revision(client.world);
        boolean[] sent = {false};
        boolean scoped = SilentSlotManager.getInstance().runWithSlot(owner, selected.slot, priority, true, () -> {
            if (selected.entity != null) {
                if (client.player.isBelowMinimumAttackCharge(client.player.getMainHandStack(), 0)) return;
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
        PENDING_LEASE.acquire(owner);
        if (selected.kind == Kind.OBSIDIAN_PLACE || selected.kind == Kind.ANCHOR_PLACE
            || selected.kind == Kind.ANCHOR_CHARGE || selected.kind == Kind.ANCHOR_BREAK)
            AuraServerState.predict(client.world, selected.position, revision);
        long tick = ActionScheduler.getInstance().tickNumber();
        pending = selected;
        if (anchors) activeAnchor = selected.position;
        if (selected.kind == Kind.SWORD) {
            swordFeet = selected.entity.getY();
            confirmation.begin(tick, tick);
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
            AuraServerState.EntityObservation event = crystal == null ? null : AuraServerState.entity(client.world, crystal.getId());
            if (event != null) { revision = event.revision(); matches = event.present(); }
        } else if (pending.kind == Kind.CRYSTAL_BREAK) {
            AuraServerState.EntityObservation event = AuraServerState.entity(client.world, pending.entity.getId());
            if (event != null) { revision = event.revision(); matches = !event.present(); }
        } else {
            AuraServerState.BlockObservation event = AuraServerState.block(client.world, pending.position);
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
        AuraRules.ConfirmationResult result = confirmation.observe(revision, tick, matches);
        if (result == AuraRules.ConfirmationResult.WAITING) return false;
        if (result == AuraRules.ConfirmationResult.CONFIRMED && pending.position != null)
            AuraServerState.blockReady(client.world, pending.position);
        PENDING_LEASE.release(owner);
        if (pending.kind == Kind.ANCHOR_BREAK || result == AuraRules.ConfirmationResult.TIMED_OUT) activeAnchor = null;
        pending = null;
        if (result == AuraRules.ConfirmationResult.TIMED_OUT) nextTick = tick + 4;
        return true;
    }

    private Plan crystalPlan(MinecraftClient client, ModuleContext context, PlayerEntity target, List<PlayerEntity> enemies) {
        int sword = slot(client, null, true);
        int crystalSlot = slot(client, Items.END_CRYSTAL, false);
        AuraRules.CrystalAction action = AuraRules.crystalAction(client.player.getY(), target.getY(), false,
            sword >= 0 && swordReady(client, sword));
        if (action == AuraRules.CrystalAction.SWORD) {
            if (crystalSlot < 0) return null;
            if (client.player.getBoundingBox().squaredMagnitude(target.getEyePos()) > range(context) * range(context)) return null;
            return new Plan(Kind.SWORD, null, null, target, sword, 0, 0, 0);
        }
        if (action != AuraRules.CrystalAction.PLACE) return null;
        List<Candidate> candidates = new ArrayList<>();
        int obsidianSlot = slot(client, Items.OBSIDIAN, false);
        BlockPos origin = target.getBlockPos();
        int probes = 0;
        for (BlockPos mutable : BlockPos.iterate(origin.add(-3, -2, -3), origin.add(3, 1, 3))) {
            BlockPos pos = mutable.toImmutable();
            if (!client.world.isInBuildLimit(pos) || !client.world.isChunkLoaded(pos)) continue;
            if (!AuraServerState.blockReady(client.world, pos)) continue;
            boolean existing = crystalBase(client.world.getBlockState(pos));
            if (!existing && (obsidianSlot < 0 || crystalSlot < 0 || !client.world.getBlockState(pos).isAir())) continue;
            if (new Box(pos).squaredMagnitude(client.player.getEyePos()) > Math.pow(reach(client, context), 2)) continue;
            double estimate = estimate(client, explosion(pos, false), enemies);
            if (estimate < minimum(context)) continue;
            EndCrystalEntity crystal = existing ? crystalAt(client, pos) : null;
            if (++probes > MAX_PLACEMENT_PROBES) break;
            BlockHitResult hit = existing ? hitBlock(client, pos, reach(client, context)) : placementHit(client, pos, context, Items.OBSIDIAN);
            if (crystal == null && (crystalSlot < 0 || hit == null || !crystalSpace(client, pos))) continue;
            if (crystal != null && crystal.getBoundingBox().squaredMagnitude(client.player.getEyePos()) > Math.pow(Math.min(range(context), VanillaCombatReach.heldHitRange(client)), 2)) continue;
            offer(candidates, new Candidate(pos, hit, crystal, existing, estimate));
        }
        candidates.sort(Comparator.comparing(Candidate::existing).reversed().thenComparing(Comparator.comparingDouble(Candidate::estimate).reversed()));
        for (Candidate candidate : candidates) {
            Damage damage = damage(client, context, explosion(candidate.position, false), null, enemies);
            if (!damage.safe) continue;
            int attackSlot = client.player.getInventory().getSelectedSlot();
            return new Plan(!candidate.existing ? Kind.OBSIDIAN_PLACE : candidate.crystal == null ? Kind.CRYSTAL_PLACE : Kind.CRYSTAL_BREAK,
                candidate.position, candidate.hit, candidate.crystal, !candidate.existing ? obsidianSlot : candidate.crystal == null ? crystalSlot : attackSlot,
                damage.target, damage.self, 0);
        }
        return null;
    }

    private Plan anchorPlan(MinecraftClient client, ModuleContext context, List<PlayerEntity> enemies) {
        int anchorSlot = slot(client, Items.RESPAWN_ANCHOR, false);
        int glowstoneSlot = slot(client, Items.GLOWSTONE, false);
        int triggerSlot = triggerSlot(client);
        if (triggerSlot < 0) return null;
        if (activeAnchor != null && AuraServerState.blockReady(client.world, activeAnchor)) {
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
            if (!AuraServerState.blockReady(client.world, pos)) continue;
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
        if (!AuraServerState.blockReady(client.world, selected.position)) return false;
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
        if (target == null || target.isRemoved() || !target.isAlive() || !VanillaCombatReach.canAttack(client, target, slot,
            Math.min(4.5D, range(context)), true)) return false;
        Vec3d eyes = client.player.getEyePos();
        Vec3d direction = RotationManager.getInstance().serverRotation().direction();
        double range = Math.min(Math.min(4.5D, range(context)), VanillaCombatReach.heldHitRange(client));
        Vec3d end = eyes.add(direction.multiply(range));
        EntityHitResult hit = ProjectileUtil.raycast(client.player, eyes, end,
            client.player.getBoundingBox().stretch(direction.multiply(range)).expand(1),
            entity -> entity != client.player && entity.isAlive() && !entity.isSpectator() && entity.canHit(), range * range);
        return hit != null && hit.getEntity() == target;
    }

    private static BlockHitResult serverBlockHit(MinecraftClient client, BlockHitResult planned, ModuleContext context) {
        Vec3d eyes = client.player.getEyePos();
        Vec3d end = eyes.add(RotationManager.getInstance().serverRotation().direction().multiply(reach(client, context)));
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
        return client.world.getBlockState(pos.up()).isAir() && client.world.getBlockState(pos.up(2)).isAir()
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
        boolean safe = AuraRules.safe(best, self, client.player.getHealth() + client.player.getAbsorptionAmount(),
            minimum(context), MathHelper.clamp(context.slider("Max self damage", 6), 0, 20), friend);
        return new Damage(best, self, safe);
    }

    private double estimate(MinecraftClient client, Vec3d explosion, List<PlayerEntity> enemies) {
        double best = 0;
        for (PlayerEntity enemy : enemies) best = Math.max(best, AuraRules.damage(enemy.getEntityPos().distanceTo(explosion),
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
        double damage = AuraRules.damage(entity.getEntityPos().distanceTo(origin), power, exposure, entity.getArmor(),
            entity.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS), self ? 3 : client.world.getDifficulty().getId());
        if (self) return damage;
        int protection = 0;
        var registry = client.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            var enchants = entity.getEquippedStack(slot).get(DataComponentTypes.ENCHANTMENTS);
            if (enchants != null) protection += enchants.getLevel(registry.getOrThrow(Enchantments.PROTECTION))
                + 2 * enchants.getLevel(registry.getOrThrow(Enchantments.BLAST_PROTECTION));
        }
        return AuraRules.protectedDamage(damage, protection);
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
        return AuraRules.swordReady(MinecraftAccess.ticksSinceAttack(client.player), scratch.getValue());
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
