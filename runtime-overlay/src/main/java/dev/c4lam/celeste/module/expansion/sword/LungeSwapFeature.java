package dev.c4lam.celeste.module.expansion.sword;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.rotation.MoveFixMode;
import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.rotation.RotationTask;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PiercingWeaponComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

/**
 * Held-key Lunge macro rebuilt from observed behavior, not source code. It
 * performs at most one ordinary vanilla jab per START tick and never crafts a
 * packet or repeats an attack inside one tick.
 */
public final class LungeSwapFeature extends LungeSwapInterceptFeature {
    private static final int PRIORITY = 200;

    private double range = 3.35D;
    private boolean groundOnly;
    private boolean silentSwap = true;
    private int swapBackDelay = 1;
    private int cooldown;
    private int actionKey = GLFW.GLFW_KEY_G;
    private int nextActionAge = Integer.MIN_VALUE / 2;
    private int restoreAtAge = Integer.MAX_VALUE;
    private int originalSlot = -1;
    private int lungeSlot = -1;
    private ClientPlayerEntity visiblePlayer;
    private int lastObservedAge = Integer.MIN_VALUE;

    @Override
    public String name() {
        return "Lunge Swap";
    }

    @Override
    protected void refresh(ModuleContext context) {
        range = Math.max(2.0D, Math.min(4.5D, context.slider("Range", 3.35D)));
        groundOnly = context.toggle("Ground only", false);
        silentSwap = context.toggle("Silent swap", true);
        swapBackDelay = Math.max(1, (int) Math.round(context.slider("Swap back delay", 1.0D)));
        cooldown = Math.max(0, (int) Math.round(context.slider("Cooldown", 0.0D)));
        actionKey = context.keybind("Action");
    }

    @Override
    protected void tick(ModuleContext context) {
        MinecraftClient client = context.client();
        if (client.player == null || client.world == null || client.interactionManager == null) {
            clearState();
            return;
        }
        if (client.player.age < lastObservedAge) {
            restoreVisible(client);
            nextActionAge = Integer.MIN_VALUE / 2;
        }
        lastObservedAge = client.player.age;

        if (!playable(client) || !actionDown(client)) {
            restoreVisible(client);
            nextActionAge = Integer.MIN_VALUE / 2;
            return;
        }
        if (originalSlot >= 0) {
            if (client.player.getInventory().getSelectedSlot() != lungeSlot) {
                clearVisibleState();
                nextActionAge = client.player.age + cooldown;
            } else if (client.player.age >= restoreAtAge) {
                restoreVisible(client);
                nextActionAge = client.player.age + cooldown;
            }
            return;
        }
        if (client.player.age < nextActionAge || (groundOnly && !client.player.isOnGround())) {
            return;
        }

        int slot = findPiercingSlot(client);
        if (slot < 0) {
            return;
        }
        ItemStack weapon = client.player.getInventory().getStack(slot);

        LivingEntity directTarget = directTarget(client);
        if (client.crosshairTarget instanceof EntityHitResult && directTarget == null) {
            return;
        }
        PiercingWeaponComponent piercing = weapon.get(DataComponentTypes.PIERCING_WEAPON);
        if (piercing == null || piercingPathContainsFriend(client, range)) {
            return;
        }

        Plan plan = new Plan(client.world, client.player,
            directTarget == null ? -1 : directTarget.getId(), slot, silentSwap, range);
        RotationManager.getInstance().submit(RotationTask.builder(this,
                new Rotation(client.player.getYaw(), client.player.getPitch()))
            .priority(PRIORITY).lifetimeTicks(1).smoothing(180.0F, 180.0F)
            .sensitivityQuantized(true).moveFix(MoveFixMode.STRICT).build());
        ActionScheduler.getInstance().submit(this, PRIORITY, actionClient -> execute(actionClient, plan));
    }

    private void execute(MinecraftClient client, Plan plan) {
        if (!playable(client) || client.world != plan.world || client.player != plan.player
            || !actionDown(client) || (groundOnly && !client.player.isOnGround())
            || client.player.isUsingItem() || client.interactionManager.isBreakingBlock()
            || !RotationManager.getInstance().isOwnedBy(this)
            || plan.slot < 0 || plan.slot > 8) {
            return;
        }
        ItemStack stack = client.player.getInventory().getStack(plan.slot);
        PiercingWeaponComponent piercing = stack.get(DataComponentTypes.PIERCING_WEAPON);
        if (piercing == null) {
            return;
        }

        LivingEntity target = plan.targetId < 0 ? null : resolveTarget(client, plan.targetId, plan.range);
        if (plan.targetId >= 0 && target == null) {
            return;
        }
        if ((target == null && client.crosshairTarget instanceof EntityHitResult)
            || piercingPathContainsFriend(client, plan.range)) {
            return;
        }

        PlayerInventory inventory = client.player.getInventory();
        int visible = inventory.getSelectedSlot();
        Runnable jab = () -> {
            // This is MinecraftClient.doAttack's 1.21.11 piercing branch: a
            // single vanilla STAB action whose server-side corridor/range is
            // defined by the held item's components.
            client.interactionManager.attackWithPiercingWeapon(piercing);
            client.player.swingHand(Hand.MAIN_HAND);
        };

        if (plan.silent || visible == plan.slot) {
            if (visible == plan.slot) {
                jab.run();
            } else if (!SilentSlotManager.getInstance()
                .runWithSlot(this, plan.slot, PRIORITY, true, jab)) {
                return;
            }
            nextActionAge = client.player.age + cooldown;
            return;
        }

        if (!SilentSlotManager.getInstance().selectVisibleSlot(client, plan.slot)) {
            return;
        }
        originalSlot = visible;
        lungeSlot = plan.slot;
        visiblePlayer = client.player;
        restoreAtAge = client.player.age + swapBackDelay;
        jab.run();
    }

