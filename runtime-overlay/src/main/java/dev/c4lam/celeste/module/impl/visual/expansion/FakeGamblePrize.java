package dev.c4lam.celeste.module.impl.visual.expansion;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import java.util.UUID;

/** Identifies client-created prizes without claiming real items that share their names. */
final class FakeGamblePrize {
    private static final String OWNER_KEY = "celeste_fake_gamble_owner";
    private static final String OWNER = UUID.randomUUID().toString();

    private FakeGamblePrize() { }

    static void mark(ItemStack stack) {
        NbtComponent existing = stack.get(DataComponentTypes.CUSTOM_DATA);
        NbtCompound data = existing == null ? new NbtCompound() : existing.copyNbt();
        data.putString(OWNER_KEY, OWNER);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(data));
    }

    static boolean isLocal(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        NbtComponent component = stack.get(DataComponentTypes.CUSTOM_DATA);
        return component != null && OWNER.equals(component.copyNbt().getString(OWNER_KEY, ""));
    }

    static int kindOf(ItemStack stack) {
        if (!isLocal(stack)) return -1;
        Text name = stack.get(DataComponentTypes.CUSTOM_NAME);
        if (name == null) return -1;
        String value = name.getString();
        return value.length() == 1 && value.charAt(0) >= '1' && value.charAt(0) <= '9'
            ? value.charAt(0) - '1' : -1;
    }

    static ItemStack unnamedCopy(ItemStack stack) {
        if (!isLocal(stack)) return ItemStack.EMPTY;
        ItemStack copy = stack.copy();
        copy.remove(DataComponentTypes.CUSTOM_NAME);
        return copy;
    }
}
