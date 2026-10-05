package dev.c4lam.celeste.mixin.module;

import dev.c4lam.celeste.compat.FakeGambleHooks;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class FakeGambleSyncMixin {
    @Inject(method = "onScreenHandlerSlotUpdate", at = @At("TAIL"))
    private void celeste$reapplyFakeGambleSlot(ScreenHandlerSlotUpdateS2CPacket packet, CallbackInfo ci) {
        FakeGambleHooks.reapplyAfterSync();
    }

    @Inject(method = "onInventory", at = @At("TAIL"))
    private void celeste$reapplyFakeGambleInventory(InventoryS2CPacket packet, CallbackInfo ci) {
        FakeGambleHooks.reapplyAfterSync();
    }
}
