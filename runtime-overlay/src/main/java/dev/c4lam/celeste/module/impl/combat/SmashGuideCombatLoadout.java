package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.compat.MinecraftAccess;
import dev.c4lam.celeste.gui.model.GuiCatalog;
import dev.c4lam.celeste.gui.model.GuiModule;
import dev.c4lam.celeste.gui.model.GuiSetting;
import dev.c4lam.celeste.hud.LocalDamageEstimator;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.EntityTypeTags;
import net.minecraft.registry.tag.ItemTags;

import java.util.Arrays;
import java.util.Locale;

/** Shared weapon policy so separately enabled modules never race hotbar state. */
final class SmashGuideCombatLoadout {
    enum Weapon { CURRENT, SWORD, AXE, MACE }

    private static int lastSwapAge = Integer.MIN_VALUE / 2;
    private static long lastMaceActionNanos = Long.MIN_VALUE;

    private SmashGuideCombatLoadout() {
    }

    static int attackSlot(MinecraftClient client, LivingEntity target, Weapon requested) {
        if (client.player == null) {
            return -1;
        }
        int visible = client.player.getInventory().getSelectedSlot();

        // Shield breaking is an explicit scheduled intent. Do not force an axe into unrelated
        // attacks merely because its module is enabled (which bypassed its miss/stun policy).

        if (requested == Weapon.MACE) {
            if (enabled("Mace Swap")) {
                return bestMaceSlot(client, target);
            }
            if (isWeapon(client.player.getInventory().getStack(visible), Weapon.MACE)) {
                return visible;
            }
            return -1;
        }

        if ((requested == Weapon.SWORD || requested == Weapon.CURRENT) && enabled("Auto Sword")) {
            boolean preferAxe = toggle("Auto Sword", "Prefer axe", true)
                && target != null && target.isBlocking();
            Weapon preference;
            int slot;
            if (preferAxe) {
                int axe = bestSlot(client, Weapon.AXE);
                int sword = bestSlot(client, Weapon.SWORD);
                if (axe >= 0 && sword >= 0) {
                    preference = Weapon.AXE;
                    slot = axe;
                } else if (sword >= 0) {
                    preference = Weapon.SWORD;
                    slot = sword;
                } else {
                    preference = Weapon.AXE;
                    slot = axe;
                }
            } else {
                preference = Weapon.SWORD;
                slot = bestSlot(client, Weapon.SWORD);
                if (slot < 0) {
                    preference = Weapon.AXE;
                    slot = bestSlot(client, Weapon.AXE);
                }
            }
            return slot >= 0 && (slot == visible || swapDelayElapsed(client)) ? slot : -1;
        }

        if (requested == Weapon.AXE) {
            int axe = bestSlot(client, Weapon.AXE);
            return axe >= 0 && (axe == visible || swapDelayElapsed(client)) ? axe : -1;
        }
        if (requested == Weapon.SWORD
            && !isWeapon(client.player.getInventory().getStack(visible), Weapon.SWORD)) {
            return -1;
        }
        return visible;
    }

    /**
     * Returns the strongest hotbar instance of an explicitly requested weapon
     * without inheriting Auto Sword's normal swap-delay policy.  A stun slam is
     * a deliberate two-tick combo, so delaying its axe half would leave the
     * sequence in a half-completed state.
     */
    static int immediateWeaponSlot(MinecraftClient client, Weapon weapon) {
        return immediateWeaponSlot(client, null, weapon);
    }

    static int immediateWeaponSlot(MinecraftClient client, LivingEntity target, Weapon weapon) {
        return switch (weapon) {
            case MACE -> bestMaceSlot(client, target);
            case AXE, SWORD -> bestSlot(client, weapon);
            case CURRENT -> client.player == null ? -1 : client.player.getInventory().getSelectedSlot();
        };
    }

    static boolean runWithSlot(Object owner, MinecraftClient client, int slot, int priority,
                               boolean silent, Runnable action) {
        if (client.player == null || slot < 0 || slot > 8) {
            return false;
        }
        return SilentSlotManager.getInstance().runWithSlot(owner, slot, priority, silent, action);
    }

    static boolean silentSwap(MinecraftClient client, LivingEntity target, Weapon requested) {
        if (client.player == null) {
            return true;
        }
        // A Mace Aura/Mace Swap action must use Mace Swap's own presentation
        // policy even when the target happens to be blocking. Shield Break is
        // a separate axe intent and must not overwrite the mace setting.
        if (requested == Weapon.MACE && enabled("Mace Swap")) {
            return maceSwapSilent();
        }
        if ((requested == Weapon.SWORD || requested == Weapon.CURRENT) && enabled("Auto Sword")) {
            return toggle("Auto Sword", "Silent swap", true);
        }
        if (requested == Weapon.AXE) {
            return toggle("Shield Break", "Silent swap", true);
        }
        return true;
    }

