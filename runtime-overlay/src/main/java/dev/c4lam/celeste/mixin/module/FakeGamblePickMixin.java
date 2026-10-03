package dev.c4lam.celeste.mixin.module;

import dev.c4lam.celeste.compat.FakeGambleHooks;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class FakeGamblePickMixin {
    @Inject(method = "doItemPick", at = @At("HEAD"), cancellable = true)
    private void celeste$fakeGambleMiddleClick(CallbackInfo ci) {
        if (FakeGambleHooks.onPickBlock((MinecraftClient) (Object) this)) {
            ci.cancel();
        }
    }
}
