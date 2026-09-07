package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.compat.MinecraftAccess;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.impl.expansion.SeeRotationsFeature;
import dev.c4lam.celeste.rotation.MoveFixMode;
import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.rotation.RotationTask;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttackRangeComponent;
import net.minecraft.component.type.PiercingWeaponComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Random;

/**
 * Silent sword aura inspired by Priv Client KillA: 360 targeting, smoothed
 * server aim with Celeste's move-fix, then vanilla doAttack when the silent
 * look is on the player.
 */
public final class SwordAuraFeature implements ModuleFeature {
    private static final int PRIORITY = 120;
    private static final double ATTACK_RANGE = 3.35D;
    private static final Random RANDOM = new Random();

    private final FriendManager friends = FriendManager.getInstance();
    private final HumanAim aim = new HumanAim();
    private Rotation silentLook;
    private int tickCounter;
    private int nextTick;
    private double cooldownThreshold = 0.88D;
    private int targetId = -1;

    private ShieldState shieldState = ShieldState.IDLE;
    private int shieldOriginalSlot = -1;
    private int shieldAxeSlot = -1;
    private int shieldTargetId = -1;
    private int shieldNextTick;

    private enum ShieldState {
        IDLE, WAIT_SWAP, WAIT_CLICK, WAIT_BACK
    }

    @Override
    public String name() {
        return "Sword Aura";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        tickCounter++;
        MinecraftClient client = context.client();
        if (client.player == null || client.world == null || client.interactionManager == null
            || client.currentScreen != null) {
            clear(client, true);
            return;
        }
        SeeRotationsFeature.syncFromAura(true, context.toggle("See Rotations", false));
        boolean flick = isFlick(context);

        if (shieldState != ShieldState.IDLE) {
            PlayerEntity shieldTarget = findPlayerById(client, shieldTargetId);
            if (shieldTarget != null && (!flick || shieldState == ShieldState.WAIT_CLICK)) {
                updateSilentLook(client, shieldTarget, context);
                requestSilentAim();
            } else if (flick) {
                endFlick();
            }
            return;
        }
        if (isShielding(client.player)) {
            endFlick();
            return;
        }

        PlayerEntity target = selectTarget(client, context);
        if (target == null) {
            targetId = -1;
            aim.reset();
            endFlick();
            cooldownThreshold = sample(context, "Swing Delay Min", 90.0D, "Swing Delay Max", 96.0D) / 100.0D;
            return;
        }
        targetId = target.getId();
        if (flick) {
            if (shouldFlickToTarget(client, target, context)) {
                updateSilentLook(client, target, context);
                requestSilentAim();
            } else {
                endFlick();
            }
        } else {
            updateSilentLook(client, target, context);
            requestSilentAim();
        }

        boolean noCooldownBreak = context.toggle("Shield Break No Cooldown", true);
        if (isShielding(target) && noCooldownBreak && startShieldBreak(client, target)) {
            scheduleAttack(context);
        }
    }

    @Override
    public void onActionTick(ModuleContext context) {
        MinecraftClient client = context.client();
        if (client.player == null || client.world == null || client.interactionManager == null
            || client.currentScreen != null) {
            return;
        }
        if (shieldState != ShieldState.IDLE) {
            tickShieldBreak(context);
            return;
        }
        if (isShielding(client.player) || tickCounter < nextTick) {
            if (isFlick(context)) {
                endFlick();
            }
            return;
        }

        PlayerEntity target = findPlayerById(client, targetId);
        if (target == null || !isValidPlayerTarget(client.player, target)
            || client.player.squaredDistanceTo(target) > context.slider("Range", 6.0D) * context.slider("Range", 6.0D)) {
            if (isFlick(context)) {
                endFlick();
            }
            return;
        }
        EntityHitResult hit = silentPlayerHit(client, ATTACK_RANGE);
        if (hit == null || !isSameAttackTarget(hit, target) || isShielding(target)) {
            if (isShielding(target)) {
                startShieldBreak(client, target);
            } else if (isFlick(context)) {
                endFlick();
            }
            return;
        }
        if (!canRunTriggerAttack(client.player,
            context.toggle("Prioritize Crits", true),
            context.toggle("Prioritize Sprint", false))) {
            if (isFlick(context)) {
                endFlick();
            }
            return;
        }
        if (client.player.getAttackCooldownProgress(0.0F) < cooldownThreshold) {
            return;
        }
        if (ActionScheduler.getInstance().hasExecuted()) {
            return;
        }
        boolean flick = isFlick(context);
        ActionScheduler.getInstance().submit(this, PRIORITY, actionClient -> {
            if (attackSilentCrosshairPlayer(actionClient, hit, target)) {
                scheduleAttack(context);
                if (flick) {
                    endFlick();
                }
            }
        });
    }

