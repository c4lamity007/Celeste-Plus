package dev.c4lam.celeste.beta.lifecycle;

import dev.c4lam.celeste.compat.FakeGambleAccess;
import dev.c4lam.celeste.compat.FakeGambleHooks;
import dev.c4lam.celeste.module.impl.visual.expansion.FakeGambleFeature;
import dev.celestebeta.host.FakeGambleHookBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.Generic3x3ContainerScreen;
import net.minecraft.client.world.WorldEventHandler;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldEvents;

/** Existing local-only feature guards remain authoritative; no server inventory action is introduced. */
public final class BetaFakeGambleHooks implements FakeGambleHookBridge.Handler, AutoCloseable {
    private final FakeGambleHookBridge.Registration registration;
    private BetaFakeGambleHooks() { registration = FakeGambleHookBridge.install(this); }
    public static BetaFakeGambleHooks connect(MinecraftClient client) {
        if (!client.isOnThread()) throw new IllegalStateException("Connect local inventory simulation on the client thread");
        FakeGambleHookBridge.assertVisibleFrom(client.getClass().getClassLoader());
        FakeGambleHookBridge.assertVisibleFrom(BetaFakeGambleHooks.class.getClassLoader());
        FakeGambleAccess.verifyLateMembers();
        return new BetaFakeGambleHooks();
    }
    public boolean click(int syncId, int slotId, int button, Object actionType, Object player) {
        return FakeGambleFeature.handleClick(syncId, slotId, button, (SlotActionType) actionType, (PlayerEntity) player);
    }
    public boolean worldEvent(Object value, int eventId, Object pos, int data) {
        if (eventId != WorldEvents.DISPENSER_FAILS) return false;
        WorldEventHandler handler = (WorldEventHandler) value;
        return FakeGambleHooks.worldEvent(handler, FakeGambleAccess.world(handler), eventId, (BlockPos) pos, data);
    }
    public boolean pickup(Object item, Object player) { return FakeGambleFeature.tryPickup((ItemEntity) item, (PlayerEntity) player); }
    public void itemTick(Object value) {
        ItemEntity item = (ItemEntity) value;
        int action = FakeGambleHooks.itemTickAction(item);
        if (action == FakeGambleHooks.SKIP) return;
        if (action == FakeGambleHooks.MERGE_AND_TICK) FakeGambleAccess.tryMerge(item);
        FakeGambleFeature.clientItemTick(item);
    }
    public void afterSync() { FakeGambleFeature.reapplyAfterSync(); }
    public void screenClosed(Object handler, Object player) { FakeGambleFeature.stashCursor((ScreenHandler) handler, (PlayerEntity) player); }
    public void noteDispenser(Object hit) { FakeGambleFeature.noteUsedDispenser((BlockHitResult) hit); }
    public boolean pick(Object client) { return FakeGambleFeature.onPickBlock((MinecraftClient) client); }
    public void screenRender(Object screen) { FakeGambleFeature.ensureScreenPrizes((Generic3x3ContainerScreen) screen); }
    @Override public void close() { registration.close(); }
}
