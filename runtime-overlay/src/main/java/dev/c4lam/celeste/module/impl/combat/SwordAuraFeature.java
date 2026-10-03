package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.action.GameplayFoundation;
import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.compat.MinecraftAccess;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.impl.expansion.SeeRotationsFeature;
import dev.c4lam.celeste.rotation.MoveFixMode;
import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.rotation.RotationTask;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttackRangeComponent;
import net.minecraft.component.type.PiercingWeaponComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
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
    private static final Random RANDOM = new Random();

    private final FriendManager friends = FriendManager.getInstance();
    private final HumanAim aim = new HumanAim();
    private final SwordAuraShieldBreakSupport shieldBreak = new SwordAuraShieldBreakSupport();
    private Rotation silentLook;
    private int tickCounter;
    private int nextTick;
    private double cooldownThreshold = Double.NaN;
    private int targetId = -1;

    private ClientPlayerEntity shieldPlayer;
    private boolean shieldBreakReady;

    @Override
    public String name() {
        return "Sword Aura";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        tickCounter++;
        shieldBreakReady = false;
        MinecraftClient client = context.client();
        if (client.player == null || client.world == null || client.interactionManager == null
            || client.currentScreen != null) {
            clear();
            return;
        }
        SeeRotationsFeature.syncFromAura(true, context.toggle("See Rotations", false));
        if (!Double.isFinite(cooldownThreshold)) {
            cooldownThreshold = sample(context, "Swing Delay Min", 90.0D, "Swing Delay Max", 96.0D) / 100.0D;
        }
        boolean flick = isFlick(context);

        if (shieldPlayer != client.player) {
            shieldBreak.reset();
            shieldPlayer = client.player;
        }
        if (isShielding(client.player)) {
            shieldBreak.reset();
            endFlick();
            return;
        }

        PlayerEntity target = selectTarget(client, context);
        if (target == null) {
            shieldBreak.reset();
            targetId = -1;
            aim.reset();
            endFlick();
            cooldownThreshold = sample(context, "Swing Delay Min", 90.0D, "Swing Delay Max", 96.0D) / 100.0D;
            return;
        }
        if (targetId != target.getId()) {
            shieldBreak.reset();
        }
        targetId = target.getId();
        // Observe the blocking episode once per tick, even while the ordinary
        // sword cooldown is still charging. Flick may then aim for an axe hit.
        if (client.player.getMainHandStack().isIn(ItemTags.SWORDS)) {
            shieldBreakReady = shieldBreak.ready(context, target) && shieldCooldownReady(context);
        } else {
            shieldBreak.reset();
        }
        if (flick) {
            boolean shieldFlick = shieldBreakReady
                && isSameAttackTarget(playerHitAt(client,
                    Rotation.lookingAt(client.player, aimPoint(target))), target);
            if (shieldFlick || shouldFlickToTarget(client, target, context)) {
                updateSilentLook(client, target, context);
                requestSilentAim(context);
            } else {
                endFlick();
            }
        } else {
            updateSilentLook(client, target, context);
            requestSilentAim(context);
        }
    }

    @Override
    public void onActionTick(ModuleContext context) {
        MinecraftClient client = context.client();
        if (client.player == null || client.world == null || client.interactionManager == null
            || client.currentScreen != null) {
            return;
        }
        if (isShielding(client.player)) {
            shieldBreak.reset();
            shieldBreakReady = false;
            if (isFlick(context)) {
                endFlick();
            }
            return;
        }

        PlayerEntity target = findPlayerById(client, targetId);
        if (target == null || !isValidPlayerTarget(client.player, target)
            || client.player.squaredDistanceTo(target) > context.slider("Range", 6.0D) * context.slider("Range", 6.0D)) {
            shieldBreak.reset();
            shieldBreakReady = false;
            if (isFlick(context)) {
                endFlick();
            }
            return;
        }
        if (SwordAuraShieldBreakSupport.blockingFrom(client, target)) {
            submitShieldBreak(context, target);
            return;
        }
        shieldBreak.reset();
        shieldBreakReady = false;
        if (tickCounter < nextTick) {
            if (isFlick(context)) {
                endFlick();
            }
            return;
        }
        EntityHitResult hit = silentPlayerHit(client);
        if (hit == null || !isSameAttackTarget(hit, target)) {
            if (isFlick(context)) {
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
            if (attackSilentCrosshairPlayer(actionClient, target)) {
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
        RotationManager.getInstance().hold(this, silentLook, moveFixMode(context));
    }

    @Override
    public void onDisable(ModuleContext context) {
        clear();
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        clear();
    }

    private void clear() {
        SeeRotationsFeature.syncFromAura(false, false);
        GameplayFoundation.releaseOwner(this);
        shieldBreak.reset();
        shieldPlayer = null;
        shieldBreakReady = false;
        aim.reset();
        silentLook = null;
        targetId = -1;
        nextTick = 0;
        cooldownThreshold = Double.NaN;
    }

    private static boolean isFlick(ModuleContext context) {
        return "Flick".equals(context.mode("Mode", "Locked"));
    }

    private boolean shouldFlickToTarget(MinecraftClient client, PlayerEntity target, ModuleContext context) {
        if (tickCounter < nextTick) {
            return false;
        }
        if (!canRunTriggerAttack(client.player,
            context.toggle("Prioritize Crits", true),
            context.toggle("Prioritize Sprint", false))) {
            return false;
        }
        if (client.player.getAttackCooldownProgress(0.0F) < cooldownThreshold - 0.02F) {
            return false;
        }
        // Flick only starts when the prospective snap would hit; it must not depend on owning the old look.
        Rotation prospective = Rotation.lookingAt(client.player, aimPoint(target));
        return isSameAttackTarget(playerHitAt(client, prospective), target);
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

    private void requestSilentAim(ModuleContext context) {
        if (silentLook == null) {
            return;
        }
        RotationManager.getInstance().submit(RotationTask.builder(this, silentLook)
            .priority(PRIORITY)
            .lifetimeTicks(2)
            .smoothing(180.0F, 180.0F)
            .sensitivityQuantized(false)
            .moveFix(moveFixMode(context))
            .build());
    }

    private static MoveFixMode moveFixMode(ModuleContext context) {
        return "Anarchy".equals(context.mode("Movefix mode", "Default"))
            ? MoveFixMode.ANARCHY : MoveFixMode.STRICT;
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
        return SwordAuraSharedTargeting.inFov(observer, target, halfFovDegrees);
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

    private EntityHitResult silentPlayerHit(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        if (self == null || client.world == null || client.interactionManager == null) {
            return null;
        }
        Rotation look = RotationManager.getInstance().isOwnedBy(this)
            ? RotationManager.getInstance().serverRotation()
            : new Rotation(self.getYaw(), self.getPitch());
        return playerHitAt(client, look);
    }

    private EntityHitResult playerHitAt(MinecraftClient client, Rotation look) {
        ClientPlayerEntity self = client.player;
        if (self == null || client.world == null || client.interactionManager == null) {
            return null;
        }
        // Combat authority always originates at the real player. A detached
        // Freecam entity is presentation only and must never extend or move reach.
        return SwordAuraTargeting.playerHit(self, self, look.yaw(), look.pitch(),
            target -> isValidPlayerTarget(self, target));
    }

    private boolean attackSilentCrosshairPlayer(MinecraftClient client, PlayerEntity expected) {
        if (client.player == null || client.world == null || client.interactionManager == null
            || client.currentScreen != null
            || !isValidPlayerTarget(client.player, expected)) {
            return false;
        }
        // Re-query when the scheduler executes: movement, a slot change or a new blocker invalidates an older hit.
        EntityHitResult hit = silentPlayerHit(client);
        if (!isSameAttackTarget(hit, expected)) {
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

    private boolean shieldCooldownReady(ModuleContext context) {
        return context.toggle("Shield Break No Cooldown", true)
            || tickCounter >= nextTick
                && context.client().player.getAttackCooldownProgress(0.0F) >= cooldownThreshold;
    }

    private void submitShieldBreak(ModuleContext context, PlayerEntity target) {
        MinecraftClient client = context.client();
        boolean flick = isFlick(context);
        if (!shieldBreakReady || !shieldHitReady(context, target)
            || ActionScheduler.getInstance().hasExecuted()) {
            if (flick) {
                endFlick();
            }
            return;
        }
        ActionScheduler.getInstance().submit(this, PRIORITY, actionClient -> {
            // Winning the action does not retain an old hit or slot choice.
            // The dedicated axe path never enters physical Mace Swap policy.
            if (actionClient == client && shieldHitReady(context, target)
                && shieldBreak.attempt(this, context, target, PRIORITY,
                    context.toggle("Shield silent swap", true))) {
                scheduleAttack(context);
            }
            if (flick) {
                endFlick();
            }
        });
    }

    private boolean shieldHitReady(ModuleContext context, PlayerEntity target) {
        MinecraftClient client = context.client();
        if (client.player == null || client.player != shieldPlayer || client.world == null
            || client.interactionManager == null || client.currentScreen != null
            || target.getId() != targetId || !isValidPlayerTarget(client.player, target)
            || !client.player.getMainHandStack().isIn(ItemTags.SWORDS)
            || !SwordAuraShieldBreakSupport.blockingFrom(client, target) || !shieldCooldownReady(context)) {
            return false;
        }
        double range = context.slider("Range", 6.0D);
        int axeSlot = SwordAuraShieldBreakSupport.axeSlot(client);
        return client.player.squaredDistanceTo(target) <= range * range
            && isSameAttackTarget(silentPlayerHit(client), target)
            && axeSlot >= 0 && SwordAuraVanillaCombatReach.canAttack(client, target, axeSlot, range, true);
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

}