    private LivingEntity directTarget(MinecraftClient client) {
        return LungeSwapSwordExpansionTargets.crosshairLiving(client, range);
    }

    private static LivingEntity resolveTarget(MinecraftClient client, int entityId, double range) {
        Entity entity = client.world == null ? null : client.world.getEntityById(entityId);
        if (!(entity instanceof LivingEntity living)
            || !(client.crosshairTarget instanceof EntityHitResult hit)
            || hit.getEntity() != living
            || !LungeSwapSwordExpansionTargets.validLiving(client, living, range, true)) {
            return null;
        }
        return living;
    }

    private static int findPiercingSlot(MinecraftClient client) {
        if (client.player == null) {
            return -1;
        }
        PlayerInventory inventory = client.player.getInventory();
        int selected = inventory.getSelectedSlot();
        if (isPiercing(inventory.getStack(selected))) {
            return selected;
        }
        int enchanted = LungeSwapEnchantedHotbar.lungeSpear(client);
        if (enchanted >= 0) {
            return enchanted;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (isPiercing(inventory.getStack(slot))) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean isPiercing(ItemStack stack) {
        return stack != null && !stack.isEmpty()
            && stack.get(DataComponentTypes.PIERCING_WEAPON) != null;
    }

    private static boolean piercingPathContainsFriend(MinecraftClient client, double range) {
        if (client.player == null || client.world == null) {
            return true;
        }
        Vec3d start = client.player.getEyePos();
        Vec3d direction = client.player.getRotationVec(1.0F);
        if (direction.lengthSquared() < 1.0E-8D) {
            return true;
        }
        Vec3d end = start.add(direction.normalize().multiply(range));
        HitResult block = client.world.raycast(new RaycastContext(
            start, end, RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE, client.player));
        double maximumDistance = block.getType() == HitResult.Type.MISS
            ? range * range : start.squaredDistanceTo(block.getPos());
        FriendManager friends = FriendManager.getInstance();
        for (var player : client.world.getPlayers()) {
            if (!player.isAlive() || player.isRemoved() || !friends.isFriend(player)) {
                continue;
            }
            Box corridorTarget = player.getBoundingBox().expand(0.35D);
            var intersection = corridorTarget.raycast(start, end);
            if (intersection.isPresent()
                && start.squaredDistanceTo(intersection.get()) <= maximumDistance + 1.0E-6D) {
                return true;
            }
        }
        return false;
    }

    private boolean actionDown(MinecraftClient client) {
        return actionKey >= 0 && client.getWindow() != null
            && org.lwjgl.glfw.GLFW.glfwGetKey(client.getWindow().getHandle(), actionKey)
            == GLFW.GLFW_PRESS;
    }

    private static boolean playable(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        return player != null && client.world != null && client.interactionManager != null
            && client.currentScreen == null && player.isAlive() && !player.isSpectator()
            && !player.hasVehicle() && !player.isGliding() && !player.isTouchingWater();
    }

    @Override
    protected void disabled(ModuleContext context) {
        restoreVisible(context.client());
        clearState();
    }

    private void restoreVisible(MinecraftClient client) {
        if (originalSlot >= 0 && originalSlot <= 8 && client != null
            && client.player == visiblePlayer
            && client.player.getInventory().getSelectedSlot() == lungeSlot) {
            SilentSlotManager.getInstance().selectVisibleSlot(client, originalSlot);
        }
        clearVisibleState();
    }

    private void clearVisibleState() {
        originalSlot = -1;
        lungeSlot = -1;
        visiblePlayer = null;
        restoreAtAge = Integer.MAX_VALUE;
    }

    private void clearState() {
        clearVisibleState();
        nextActionAge = Integer.MIN_VALUE / 2;
        lastObservedAge = Integer.MIN_VALUE;
    }

    private record Plan(Object world, ClientPlayerEntity player, int targetId,
                        int slot, boolean silent, double range) {
    }
}
