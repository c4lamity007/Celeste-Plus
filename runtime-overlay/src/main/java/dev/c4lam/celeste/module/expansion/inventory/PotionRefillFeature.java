package dev.c4lam.celeste.module.expansion.inventory;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

import java.util.concurrent.ThreadLocalRandom;

/** Splash-potion hotbar refill: inventory SWAP, one action per tick. */
final class PotionRefillFeature implements ModuleFeature {
    private static final int PRIORITY = 120;
    private int lastActionAge = Integer.MIN_VALUE / 2;
    private int hoverSlotId = -1;
    private boolean hoverClicked;
    private long nextHoverAtMs;
    private InventorySupport.ClickIntent intent;
    private int destHotbar = -1;
    private PotionKind expectedKind;
    private InventoryScreen intentScreen;
    private boolean intentHover;

    private enum PotionKind {
        STRENGTH, SPEED, HEALTH
    }

    @Override
    public String name() {
        return "Potion Refill";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        intent = null;
        destHotbar = -1;
        expectedKind = null;
        intentScreen = null;
        MinecraftClient client = context.client();
        if (!InventorySupport.visibleInventoryAvailable(client)
            || !client.player.currentScreenHandler.getCursorStack().isEmpty()) {
            resetHover();
            return;
        }

        boolean hover = context.mode("Mode", "Automatic").equalsIgnoreCase("Hover");
        if (hover) {
            planHover(context, client);
        } else {
            resetHover();
            planAutomatic(context, client);
        }
    }

    @Override
    public void onActionTick(ModuleContext context) {
        InventorySupport.ClickIntent selected = intent;
        int selectedDest = destHotbar;
        PotionKind selectedKind = expectedKind;
        InventoryScreen selectedScreen = intentScreen;
        if (selected == null || selectedDest < 0 || selectedDest > 8 || selectedKind == null) return;
        boolean hover = intentHover;
        ActionScheduler.getInstance().submit(this, PRIORITY, client -> {
            if (!context.module().enabled()
                || client.currentScreen != selectedScreen || !InventorySupport.visibleInventoryAvailable(client)
                || context.mode("Mode", "Automatic").equalsIgnoreCase("Hover") != hover
                || expectedPotion(context, selectedDest) != selectedKind
                || hover && (InventorySupport.focusedSlot(client) == null
                    || InventorySupport.focusedSlot(client).id != selected.slotId())
                || !delayReady(context, client, hover)
                || !validSwap(client, selected.slotId(), selectedDest, selectedKind)) return;
            if (InventorySupport.executeInventoryClick(client, selected, selectedDest, SlotActionType.SWAP)) {
                lastActionAge = client.player.age;
                if (hover) {
                    hoverClicked = true;
                    nextHoverAtMs = System.currentTimeMillis() + hoverDelayMs(context);
                }
            }
        });
    }

    private void planAutomatic(ModuleContext context, MinecraftClient client) {
        if (!delayReady(context, client, false)) return;
        for (PotionKind kind : PotionKind.values()) {
            int dest = firstDestination(client, context, kind);
            if (dest < 0) continue;
            Slot source = automaticSource(client, kind);
            if (source == null || !validSwap(client, source.id, dest, kind)) continue;
            capture(client, source, dest, kind, false);
            return;
        }
    }

    private void planHover(ModuleContext context, MinecraftClient client) {
        Slot focused = InventorySupport.focusedSlot(client);
        PotionKind kind = focused == null ? null : potionKind(focused.getStack());
        if (focused == null || kind == null || !playerInventorySlot(client, focused)
            || sourceAlreadyAssigned(context, focused.getIndex(), kind)) {
            resetHover();
            return;
        }
        if (hoverSlotId != focused.id) {
            hoverSlotId = focused.id;
            hoverClicked = false;
            nextHoverAtMs = 0L;
        }
        if (!delayReady(context, client, true)) return;
        int dest = firstDestination(client, context, kind);
        if (dest < 0 || !validSwap(client, focused.id, dest, kind)) return;
        capture(client, focused, dest, kind, true);
    }

    private void capture(MinecraftClient client, Slot source, int dest, PotionKind kind, boolean hover) {
        PotionKind expected = kind;
        intent = InventorySupport.captureClick(client, source.id,
            stack -> potionKind(stack) == expected);
        destHotbar = dest;
        expectedKind = kind;
        intentScreen = (InventoryScreen) client.currentScreen;
        intentHover = hover;
    }

    private boolean delayReady(ModuleContext context, MinecraftClient client, boolean hover) {
        if (hover) {
            return !hoverClicked || System.currentTimeMillis() >= nextHoverAtMs;
        }
        int delay = Math.max(1, (int) Math.round(context.slider("Delay", 3.0D)));
        return client.player.age - lastActionAge >= delay;
    }

    private static long hoverDelayMs(ModuleContext context) {
        double min = Math.max(0.1D, context.slider("CPS Min", 8.0D));
        double max = Math.max(0.1D, context.slider("CPS Max", 12.0D));
        if (max < min) {
            double swap = min;
            min = max;
            max = swap;
        }
        double cps = min == max ? min : ThreadLocalRandom.current().nextDouble(min, max);
        return Math.max(0L, Math.round(1000.0D / cps));
    }

