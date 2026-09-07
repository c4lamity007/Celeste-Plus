package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.compat.MinecraftAccess;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.silent.SilentSlotManager;
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
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Random;

/**
 * Priv Client trigger bot: vanilla-crosshair attacks with sampled swing delay,
 * optional crit/sprint waits, and an axe shield-break sequence.
 */
public final class TBOTFeature implements ModuleFeature {
    private static final int PRIORITY = 100;
    private static final Random RANDOM = new Random();
    private static final double DEFAULT_SWING_MIN = 90.0D;
    private static final double DEFAULT_SWING_MAX = 96.0D;

    private final FriendManager friends = FriendManager.getInstance();
    private int tickCounter;
    private double cooldownThreshold = 0.88D;
    private int hoverTargetId = -1;
    private int hoverTicks;
    private EntityHitResult cachedHit;
    private int cachedSlot = -1;
    private float cachedYaw;
    private float cachedPitch;
    private Entity cachedCamera;
    private boolean cacheReady;

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
        return "Trigger Bot";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        tickCounter++;
        cacheReady = false;
        cachedHit = null;
        MinecraftClient client = context.client();
        if (client.player == null || client.world == null || client.interactionManager == null
            || client.currentScreen != null) {
            resetShield(client.player, true);
            return;
        }

        if (shieldState != ShieldState.IDLE) {
            return;
        }

        if (isShielding(client.player)) {
            return;
        }

        EntityHitResult attackHit = freshVanillaAttackPlayerHit(client);
        PlayerEntity target = attackHit == null ? null : playerFrom(attackHit);
        updateHover(target);
        boolean noCooldownBreak = context.toggle("Shield Break No Cooldown", true);
        if (isShielding(target) && noCooldownBreak && hoveredLongEnough(context)
            && startShieldBreak(client, target)) {
            scheduleAttack(context);
            return;
        }
        if (target == null) {
            cooldownThreshold = sample(context, "Swing Delay Min", DEFAULT_SWING_MIN,
                "Swing Delay Max", DEFAULT_SWING_MAX) / 100.0D;
            return;
        }
        if (isShielding(target) && hoveredLongEnough(context) && startShieldBreak(client, target)) {
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
        if (isShielding(client.player)) {
            return;
        }

        EntityHitResult attackHit = freshVanillaAttackPlayerHit(client);
        PlayerEntity target = attackHit == null ? null : playerFrom(attackHit);
        if (target == null || isShielding(target) || !hoveredLongEnough(context)) {
            return;
        }
        if (!canRunTriggerAttack(client.player,
            context.toggle("Prioritize Crits", true),
            context.toggle("Prioritize Sprint", false))) {
            return;
        }
        if (client.player.getAttackCooldownProgress(0.0F) < cooldownThreshold) {
            return;
        }
        if (ActionScheduler.getInstance().hasExecuted()) {
            return;
        }
        ActionScheduler.getInstance().submit(this, PRIORITY, actionClient -> {
            if (attackVanillaCrosshairPlayer(actionClient, attackHit, target)) {
                scheduleAttack(context);
            }
        });
    }

    @Override
    public void onDisable(ModuleContext context) {
        resetShield(context.client().player, true);
        cooldownThreshold = 0.88D;
        hoverTargetId = -1;
        hoverTicks = 0;
        cacheReady = false;
        cachedHit = null;
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        onDisable(context);
    }

    private void scheduleAttack(ModuleContext context) {
        cooldownThreshold = sample(context, "Swing Delay Min", DEFAULT_SWING_MIN,
            "Swing Delay Max", DEFAULT_SWING_MAX) / 100.0D;
        hoverTicks = 0;
    }

    private void updateHover(PlayerEntity target) {
        if (target == null) {
            hoverTargetId = -1;
            hoverTicks = 0;
            return;
        }
        if (target.getId() != hoverTargetId) {
            hoverTargetId = target.getId();
            hoverTicks = 1;
            return;
        }
        hoverTicks++;
    }

    private boolean hoveredLongEnough(ModuleContext context) {
        int needed = Math.max(0, (int) Math.round(context.slider("Reaction Time", 0.0D)));
        return needed <= 0 || hoverTicks >= needed;
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
                EntityHitResult attackHit = freshVanillaAttackPlayerHit(client);
                if (!isSameAttackTarget(attackHit, target)) {
                    resetShield(client.player, true);
                    return;
                }
                if (ActionScheduler.getInstance().hasExecuted()) {
                    return;
                }
                ActionScheduler.getInstance().submit(this, PRIORITY, actionClient -> {
                    if (attackVanillaCrosshairPlayer(actionClient, attackHit, target)) {
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

    private EntityHitResult freshVanillaAttackPlayerHit(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        if (self == null || client.world == null || client.interactionManager == null) {
            return null;
        }
        int selectedSlot = self.getInventory().getSelectedSlot();
        Entity camera = client.getCameraEntity() == null ? self : client.getCameraEntity();
        float cameraYaw = camera.getYaw();
        float cameraPitch = camera.getPitch();
        if (cacheReady && selectedSlot == cachedSlot && cachedCamera == camera
            && cachedYaw == cameraYaw && cachedPitch == cameraPitch) {
            return cachedHit;
        }

        HitResult result = self.getCrosshairTarget(1.0F, camera);
        EntityHitResult hit = result instanceof EntityHitResult entityHit
            && entityHit.getEntity() instanceof PlayerEntity target
            && isValidPlayerTarget(self, target) ? entityHit : null;
        if (hit != null) {
            AttackRangeComponent attackRange = self.getMainHandStack().get(DataComponentTypes.ATTACK_RANGE);
            if (attackRange != null && !attackRange.isWithinRange(self, hit.getPos())) {
                hit = null;
            }
        }
        cacheReady = true;
        cachedSlot = selectedSlot;
        cachedCamera = camera;
        cachedYaw = cameraYaw;
        cachedPitch = cameraPitch;
        cachedHit = hit;
        return hit;
    }

    private boolean attackVanillaCrosshairPlayer(MinecraftClient client, EntityHitResult hit,
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
        Vec3d start = client.player.getEyePos();
        Vec3d direction = client.player.getRotationVec(1.0F);
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

    private boolean isValidPlayerTarget(ClientPlayerEntity self, PlayerEntity target) {
        return self != null && target != null && target != self && target.isAlive()
            && !target.isRemoved() && !target.isSpectator() && !target.isCreative()
            && target.isAttackable() && !friends.isFriend(target);
    }

    private static boolean isSameAttackTarget(EntityHitResult hit, PlayerEntity expected) {
        return hit != null && expected != null && hit.getEntity().getId() == expected.getId();
    }

    private static PlayerEntity playerFrom(EntityHitResult hit) {
        return hit.getEntity() instanceof PlayerEntity player ? player : null;
    }

    private static PlayerEntity findPlayerById(MinecraftClient client, int id) {
        if (client.world == null) {
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