    @Override
    public void onRenderFrame(ModuleContext context) {
        if (isFlick(context)) {
            return;
        }
        MinecraftClient client = context.client();
        if (client.player == null || client.world == null || silentLook == null) {
            return;
        }
        PlayerEntity target = findPlayerById(client, targetId);
        if (target == null || !isValidPlayerTarget(client.player, target)) {
            return;
        }
        updateSilentLook(client, target, context);
        RotationManager.getInstance().hold(this, silentLook, MoveFixMode.STRICT);
    }

    @Override
    public void onDisable(ModuleContext context) {
        clear(context.client(), true);
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        clear(context.client(), true);
    }

    private void clear(MinecraftClient client, boolean restoreShield) {
        SeeRotationsFeature.syncFromAura(false, false);
        RotationManager.getInstance().release(this);
        resetShield(client == null ? null : client.player, restoreShield);
        aim.reset();
        silentLook = null;
        targetId = -1;
        nextTick = 0;
        cooldownThreshold = 0.88D;
    }

    private static boolean isFlick(ModuleContext context) {
        return "Flick".equals(context.mode("Mode", "Locked"));
    }

    private boolean shouldFlickToTarget(MinecraftClient client, PlayerEntity target, ModuleContext context) {
        if (tickCounter < nextTick || client.player.squaredDistanceTo(target) > ATTACK_RANGE * ATTACK_RANGE) {
            return false;
        }
        if (!canRunTriggerAttack(client.player,
            context.toggle("Prioritize Crits", true),
            context.toggle("Prioritize Sprint", false))) {
            return false;
        }
        return client.player.getAttackCooldownProgress(0.0F) >= cooldownThreshold - 0.02F;
    }

    private void endFlick() {
        silentLook = null;
        RotationManager.getInstance().release(this);
    }

    private void updateSilentLook(MinecraftClient client, PlayerEntity target, ModuleContext context) {
        ClientPlayerEntity self = client.player;
        if (isFlick(context)) {
            silentLook = Rotation.lookingAt(self, aimPoint(target));
            return;
        }
        if (silentLook == null) {
            silentLook = RotationManager.getInstance().isOwnedBy(this)
                ? RotationManager.getInstance().serverRotation()
                : new Rotation(self.getYaw(), self.getPitch());
        }
        silentLook = aim.step(
            self,
            target,
            silentLook,
            aim.smoothedSpeed(context.slider("Speed Min", 116.0D), context.slider("Speed Max", 200.0D)),
            context.slider("Smoothness", 100.0D),
            context.slider("Bezier Curve", 100.0D),
            context.slider("Jitter", 7.0D),
            context.slider("Target Variety", 100.0D)
        );
    }

    private void requestSilentAim() {
        if (silentLook == null) {
            return;
        }
        RotationManager.getInstance().submit(RotationTask.builder(this, silentLook)
            .priority(PRIORITY)
            .lifetimeTicks(2)
            .smoothing(180.0F, 180.0F)
            .sensitivityQuantized(false)
            .moveFix(MoveFixMode.STRICT)
            .build());
    }