    private static int firstDestination(MinecraftClient client, ModuleContext context, PotionKind kind) {
        for (int hotbar = 0; hotbar < 9; hotbar++) {
            if (expectedPotion(context, hotbar) != kind) continue;
            ItemStack dest = client.player.getInventory().getStack(hotbar);
            if (!dest.isIn(ItemTags.SWORDS) && potionKind(dest) != kind) return hotbar;
        }
        return -1;
    }

    private static Slot automaticSource(MinecraftClient client, PotionKind kind) {
        for (Slot slot : client.player.playerScreenHandler.slots) {
            if (!playerInventorySlot(client, slot) || slot.getIndex() < 9 || slot.getIndex() > 35) continue;
            if (potionKind(slot.getStack()) == kind) return slot;
        }
        return null;
    }

    private static boolean sourceAlreadyAssigned(ModuleContext context, int inventoryIndex, PotionKind kind) {
        return inventoryIndex >= 0 && inventoryIndex < 9 && expectedPotion(context, inventoryIndex) == kind;
    }

    private static boolean validSwap(MinecraftClient client, int sourceSlotId, int destHotbar, PotionKind kind) {
        if (!InventorySupport.playerInventoryAvailable(client) || destHotbar < 0 || destHotbar > 8
            || sourceSlotId < 0 || sourceSlotId >= client.player.playerScreenHandler.slots.size()) {
            return false;
        }
        Slot source = client.player.playerScreenHandler.slots.get(sourceSlotId);
        Slot dest = playerSlot(client, destHotbar);
        if (source == null || dest == null || source.id != sourceSlotId || dest == source
            || !playerInventorySlot(client, source) || !playerInventorySlot(client, dest)
            || dest.getIndex() != destHotbar
            || !source.canTakeItems(client.player) || !dest.canTakeItems(client.player)
            || !dest.canInsert(source.getStack()) || !source.canInsert(dest.getStack())
            || dest.getStack().isIn(ItemTags.SWORDS)
            || potionKind(source.getStack()) != kind
            || potionKind(dest.getStack()) == kind) {
            return false;
        }
        return true;
    }

    private static Slot playerSlot(MinecraftClient client, int inventoryIndex) {
        for (Slot slot : client.player.playerScreenHandler.slots) {
            if (slot.inventory == client.player.getInventory() && slot.getIndex() == inventoryIndex) {
                return slot;
            }
        }
        return null;
    }

    private static boolean playerInventorySlot(MinecraftClient client, Slot slot) {
        int index = slot.getIndex();
        return slot.inventory == client.player.getInventory() && index >= 0 && index <= 35
            && slot.id >= 0 && slot.id < client.player.playerScreenHandler.slots.size()
            && client.player.playerScreenHandler.slots.get(slot.id) == slot;
    }

    private static PotionKind expectedPotion(ModuleContext context, int hotbar) {
        if (hotbar < 0 || hotbar > 8) return null;
        int strength = uiSlot(context.slider("Strength Slot", 2.0D), true);
        int speed = uiSlot(context.slider("Speed Slot", 3.0D), true);
        int healthMin = uiSlot(context.rangeLow("Health Slots", 4.0D), false);
        int healthMax = uiSlot(context.rangeHigh("Health Slots", 9.0D), false);
        if (healthMin > healthMax) {
            int swap = healthMin;
            healthMin = healthMax;
            healthMax = swap;
        }
        if (hotbar == strength) return PotionKind.STRENGTH;
        if (hotbar == speed) return PotionKind.SPEED;
        if (hotbar >= healthMin && hotbar <= healthMax) return PotionKind.HEALTH;
        return null;
    }

    private static int uiSlot(double value, boolean allowOff) {
        int slot = (int) Math.round(value);
        if (allowOff && slot == 0) return -1;
        if (slot < 1 || slot > 9) return -1;
        return slot - 1;
    }

    static PotionKind potionKind(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isOf(Items.SPLASH_POTION)) return null;
        PotionContentsComponent potion = stack.get(DataComponentTypes.POTION_CONTENTS);
        if (potion == null) return null;
        boolean strength = false;
        boolean speed = false;
        boolean health = false;
        for (var effect : potion.getEffects()) {
            if (effect.getEffectType().equals(StatusEffects.STRENGTH)) strength = true;
            else if (effect.getEffectType().equals(StatusEffects.SPEED)) speed = true;
            else if (effect.getEffectType().equals(StatusEffects.INSTANT_HEALTH)) health = true;
        }
        int matched = (strength ? 1 : 0) + (speed ? 1 : 0) + (health ? 1 : 0);
        if (matched != 1) return null;
        if (strength) return PotionKind.STRENGTH;
        if (speed) return PotionKind.SPEED;
        return PotionKind.HEALTH;
    }

    @Override
    public void onDisable(ModuleContext context) { reset(); }

    @Override
    public void onWorldChanged(ModuleContext context) { reset(); }

    private void reset() {
        intent = null;
        destHotbar = -1;
        expectedKind = null;
        intentScreen = null;
        lastActionAge = Integer.MIN_VALUE / 2;
        resetHover();
    }

    private void resetHover() {
        hoverSlotId = -1;
        hoverClicked = false;
        nextHoverAtMs = 0L;
    }
}
