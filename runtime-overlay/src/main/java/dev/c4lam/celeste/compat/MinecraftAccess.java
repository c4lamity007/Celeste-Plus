package dev.c4lam.celeste.compat;

import dev.c4lam.celeste.mixin.ClientPlayerInteractionManagerAccessor;
import dev.c4lam.celeste.mixin.InGameHudAccessor;
import dev.c4lam.celeste.mixin.MinecraftClientAttackInvoker;
import dev.c4lam.celeste.mixin.expansion.inventory.InventoryExpansionHandledScreenAccessor;
import dev.c4lam.celeste.mixin.expansion.inventory.MinecraftClientUseInvoker;
import dev.c4lam.celeste.mixin.module.AuraAttackCooldownAccessor;
import dev.c4lam.celeste.mixin.module.KeyBindingAccessor;
import java.lang.invoke.MethodHandle;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.LivingEntity;
import net.minecraft.screen.slot.Slot;

/** Existing startup accessors plus cached, schema-preserving access for late loading. */
public final class MinecraftAccess {
    private MinecraftAccess() { }

    public static InputUtil.Key boundKey(KeyBinding binding) {
        if ((Object) binding instanceof KeyBindingAccessor accessor) return accessor.celeste$getBoundKey();
        try { return (InputUtil.Key) Keys.BOUND.invokeExact(binding); }
        catch (Throwable failure) { throw NamedOrIntermediaryMembers.propagate(failure); }
    }

    public static int queuedPresses(KeyBinding binding) {
        if ((Object) binding instanceof KeyBindingAccessor accessor) return accessor.celeste$getTimesPressed();
        try { return (int) Keys.PRESSES.invokeExact(binding); }
        catch (Throwable failure) { throw NamedOrIntermediaryMembers.propagate(failure); }
    }

    public static int lastSelectedSlot(ClientPlayerInteractionManager manager) {
        if (manager instanceof ClientPlayerInteractionManagerAccessor accessor) return accessor.celeste$getLastSelectedSlot();
        try { return (int) Interaction.LAST_SLOT.invokeExact(manager); }
        catch (Throwable failure) { throw NamedOrIntermediaryMembers.propagate(failure); }
    }

    public static void syncSelectedSlot(ClientPlayerInteractionManager manager) {
        if (manager instanceof ClientPlayerInteractionManagerAccessor accessor) { accessor.celeste$syncSelectedSlot(); return; }
        try { Interaction.SYNC_SLOT.invokeExact(manager); }
        catch (Throwable failure) { throw NamedOrIntermediaryMembers.propagate(failure); }
    }

    public static int attackCooldown(MinecraftClient client) {
        if ((Object) client instanceof MinecraftClientAttackInvoker accessor) return accessor.celeste$getAttackCooldown();
        try { return (int) Client.COOLDOWN.invokeExact(client); }
        catch (Throwable failure) { throw NamedOrIntermediaryMembers.propagate(failure); }
    }

    public static boolean attack(MinecraftClient client) {
        if ((Object) client instanceof MinecraftClientAttackInvoker accessor) return accessor.celeste$invokeDoAttack();
        try { return (boolean) Client.ATTACK.invokeExact(client); }
        catch (Throwable failure) { throw NamedOrIntermediaryMembers.propagate(failure); }
    }

    public static void useItem(MinecraftClient client) {
        if ((Object) client instanceof MinecraftClientUseInvoker accessor) { accessor.celeste$invokeDoItemUse(); return; }
        try { Client.USE.invokeExact(client); }
        catch (Throwable failure) { throw NamedOrIntermediaryMembers.propagate(failure); }
    }

    public static int ticksSinceAttack(LivingEntity entity) {
        if (entity instanceof AuraAttackCooldownAccessor accessor) return accessor.celeste$auraTicksSinceLastAttack();
        try { return (int) Entity.ATTACK_TICKS.invokeExact(entity); }
        catch (Throwable failure) { throw NamedOrIntermediaryMembers.propagate(failure); }
    }

    public static Slot focusedSlot(HandledScreen<?> screen) {
        if ((Object) screen instanceof InventoryExpansionHandledScreenAccessor accessor) return accessor.celeste$inventoryExpansion$getFocusedSlot();
        try { return (Slot) Screen.FOCUSED.invokeExact(screen); }
        catch (Throwable failure) { throw NamedOrIntermediaryMembers.propagate(failure); }
    }

    public static void renderHotbar(InGameHud hud, DrawContext context, RenderTickCounter tickCounter) {
        if ((Object) hud instanceof InGameHudAccessor accessor) { accessor.celeste$renderHotbar(context, tickCounter); return; }
        try { Hud.HOTBAR.invokeExact(hud, context, tickCounter); }
        catch (Throwable failure) { throw NamedOrIntermediaryMembers.propagate(failure); }
    }

    /** Beta host preflight: resolve every required member before activating modules. */
    public static void verifyLateMembers() {
        MethodHandle[] required = { Keys.BOUND, Keys.PRESSES, Interaction.LAST_SLOT, Interaction.SYNC_SLOT,
            Client.COOLDOWN, Client.ATTACK, Client.USE, Entity.ATTACK_TICKS, Screen.FOCUSED, Hud.HOTBAR };
        for (MethodHandle handle : required) if (handle == null) throw new IllegalStateException("Missing Minecraft member handle");
    }

    // Each group initializes lazily. Ordinary startup continues through the
    // existing interface path without resolving or invoking reflective handles.
    private static final class Keys {
        static final MethodHandle BOUND = NamedOrIntermediaryMembers.getter(KeyBinding.class, InputUtil.Key.class, "boundKey", "field_1655");
        static final MethodHandle PRESSES = NamedOrIntermediaryMembers.getter(KeyBinding.class, int.class, "timesPressed", "field_1661");
    }
    private static final class Interaction {
        static final MethodHandle LAST_SLOT = NamedOrIntermediaryMembers.getter(ClientPlayerInteractionManager.class, int.class, "lastSelectedSlot", "field_3721");
        static final MethodHandle SYNC_SLOT = NamedOrIntermediaryMembers.method(ClientPlayerInteractionManager.class, void.class, "syncSelectedSlot", "method_2911");
    }
    private static final class Client {
        static final MethodHandle COOLDOWN = NamedOrIntermediaryMembers.getter(MinecraftClient.class, int.class, "attackCooldown", "field_1771");
        static final MethodHandle ATTACK = NamedOrIntermediaryMembers.method(MinecraftClient.class, boolean.class, "doAttack", "method_1536");
        static final MethodHandle USE = NamedOrIntermediaryMembers.method(MinecraftClient.class, void.class, "doItemUse", "method_1583");
    }
    private static final class Entity {
        static final MethodHandle ATTACK_TICKS = NamedOrIntermediaryMembers.getter(LivingEntity.class, int.class, "ticksSinceLastAttack", "field_6273");
    }
    private static final class Screen {
        static final MethodHandle FOCUSED = NamedOrIntermediaryMembers.getter(HandledScreen.class, Slot.class, "focusedSlot", "field_2787");
    }
    private static final class Hud {
        static final MethodHandle HOTBAR = NamedOrIntermediaryMembers.method(InGameHud.class, void.class, "renderHotbar", "method_1759", DrawContext.class, RenderTickCounter.class);
    }
}
