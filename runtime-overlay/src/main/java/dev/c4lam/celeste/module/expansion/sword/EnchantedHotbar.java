package dev.c4lam.celeste.module.expansion.sword;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;

import java.util.function.Predicate;

final class EnchantedHotbar {
    private EnchantedHotbar() {
    }

    static int breachMace(MinecraftClient client) {
        return find(client, stack -> stack.isOf(Items.MACE) && has(client, stack, Enchantments.BREACH));
    }

    static int lungeSpear(MinecraftClient client) {
        return find(client, stack -> stack.isIn(ItemTags.SPEARS) && has(client, stack, Enchantments.LUNGE));
    }

    static int bestSword(MinecraftClient client) {
        if (client.player == null) {
            return -1;
        }
        int visible = client.player.getInventory().getSelectedSlot();
        int bestSlot = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (!stack.isIn(ItemTags.SWORDS)) {
                continue;
            }
            int score = swordScore(stack) + (slot == visible ? 1 : 0);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private static int find(MinecraftClient client, Predicate<ItemStack> predicate) {
        if (client.player == null) {
            return -1;
        }
        int visible = client.player.getInventory().getSelectedSlot();
        if (predicate.test(client.player.getInventory().getStack(visible))) {
            return visible;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (predicate.test(client.player.getInventory().getStack(slot))) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean has(MinecraftClient client, ItemStack stack,
                               RegistryKey<Enchantment> enchantment) {
        if (client.world == null || stack.isEmpty()) {
            return false;
        }
        ItemEnchantmentsComponent component = stack.get(DataComponentTypes.ENCHANTMENTS);
        if (component == null) {
            return false;
        }
        Registry<Enchantment> registry = client.world.getRegistryManager()
            .getOrThrow(RegistryKeys.ENCHANTMENT);
        RegistryEntry<Enchantment> entry = registry.getOrThrow(enchantment);
        return component.getLevel(entry) > 0;
    }

    private static int swordScore(ItemStack stack) {
        if (stack.isOf(Items.NETHERITE_SWORD)) return 70;
        if (stack.isOf(Items.DIAMOND_SWORD)) return 60;
        if (stack.isOf(Items.IRON_SWORD)) return 50;
        if (stack.isOf(Items.COPPER_SWORD)) return 45;
        if (stack.isOf(Items.STONE_SWORD)) return 40;
        if (stack.isOf(Items.GOLDEN_SWORD)) return 30;
        if (stack.isOf(Items.WOODEN_SWORD)) return 20;
        return 0;
    }
}
