package dev.c4lam.celeste.hud;

import dev.c4lam.celeste.action.VanillaAttackReach;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.EntityTypeTags;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Read-only inputs for the HUD's local melee model. Target equipment/type and
 * geometry are observable; target health, attributes, effects and hurt state
 * are deliberately never sampled. No damage/attack method is replayed here.
 */
record LocalAttackSnapshot(double damage, LocalDamageEstimator.Defense defense) {
    private static final EquipmentSlot[] ARMOR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
        EquipmentSlot.FEET, EquipmentSlot.BODY
    };

    static boolean eligible(MinecraftClient client, LivingEntity target) {
        if (client == null || client.player == null || client.world == null
            || client.player.isSpectator() || target == client.player || target.isRemoved()
            || target.getEntityWorld() != client.world) {
            return false;
        }
        // Existing start-of-tick reach preserves an approved spear -> mace/axe
        // action. This reads local slot intent; it never sends or changes it.
        if (!VanillaAttackReach.canAttack(client, target, actionSlot(client.player),
            VanillaAttackReach.heldHitRange(client), false)) {
            return false;
        }
        // Air swings never reach the entity-attack hook. Reject locally blocked
        // attempts too; a non-crosshair attack uses a conservative center ray.
        Vec3d contact = client.crosshairTarget instanceof EntityHitResult hit && hit.getEntity() == target
            ? hit.getPos() : target.getBoundingBox().getCenter();
        HitResult obstruction = client.world.raycast(new RaycastContext(client.player.getEyePos(), contact,
            RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, client.player));
        return obstruction.getType() == HitResult.Type.MISS;
    }

    static LocalAttackSnapshot capture(PlayerEntity player, LivingEntity target) {
        // A silent action can use a different stack from the visibly held sword.
        // serverSlotOr is the client's requested lease, not a received server value.
        int attackSlot = actionSlot(player);
        ItemStack weapon = attackSlot >= 0 && attackSlot <= 8
            ? player.getInventory().getStack(attackSlot) : player.getMainHandStack();
        double cooldown = player.getAttackCooldownProgress(0.5f);
        boolean critical = cooldown > 0.9 && player.fallDistance > 0.0 && !player.isOnGround()
            && !player.isClimbing() && !player.isTouchingWater() && !player.hasBlindnessEffect()
            && !player.hasVehicle() && !player.isSprinting();

        double enchantmentDamage = LocalDamageEstimator.sharpnessBonus(level(weapon, Enchantments.SHARPNESS));
        if (target.getType().isIn(EntityTypeTags.SENSITIVE_TO_SMITE)) {
            enchantmentDamage += 2.5 * level(weapon, Enchantments.SMITE);
        }
        if (target.getType().isIn(EntityTypeTags.SENSITIVE_TO_BANE_OF_ARTHROPODS)) {
            enchantmentDamage += 2.5 * level(weapon, Enchantments.BANE_OF_ARTHROPODS);
        }
        if (target.getType().isIn(EntityTypeTags.SENSITIVE_TO_IMPALING)) {
            enchantmentDamage += 2.5 * level(weapon, Enchantments.IMPALING);
        }
        double itemBonus = weapon.isOf(Items.MACE) && !player.isGliding()
            ? LocalDamageEstimator.maceBonus(player.fallDistance, level(weapon, Enchantments.DENSITY)) : 0.0;
        double damage = LocalDamageEstimator.outgoing(mainHandDamage(weapon), enchantmentDamage,
            itemBonus, cooldown, critical);

        int protection = 0;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            protection += level(target.getEquippedStack(slot), Enchantments.PROTECTION);
        }
        return new LocalAttackSnapshot(damage, new LocalDamageEstimator.Defense(
            wornAttribute(target, EntityAttributes.ARMOR),
            wornAttribute(target, EntityAttributes.ARMOR_TOUGHNESS),
            protection, 0.15 * level(weapon, Enchantments.BREACH)));
    }

    private static int actionSlot(PlayerEntity player) {
        return SilentSlotManager.getInstance().serverSlotOr(player.getInventory().getSelectedSlot());
    }

    private static double mainHandDamage(ItemStack weapon) {
        // Base player punch + held item's modifiers, not synced player/target attributes.
        EntityAttributeInstance scratch = new EntityAttributeInstance(EntityAttributes.ATTACK_DAMAGE, ignored -> {});
        scratch.setBaseValue(1.0);
        weapon.applyAttributeModifiers(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.equals(EntityAttributes.ATTACK_DAMAGE)) {
                scratch.updateModifier(modifier);
            }
        });
        return scratch.getValue();
    }

    private static double wornAttribute(LivingEntity target, RegistryEntry<EntityAttribute> wanted) {
        EntityAttributeInstance scratch = new EntityAttributeInstance(wanted, ignored -> {});
        scratch.setBaseValue(0.0);
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            target.getEquippedStack(slot).applyAttributeModifiers(slot, (attribute, modifier) -> {
                if (attribute.equals(wanted)) {
                    scratch.updateModifier(modifier);
                }
            });
        }
        return scratch.getValue();
    }

    private static int level(ItemStack stack, RegistryKey<Enchantment> key) {
        for (var entry : stack.getEnchantments().getEnchantmentEntries()) {
            if (entry.getKey().matchesKey(key)) {
                return Math.max(0, entry.getIntValue());
            }
        }
        return 0;
    }
}
