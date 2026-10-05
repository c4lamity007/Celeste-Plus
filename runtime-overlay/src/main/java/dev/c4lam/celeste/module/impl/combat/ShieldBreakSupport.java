package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.ThreadLocalRandom;

/** Shared Shield Break / Sword Aura behavior; never enters the physical Mace Swap click hook. */
final class ShieldBreakSupport {
    private final ShieldBreakPolicy policy = new ShieldBreakPolicy();
    private ClientPlayerEntity player;
    private ClientWorld world;

    boolean ready(ModuleContext context, LivingEntity target) {
        MinecraftClient client = context.client();
        if (client == null) { reset(); return false; }
        if (client.player != player || client.world != world) {
            reset();
            player = client.player;
            world = client.world;
        }
        if (!blockingFrom(client, target) || axeSlot(client) < 0) {
            policy.reset();
            return false;
        }
        return policy.ready(target.getId(), ActionScheduler.getInstance().tickNumber(),
            context.slider("Stun delay", 0));
    }

    /** Run only inside the owning scheduler callback, after its exact target-ray validation. */
    boolean attempt(Object owner, ModuleContext context, LivingEntity target, int priority, boolean silent) {
        MinecraftClient client = context.client();
        long tick = ActionScheduler.getInstance().tickNumber();
        if (client == null || client.player != player || client.world != world || target == null
            || !policy.readyFor(target.getId(), tick) || !blockingFrom(client, target)
            || !RotationManager.getInstance().isOwnedBy(owner)) return false;
        int slot = axeSlot(client);
        if (slot < 0 || !validHit(client, target, slot)
            || (SilentSlotManager.getInstance().isActive() && !SilentSlotManager.getInstance().isOwnedBy(owner))) return false;

        int clicks = ShieldBreakPolicy.clicks(context.slider("Stun %", 100), context.slider("Miss %", 0),
            () -> ThreadLocalRandom.current().nextDouble());
        if (clicks == 0) {
            policy.attempted(tick);
            return false;
        }
        int[] sent = {0};
        CombatLoadout.runWithSlot(owner, client, slot, priority, silent, () -> {
            ItemStack axe = client.player.getInventory().getStack(slot);
            // Both clicks stay on this axe, target and tick. Do not wait for remote shield metadata
            // between them: the requested stun is the rapid second hit, not a next-tick combo.
            sent[0] = ShieldBreakPolicy.runClicks(clicks,
                () -> client.player == player && client.world == world && validHit(client, target, slot)
                    && ActionScheduler.getInstance().tickNumber() == tick
                    && RotationManager.getInstance().isOwnedBy(owner)
                    && client.player.getMainHandStack() == axe,
                () -> {
                    client.interactionManager.attackEntity(client.player, target);
                    client.player.swingHand(Hand.MAIN_HAND);
                });
        });
        if (sent[0] > 0) policy.attempted(tick);
        return sent[0] > 0; // Submitted locally, not a claim that the server accepted either hit.
    }

    private static boolean validHit(MinecraftClient client, LivingEntity target, int slot) {
        return client.player != null && !client.player.isUsingItem() && client.currentScreen == null
            && Targeting.validLivingTarget(client, target, FriendManager.getInstance(), VanillaCombatReach.heldHitRange(client), true)
            && disablingAxe(client.player.getInventory().getStack(slot))
            && VanillaCombatReach.canAttack(client, target, slot, VanillaCombatReach.heldHitRange(client), true);
    }

    static int axeSlot(MinecraftClient client) {
        return client == null || client.player == null ? -1 : CombatLoadout.shieldBreakerSlot(client);
    }

    static boolean disablingAxe(ItemStack stack) {
        var weapon = stack.get(DataComponentTypes.WEAPON);
        return stack.isIn(net.minecraft.registry.tag.ItemTags.AXES) && weapon != null
            && weapon.disableBlockingForSeconds() > 0;
    }

    /** Pure client observation of the 1.21.11 block-delay, direction and item component rules. */
    static boolean blockingFrom(MinecraftClient client, LivingEntity target) {
        if (client == null || client.player == null || client.world == null || client.interactionManager == null
            || !client.player.isAlive() || client.player.isUsingItem() || client.player.hasVehicle()
            || target == null || target == client.player || !target.isAlive() || target.isRemoved()
            || target.getEntityWorld() != client.world) return false;
        ItemStack blocking = target.getBlockingItem(); // Includes the item's actual raise delay.
        if (blocking == null) return false;
        var component = blocking.get(DataComponentTypes.BLOCKS_ATTACKS);
        if (component == null || component.disableCooldownScale() <= 0) return false;
        var source = client.player.getDamageSources().playerAttack(client.player);
        if (component.bypassedBy().filter(source::isIn).isPresent()) return false;
        Vec3d facing = target.getRotationVector(0, target.getHeadYaw());
        Vec3d incoming = client.player.getEntityPos().subtract(target.getEntityPos()).multiply(1, 0, 1).normalize();
        double angle = Math.acos(MathHelper.clamp(facing.dotProduct(incoming), -1, 1));
        // Unlike LivingEntity.getDamageBlockedAmount, this component method has no hit side effects.
        return component.getDamageReductionAmount(source, 1, angle) > 0;
    }

    void reset() {
        policy.reset();
        player = null;
        world = null;
    }
}
