package dev.c4lam.celeste.module.expansion.sword;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.rotation.MoveFixMode;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.rotation.RotationTask;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PiercingWeaponComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

public final class VilliansLungeFeature implements ModuleFeature {
    private static final int PRIORITY = 180;
    
    private double range = 3.5D;
    private boolean groundOnly = false;
    private boolean silentSwap = true;
    private int swapBackDelay = 2;
    private int cooldown = 0;
    private int actionKey = GLFW.GLFW_KEY_G;
    private int lungeCooldown = Integer.MIN_VALUE;
    
    private int originalSlot = -1;
    private int lungeSlot = -1;
    private ClientPlayerEntity visiblePlayer = null;
    private int restoreAtAge = Integer.MAX_VALUE;

    @Override
    public String name() {
        return "Lunge (Villians)";
    }

    @Override
    public void onEnable(ModuleContext context) {
        // No special enable logic
    }

    @Override
    public void onStartTick(ModuleContext context) {
        MinecraftClient client = context.client();
        if (!isPlayable(client) || !isActionKeyDown(client)) {
            restoreWeapon(client);
            return;
        }

        // Check cooldown
        if (client.player.age < lungeCooldown) {
            restoreWeapon(client);
            return;
        }

        // Check ground restriction
        if (groundOnly && !client.player.isOnGround()) {
            restoreWeapon(client);
            return;
        }

        // Find piercing weapon for lunge
        int piercingSlot = findPiercingSlot(client);
        if (piercingSlot < 0) {
            restoreWeapon(client);
            return;
        }

        ItemStack weapon = client.player.getInventory().getStack(piercingSlot);
        if (weapon == null || weapon.isEmpty()) {
            restoreWeapon(client);
            return;
        }

        PiercingWeaponComponent piercing = weapon.get(DataComponentTypes.PIERCING_WEAPON);
        if (piercing == null) {
            restoreWeapon(client);
            return;
        }

        // Check if weapon is ready to attack
        if (client.player.isBelowMinimumAttackCharge(weapon, 0)) {
            restoreWeapon(client);
            return;
        }

        // Find target
        LivingEntity target = findTarget(client, piercing);
        if (target == null) {
            restoreWeapon(client);
            return;
        }

        // Request rotation to target
        RotationManager.getInstance().submit(RotationTask.builder(this, 
                Rotation.lookingAt(client.player, target.getBoundingBox().getCenter()))
            .priority(PRIORITY)
            .lifetimeTicks(1)
            .smoothing(20.0F, 20.0F)
            .sensitivityQuantized(true)
            .moveFix(MoveFixMode.STRICT)
            .build());

        // Execute lunge
        ActionScheduler.getInstance().submit(this, PRIORITY, lungeClient -> {
            if (!isPlayable(lungeClient) || !isActionKeyDown(lungeClient)) {
                restoreWeapon(lungeClient);
                return;
            }

            // Re-check all conditions
            if (lungeClient.player.age < lungeCooldown + 2) { // Small buffer
                restoreWeapon(lungeClient);
                return;
            }
            
            if (groundOnly && !lungeClient.player.isOnGround()) {
                restoreWeapon(lungeClient);
                return;
            }

            int slot = findPiercingSlot(lungeClient);
            if (slot < 0) {
                restoreWeapon(lungeClient);
                return;
            }

            ItemStack stack = lungeClient.player.getInventory().getStack(slot);
            if (stack == null || stack.isEmpty()) {
                restoreWeapon(lungeClient);
                return;
            }

            PiercingWeaponComponent stackPiercing = stack.get(DataComponentTypes.PIERCING_WEAPON);
            if (stackPiercing == null) {
                restoreWeapon(lungeClient);
                return;
            }

            if (lungeClient.player.isBelowMinimumAttackCharge(stack, 0)) {
                restoreWeapon(lungeClient);
                return;
            }

            LivingEntity currentTarget = findTarget(lungeClient, stackPiercing);
            if (currentTarget == null) {
                restoreWeapon(lungeClient);
                return;
            }

            // Perform the lunge attack
            executeLunge(lungeClient, stackPiercing);
            
            // Set cooldown
            lungeCooldown = lungeClient.player.age + cooldown;
        });
    }

