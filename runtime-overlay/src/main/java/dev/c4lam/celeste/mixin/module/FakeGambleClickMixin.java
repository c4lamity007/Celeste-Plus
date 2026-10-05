package dev.c4lam.celeste.mixin.module;

import dev.c4lam.celeste.compat.FakeGambleHooks;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class FakeGambleClickMixin {
    @Inject(method = "clickSlot", at = @At("HEAD"), cancellable = true)
    private void celeste$fakeGambleClick(int syncId, int slotId, int button, SlotActionType actionType,
                                         PlayerEntity player, CallbackInfo ci) {
        if (FakeGambleHooks.handleClick(syncId, slotId, button, actionType, player)) {
            ci.cancel();
        }
    }
}
