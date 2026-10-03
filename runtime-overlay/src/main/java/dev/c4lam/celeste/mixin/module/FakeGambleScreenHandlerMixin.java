package dev.c4lam.celeste.mixin.module;

import dev.c4lam.celeste.compat.FakeGambleHooks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.ScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenHandler.class)
public abstract class FakeGambleScreenHandlerMixin {
    @Inject(method = "onClosed", at = @At("HEAD"))
    private void celeste$stashFakeGambleCursor(PlayerEntity player, CallbackInfo ci) {
        FakeGambleHooks.stashCursor((ScreenHandler) (Object) this, player);
    }
}