    private PlayerEntity selectTarget(MinecraftClient client, ModuleContext context) {
        double range = context.slider("Range", 6.0D);
        double fov = context.slider("FOV", 180.0D);
        PlayerEntity current = findPlayerById(client, targetId);
        if (isValidPlayerTarget(client.player, current)
            && client.player.squaredDistanceTo(current) <= range * range
            && inFov(client.player, current, fov)) {
            return current;
        }
        PlayerEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (PlayerEntity candidate : client.world.getPlayers()) {
            if (!isValidPlayerTarget(client.player, candidate)
                || client.player.squaredDistanceTo(candidate) > range * range
                || !inFov(client.player, candidate, fov)) {
                continue;
            }
            double score = client.player.squaredDistanceTo(candidate);
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static boolean inFov(ClientPlayerEntity observer, Entity target, double halfFovDegrees) {
        if (halfFovDegrees >= 179.5D) {
            return true;
        }
        return Targeting.inFov(observer, target, halfFovDegrees);
    }

    private static Vec3d aimPoint(PlayerEntity target) {
        Box box = target.getBoundingBox();
        return new Vec3d(
            box.getCenter().x,
            box.minY + Math.max(0.38D, (box.maxY - box.minY) * 0.86D),
            box.getCenter().z
        );
    }

    private void scheduleAttack(ModuleContext context) {
        cooldownThreshold = sample(context, "Swing Delay Min", 90.0D, "Swing Delay Max", 96.0D) / 100.0D;
        nextTick = tickCounter + Math.max(1, (int) Math.round(
            sample(context, "Delay Ticks Min", 1.0D, "Delay Ticks Max", 2.0D)));
    }

    private static boolean canRunTriggerAttack(ClientPlayerEntity player, boolean prioritizeCrits,
                                               boolean prioritizeRunHop) {
        boolean midair = !player.isOnGround();
        return (!midair || !prioritizeCrits || canCrit(player))
            && (!midair || !prioritizeRunHop || player.isSprinting());
    }

    private static boolean canCrit(ClientPlayerEntity player) {
        return !player.isOnGround() && player.fallDistance > 0.08F
            && !player.isClimbing() && !player.isGliding();
    }

    private EntityHitResult silentPlayerHit(MinecraftClient client, double range) {
        ClientPlayerEntity self = client.player;
        if (self == null || client.world == null) {
            return null;
        }
        Rotation look = RotationManager.getInstance().isOwnedBy(this)
            ? RotationManager.getInstance().serverRotation()
            : new Rotation(self.getYaw(), self.getPitch());
        Vec3d start = self.getEyePos();
        Vec3d direction = look.direction();
        if (direction.lengthSquared() < 1.0E-8D) {
            return null;
        }
        Vec3d end = start.add(direction.multiply(range));
        HitResult block = client.world.raycast(new RaycastContext(
            start, end, RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE, self));
        double maximumDistance = block.getType() == HitResult.Type.MISS
            ? range * range : start.squaredDistanceTo(block.getPos());
        double reach = Math.sqrt(maximumDistance);
        Box search = self.getBoundingBox().stretch(direction.multiply(reach)).expand(1.0D);
        EntityHitResult hit = ProjectileUtil.raycast(
            self,
            start,
            start.add(direction.multiply(reach)),
            search,
            entity -> entity instanceof PlayerEntity player && isValidPlayerTarget(self, player),
            maximumDistance
        );
        if (hit == null) {
            return null;
        }
        AttackRangeComponent attackRange = self.getMainHandStack().get(DataComponentTypes.ATTACK_RANGE);
        if (attackRange != null && !attackRange.isWithinRange(self, hit.getPos())) {
            return null;
        }
        return hit;
    }

    private boolean attackSilentCrosshairPlayer(MinecraftClient client, EntityHitResult hit,
                                                PlayerEntity expected) {
        if (client.player == null || !isSameAttackTarget(hit, expected)
            || !isValidPlayerTarget(client.player, expected)) {
            return false;
        }
        ClientPlayerEntity self = client.player;
        ItemStack held = self.getMainHandStack();
        if (self.isSpectator() || self.hasVehicle()
            || !held.isItemEnabled(client.world.getEnabledFeatures())
            || self.isBelowMinimumAttackCharge(held, 0)) {
            return false;
        }
        AttackRangeComponent attackRange = held.get(DataComponentTypes.ATTACK_RANGE);
        if (attackRange != null && !attackRange.isWithinRange(self, hit.getPos())) {
            return false;
        }
        PiercingWeaponComponent piercing = held.get(DataComponentTypes.PIERCING_WEAPON);
        if (piercing != null && client.interactionManager.hasLimitedAttackSpeed()
            && piercingPathContainsFriend(client)) {
            return false;
        }
        if (MinecraftAccess.attackCooldown(client) > 0) {
            return false;
        }
        HitResult previous = client.crosshairTarget;
        client.crosshairTarget = hit;
        try {
            MinecraftAccess.attack(client);
        } finally {
            client.crosshairTarget = previous;
        }
        return true;
    }

    private boolean piercingPathContainsFriend(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            return true;
        }
        Rotation look = RotationManager.getInstance().isOwnedBy(this)
            ? RotationManager.getInstance().serverRotation()
            : new Rotation(client.player.getYaw(), client.player.getPitch());
        Vec3d start = client.player.getEyePos();
        Vec3d direction = look.direction();
        if (direction.lengthSquared() < 1.0E-8D) {
            return true;
        }
        double range = 6.0D;
        Vec3d end = start.add(direction.normalize().multiply(range));
        HitResult block = client.world.raycast(new RaycastContext(
            start, end, RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE, client.player));
        double maximumDistance = block.getType() == HitResult.Type.MISS
            ? range * range : start.squaredDistanceTo(block.getPos());
        for (PlayerEntity player : client.world.getPlayers()) {
            if (!player.isAlive() || player.isRemoved() || !friends.isFriend(player)) {
                continue;
            }
            Box corridor = player.getBoundingBox().expand(0.35D);
            var intersection = corridor.raycast(start, end);
            if (intersection.isPresent()
                && start.squaredDistanceTo(intersection.get()) <= maximumDistance + 1.0E-6D) {
                return true;
            }
        }
        return false;
    }

    private boolean startShieldBreak(MinecraftClient client, PlayerEntity target) {
        int axeSlot = shieldBreakerAxeSlot(client, target);
        if (axeSlot < 0) {
            return false;
        }
        shieldOriginalSlot = client.player.getInventory().getSelectedSlot();
        shieldAxeSlot = axeSlot;
        shieldTargetId = target.getId();
        shieldState = ShieldState.WAIT_SWAP;
        shieldNextTick = tickCounter + millisecondsToTicks(90.0D);
        return true;
    }

    private int shieldBreakerAxeSlot(MinecraftClient client, PlayerEntity target) {
        if (shieldState != ShieldState.IDLE
            || !client.player.getMainHandStack().isIn(ItemTags.SWORDS)
            || !isShielding(target)) {
            return -1;
        }
        return findAxeSlot(client.player);
    }

    private static int findAxeSlot(ClientPlayerEntity player) {
        int visible = player.getInventory().getSelectedSlot();
        if (player.getInventory().getStack(visible).isIn(ItemTags.AXES)) {
            return visible;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getStack(slot).isIn(ItemTags.AXES)) {
                return slot;
            }
        }
        return -1;
    }