    static boolean maceSwapSilent() {
        return toggle("Mace Swap", "Silent swap", true);
    }

    static boolean maceSwapAutoStunSlam() {
        return toggle("Mace Swap", "Auto stun slam", false);
    }

    static long maceAuraCooldownMillis() {
        return Math.max(0L, Math.round(slider("Mace Aura", "Mace cooldown", 100.0D)));
    }

    /** One shared cadence for Auto Mace and Mace Swap's Stun Slam. */
    static boolean maceCooldownElapsed() {
        long delayNanos = maceAuraCooldownMillis() * 1_000_000L;
        return lastMaceActionNanos == Long.MIN_VALUE
            || System.nanoTime() - lastMaceActionNanos >= delayNanos;
    }

    static void markMaceAction() {
        lastMaceActionNanos = System.nanoTime();
    }

    static void markSwap(MinecraftClient client, boolean swapped) {
        if (swapped && client.player != null) {
            lastSwapAge = client.player.age;
        }
    }

    static int findItem(MinecraftClient client, net.minecraft.item.Item item) {
        if (client.player == null) {
            return -1;
        }
        int visible = client.player.getInventory().getSelectedSlot();
        if (client.player.getInventory().getStack(visible).isOf(item)) {
            return visible;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (client.player.getInventory().getStack(slot).isOf(item)) {
                return slot;
            }
        }
        return -1;
    }

    static int findNonGlowstoneSlot(MinecraftClient client) {
        if (client.player == null) {
            return -1;
        }
        int visible = client.player.getInventory().getSelectedSlot();
        if (!client.player.getInventory().getStack(visible).isOf(Items.GLOWSTONE)) {
            return visible;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (!client.player.getInventory().getStack(slot).isOf(Items.GLOWSTONE)) {
                return slot;
            }
        }
        return -1;
    }

    static boolean enabled(String moduleName) {
        GuiModule module = module(moduleName);
        return module != null && module.enabled();
    }

    private static boolean maceAuraActive() {
        GuiModule maceAura = module("Mace Aura");
        if (maceAura == null || !maceAura.enabled()) {
            return false;
        }
        return maceAura.settings().stream()
            .filter(s -> s.label().equalsIgnoreCase("Mode"))
            .filter(GuiSetting.Mode.class::isInstance)
            .map(GuiSetting.Mode.class::cast)
            .map(GuiSetting.Mode::value)
            .findFirst()
            .map(mode -> "Mace only".equals(mode) || "Weapon only".equals(mode))
            .orElse(true);
    }

    private static boolean swapDelayElapsed(MinecraftClient client) {
        if (client.player == null) {
            return false;
        }
        if (client.player.age < lastSwapAge) {
            lastSwapAge = Integer.MIN_VALUE / 2;
        }
        int delay = (int) Math.round(slider("Auto Sword", "Swap delay", 1.0D));
        return client.player.age - lastSwapAge >= Math.max(0, delay);
    }

    private static int bestSlot(MinecraftClient client, Weapon weapon) {
        return bestSlot(client, weapon, false);
    }

    static int shieldBreakerSlot(MinecraftClient client) {
        return bestSlot(client, Weapon.AXE, true);
    }

