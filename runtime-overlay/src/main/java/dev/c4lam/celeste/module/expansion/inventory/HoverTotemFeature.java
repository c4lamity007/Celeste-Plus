package dev.c4lam.celeste.module.expansion.inventory;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.ModuleManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

/** Volt/Cyemer hover-to-equip behavior with stable-hover and exact-slot revalidation. */
public final class HoverTotemFeature implements ModuleFeature {
    private static final int PRIORITY = 210;
    private int hoveredSlotId = -1;
    private int hoverTicks;
    private Screen hoveredScreen;
    private HoverTotemInventorySupport.ClickIntent intent;
    private SlotActionType actionType;
    private int button;

    @Override
    public String name() {
        return "Hover Totem";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        intent = null;
        var client = context.client();
        if (!context.module().enabled() || legitAutoTotemEnabled()
            || !HoverTotemInventorySupport.visibleInventoryAvailable(client)) {
            resetHover();
            return;
        }
        Slot focused = HoverTotemInventorySupport.focusedSlot(client);
        if (focused == null || focused.inventory != client.player.getInventory()
            || focused.getIndex() < 0 || focused.getIndex() > 35
            || !focused.getStack().isOf(Items.TOTEM_OF_UNDYING)) {
            resetHover();
            return;
        }
        if (hoveredSlotId != focused.id || hoveredScreen != client.currentScreen) {
            hoveredSlotId = focused.id;
            hoveredScreen = client.currentScreen;
            hoverTicks = 0;
        } else {
            hoverTicks++;
        }
        int delay = Math.max(0, (int) Math.round(context.slider("Delay", 4.0D)));
        if (hoverTicks < delay) return;

        int reserveSlot = reserveButton(context);
        if (needsOffhand(context)) {
            actionType = SlotActionType.SWAP;
            button = 40;
        } else if (reserveNeeded(client, focused, reserveSlot)) {
            // A number-key swap works even when the hotbar is full; shift-click
            // silently does nothing in that common survival inventory state.
            actionType = SlotActionType.SWAP;
            button = reserveSlot;
        } else {
            return;
        }
        intent = HoverTotemInventorySupport.captureClick(client, focused.id, stack -> stack.isOf(Items.TOTEM_OF_UNDYING));
    }

    @Override
    public void onActionTick(ModuleContext context) {
        HoverTotemInventorySupport.ClickIntent selected = intent;
        SlotActionType selectedType = actionType;
        int selectedButton = button;
        Screen selectedScreen = hoveredScreen;
        intent = null;
        if (selected == null) return;
        ActionScheduler.getInstance().submit(this, PRIORITY, client -> {
            if (!context.module().enabled() || legitAutoTotemEnabled()
                || client.currentScreen != selectedScreen
                || !HoverTotemInventorySupport.visibleInventoryAvailable(client)) return;
            Slot focused = HoverTotemInventorySupport.focusedSlot(client);
            if (focused == null || focused.id != selected.slotId()
                || focused.inventory != client.player.getInventory()
                || focused.getIndex() < 0 || focused.getIndex() > 35) return;
            // Revalidate the destination too: another action/user may have filled
            // the offhand or hotbar since this intent was planned.
            if (selectedButton == 40 && !needsOffhand(context)) return;
            if (selectedButton != 40 && (selectedButton != reserveButton(context)
                || needsOffhand(context) || !reserveNeeded(client, focused, selectedButton))) return;
            if (HoverTotemInventorySupport.executeInventoryClick(client, selected, selectedButton, selectedType)) {
                resetHover();
            }
        });
    }

    private static boolean legitAutoTotemEnabled() {
        var module = ModuleManager.getInstance().module("Legit Auto Totem");
        return module != null && module.enabled();
    }

    private static boolean needsOffhand(ModuleContext context) {
        var offhand = context.client().player.getOffHandStack();
        return !offhand.isOf(Items.TOTEM_OF_UNDYING)
            && (offhand.isEmpty() || context.toggle("Replace offhand", false));
    }

    private static boolean reserveNeeded(MinecraftClient client, Slot source, int configuredHotbarSlot) {
        return HoverTotemPolicy.reserveNeeded(source.getIndex(), configuredHotbarSlot,
            slot -> client.player.getInventory().getStack(slot).isOf(Items.TOTEM_OF_UNDYING));
    }

    private static int reserveButton(ModuleContext context) {
        return Math.max(0, Math.min(8, (int) Math.round(context.slider("Hotbar slot", 3.0D)) - 1));
    }

    @Override
    public void onDisable(ModuleContext context) { resetHover(); }

    private void resetHover() {
        hoveredSlotId = -1;
        hoveredScreen = null;
        hoverTicks = 0;
        intent = null;
    }
}