    private void tickShieldBreak(ModuleContext context) {
        if (tickCounter < shieldNextTick) {
            return;
        }
        MinecraftClient client = context.client();
        PlayerEntity target = findPlayerById(client, shieldTargetId);
        if (target == null || !isValidPlayerTarget(client.player, target)
            || client.player.squaredDistanceTo(target) > 4.35D * 4.35D) {
            resetShield(client.player, true);
            return;
        }
        if (!isShielding(target)) {
            resetShield(client.player, true);
            return;
        }
        switch (shieldState) {
            case WAIT_SWAP -> {
                if (!SilentSlotManager.getInstance().selectVisibleSlot(client, shieldAxeSlot)) {
                    resetShield(client.player, true);
                    return;
                }
                shieldState = ShieldState.WAIT_CLICK;
                shieldNextTick = tickCounter + millisecondsToTicks(80.0D);
            }
            case WAIT_CLICK -> {
                EntityHitResult attackHit = silentPlayerHit(client, ATTACK_RANGE);
                if (!isSameAttackTarget(attackHit, target)) {
                    resetShield(client.player, true);
                    return;
                }
                if (ActionScheduler.getInstance().hasExecuted()) {
                    return;
                }
                ActionScheduler.getInstance().submit(this, PRIORITY, actionClient -> {
                    if (attackSilentCrosshairPlayer(actionClient, attackHit, target)) {
                        shieldState = ShieldState.WAIT_BACK;
                        shieldNextTick = tickCounter + millisecondsToTicks(90.0D);
                    }
                });
            }
            case WAIT_BACK -> {
                if (shieldOriginalSlot >= 0 && shieldOriginalSlot < 9) {
                    SilentSlotManager.getInstance().selectVisibleSlot(client, shieldOriginalSlot);
                }
                resetShield(client.player, false);
            }
            default -> resetShield(client.player, true);
        }
    }