    @Override
    public void onActionTick(ModuleContext context) {
        // Lunge doesn't need action tick - handled in start tick
    }

    @Override
    public void onDisable(ModuleContext context) {
        restoreWeapon(context.client());
    }

    private void executeLunge(MinecraftClient client, PiercingWeaponComponent piercing) {
        PlayerInventory inventory = client.player.getInventory();
        int visibleSlot = inventory.getSelectedSlot();
        
        if (silentSwap && visibleSlot != -1) {
            // Try to use silent slot swap
            if (SilentSlotManager.getInstance().runWithSlot(this, 
                    findPiercingSlot(client), PRIORITY, true, 
                    () -> performAttack(client, piercing))) {
                // Successfully swapped and attacked
                originalSlot = visibleSlot;
                lungeSlot = findPiercingSlot(client);
                visiblePlayer = client.player;
                restoreAtAge = client.player.age + swapBackDelay;
                return;
            }
        }

        // Fallback to regular swap if silent fails or not enabled
        int original = inventory.getSelectedSlot();
        int lunge = findPiercingSlot(client);
        if (lunge >= 0 && lunge < 9) {
            inventory.setSelectedSlot(lunge);
            performAttack(client, piercing);
            inventory.setSelectedSlot(original);
            
            originalSlot = original;
            lungeSlot = lunge;
            visiblePlayer = client.player;
            restoreAtAge = client.player.age + swapBackDelay;
        }
    }

    private void performAttack(MinecraftClient client, PiercingWeaponComponent piercing) {
        client.interactionManager.attackWithPiercingWeapon(piercing);
        client.player.swingHand(Hand.MAIN_HAND);
    }

    private LivingEntity findTarget(MinecraftClient client, PiercingWeaponComponent piercing) {
        if (client.player == null || client.world == null) {
            return null;
        }

        // First try crosshair
        if (client.crosshairTarget instanceof EntityHitResult hit) {
            Entity entity = hit.getEntity();
             if (entity instanceof LivingEntity living 
                     && living != client.player 
                     && living.isAlive() 
                     && (living instanceof PlayerEntity ? !FriendManager.getInstance().isFriend((PlayerEntity) living) : true)) {
                // Check if within range
                if (client.player.squaredDistanceTo(living) <= range * range) {
                    return living;
                }
            }
        }

        // Then search nearby entities
        return client.world.getPlayers().stream()
                .filter(p -> p != client.player 
                        && p.isAlive() 
                        && !FriendManager.getInstance().isFriend(p)
                        && client.player.squaredDistanceTo(p) <= range * range)
                .findAny()
                .orElse(null);
    }

    private int findPiercingSlot(MinecraftClient client) {
        if (client.player == null) {
            return -1;
        }
        PlayerInventory inventory = client.player.getInventory();
        int selected = inventory.getSelectedSlot();
        if (isPiercing(inventory.getStack(selected))) {
            return selected;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (isPiercing(inventory.getStack(slot))) {
                return slot;
            }
        }
        return -1;
    }

    private boolean isPiercing(ItemStack stack) {
        return stack != null && !stack.isEmpty() 
                && stack.get(DataComponentTypes.PIERCING_WEAPON) != null;
    }

    private boolean isPlayable(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        return player != null && client.world != null && client.interactionManager != null
                && client.currentScreen == null && player.isAlive() 
                && !player.isSpectator() && !player.hasVehicle();
    }

    private boolean isActionKeyDown(MinecraftClient client) {
        if (actionKey < 0 || client.getWindow() == null) {
            return false;
        }
        return org.lwjgl.glfw.GLFW.glfwGetKey(client.getWindow().getHandle(), actionKey)
                == GLFW.GLFW_PRESS;
    }

    private void restoreWeapon(MinecraftClient client) {
        if (originalSlot >= 0 && originalSlot <= 8 
                && client.player == visiblePlayer 
                && client.player.getInventory().getSelectedSlot() == lungeSlot) {
            
            SilentSlotManager.getInstance().selectVisibleSlot(client, originalSlot);
        }
        originalSlot = -1;
        lungeSlot = -1;
        visiblePlayer = null;
        restoreAtAge = Integer.MAX_VALUE;
    }
}