    private static int bestSlot(MinecraftClient client, Weapon weapon, boolean disablingOnly) {
        if (weapon == Weapon.MACE) {
            return bestMaceSlot(client, null);
        }
        if (client.player == null) {
            return -1;
        }
        int best = -1;
        int bestScore = Integer.MIN_VALUE;
        int visible = client.player.getInventory().getSelectedSlot();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (!isWeapon(stack, weapon) || disablingOnly && !SmashGuideShieldBreakSupport.disablingAxe(stack)) {
                continue;
            }
            int score = score(stack, weapon) + (slot == visible ? 1 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = slot;
            }
        }
        return best;
    }

    /** Read-only candidate projection; never selects a slot or starts an attack. */
    private static int bestMaceSlot(MinecraftClient client, LivingEntity target) {
        if (client.player == null) return -1;
        double[] damage = new double[9];
        Arrays.fill(damage, Double.NaN);
        double armor = target == null ? 0.0 : target.getArmor();
        double toughness = target == null ? 0.0 : target.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS);
        int elapsedTicks = MinecraftAccess.ticksSinceAttack(client.player);
        boolean critical = client.player.fallDistance > 0.0 && !client.player.isOnGround()
            && !client.player.isClimbing() && !client.player.isTouchingWater()
            && !client.player.hasBlindnessEffect() && !client.player.hasVehicle() && !client.player.isSprinting();
        for (int slot = 0; slot < damage.length; slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (!stack.isOf(Items.MACE)) continue;
            double enchantmentDamage = LocalDamageEstimator.sharpnessBonus(level(stack, Enchantments.SHARPNESS));
            if (target != null && target.getType().isIn(EntityTypeTags.SENSITIVE_TO_SMITE)) {
                enchantmentDamage += 2.5 * level(stack, Enchantments.SMITE);
            }
            if (target != null && target.getType().isIn(EntityTypeTags.SENSITIVE_TO_BANE_OF_ARTHROPODS)) {
                enchantmentDamage += 2.5 * level(stack, Enchantments.BANE_OF_ARTHROPODS);
            }
            double charge = SmashGuideMaceSelection.cooldown(elapsedTicks,
                projectedAttribute(client, stack, EntityAttributes.ATTACK_SPEED));
            damage[slot] = SmashGuideMaceSelection.damage(
                projectedAttribute(client, stack, EntityAttributes.ATTACK_DAMAGE), enchantmentDamage,
                client.player.fallDistance, !client.player.isGliding(),
                level(stack, Enchantments.DENSITY), level(stack, Enchantments.BREACH),
                armor, toughness, charge, critical);
        }
        return SmashGuideMaceSelection.bestSlot(damage, client.player.getInventory().getSelectedSlot());
    }

    private static double projectedAttribute(MinecraftClient client, ItemStack stack,
                                             RegistryEntry<EntityAttribute> attribute) {
        EntityAttributeInstance source = client.player.getAttributeInstance(attribute);
        EntityAttributeInstance projected = new EntityAttributeInstance(attribute, ignored -> {});
        if (source != null) projected.setFrom(source);
        client.player.getMainHandStack().applyAttributeModifiers(EquipmentSlot.MAINHAND, (key, modifier) -> {
            if (key.equals(attribute)) projected.removeModifier(modifier);
        });
        stack.applyAttributeModifiers(EquipmentSlot.MAINHAND, (key, modifier) -> {
            if (key.equals(attribute)) projected.updateModifier(modifier);
        });
        return projected.getValue();
    }

    private static int level(ItemStack stack, RegistryKey<Enchantment> enchantment) {
        for (var entry : stack.getEnchantments().getEnchantmentEntries()) {
            if (entry.getKey().matchesKey(enchantment)) return Math.max(0, entry.getIntValue());
        }
        return 0;
    }

    private static boolean isWeapon(ItemStack stack, Weapon weapon) {
        return switch (weapon) {
            case SWORD -> stack.isIn(ItemTags.SWORDS);
            case AXE -> stack.isIn(ItemTags.AXES);
            case MACE -> stack.isOf(Items.MACE);
            case CURRENT -> !stack.isEmpty();
        };
    }

    private static int score(ItemStack stack, Weapon weapon) {
        if (stack.isOf(Items.NETHERITE_SWORD) || stack.isOf(Items.NETHERITE_AXE)) return 60;
        if (stack.isOf(Items.DIAMOND_SWORD) || stack.isOf(Items.DIAMOND_AXE)) return 50;
        if (stack.isOf(Items.IRON_SWORD) || stack.isOf(Items.IRON_AXE)) return 40;
        if (stack.isOf(Items.COPPER_SWORD) || stack.isOf(Items.COPPER_AXE)) return 35;
        if (stack.isOf(Items.STONE_SWORD) || stack.isOf(Items.STONE_AXE)) return 30;
        if (stack.isOf(Items.GOLDEN_SWORD) || stack.isOf(Items.GOLDEN_AXE)) return 20;
        if (stack.isOf(Items.WOODEN_SWORD) || stack.isOf(Items.WOODEN_AXE)) return 10;
        return 0;
    }

    private static boolean toggle(String moduleName, String label, boolean fallback) {
        GuiModule module = module(moduleName);
        if (module == null) return fallback;
        return module.settings().stream()
            .filter(setting -> setting.label().equalsIgnoreCase(label))
            .filter(GuiSetting.Toggle.class::isInstance)
            .map(GuiSetting.Toggle.class::cast)
            .map(GuiSetting.Toggle::value)
            .findFirst().orElse(fallback);
    }

    private static double slider(String moduleName, String label, double fallback) {
        GuiModule module = module(moduleName);
        if (module == null) return fallback;
        return module.settings().stream()
            .filter(setting -> setting.label().equalsIgnoreCase(label))
            .filter(GuiSetting.Slider.class::isInstance)
            .map(GuiSetting.Slider.class::cast)
            .mapToDouble(GuiSetting.Slider::value)
            .findFirst().orElse(fallback);
    }

    private static GuiModule module(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        return GuiCatalog.modules().stream()
            .filter(module -> module.name().toLowerCase(Locale.ROOT).equals(key))
            .findFirst().orElse(null);
    }
}