    private void resetShield(ClientPlayerEntity player, boolean restore) {
        if (restore && player != null && shieldState != ShieldState.IDLE
            && shieldOriginalSlot >= 0 && shieldOriginalSlot < 9) {
            SilentSlotManager.getInstance().selectVisibleSlot(MinecraftClient.getInstance(), shieldOriginalSlot);
        }
        shieldState = ShieldState.IDLE;
        shieldOriginalSlot = -1;
        shieldAxeSlot = -1;
        shieldTargetId = -1;
        shieldNextTick = 0;
    }

    private boolean isValidPlayerTarget(ClientPlayerEntity self, PlayerEntity target) {
        return self != null && target != null && target != self && target.isAlive()
            && !target.isRemoved() && !target.isSpectator() && !target.isCreative()
            && target.isAttackable() && !friends.isFriend(target);
    }

    private static boolean isSameAttackTarget(EntityHitResult hit, PlayerEntity expected) {
        return hit != null && expected != null && hit.getEntity().getId() == expected.getId();
    }

    private static PlayerEntity findPlayerById(MinecraftClient client, int id) {
        if (client.world == null || id < 0) {
            return null;
        }
        Entity entity = client.world.getEntityById(id);
        return entity instanceof PlayerEntity player ? player : null;
    }

    private static boolean isShielding(PlayerEntity target) {
        if (target == null) {
            return false;
        }
        boolean holdingShield = target.getMainHandStack().isOf(Items.SHIELD)
            || target.getOffHandStack().isOf(Items.SHIELD);
        return holdingShield && (target.isBlocking()
            || target.isUsingItem() && target.getActiveItem().isOf(Items.SHIELD));
    }

    private static double sample(ModuleContext context, String minLabel, double minFallback,
                                 String maxLabel, double maxFallback) {
        double min = context.slider(minLabel, minFallback);
        double max = context.slider(maxLabel, maxFallback);
        double low = Math.min(min, max);
        double high = Math.max(min, max);
        return low + RANDOM.nextDouble() * Math.max(0.0D, high - low);
    }

    private static int millisecondsToTicks(double milliseconds) {
        return Math.max(1, (int) Math.ceil(Math.max(0.0D, milliseconds) / 50.0D));
    }
}
