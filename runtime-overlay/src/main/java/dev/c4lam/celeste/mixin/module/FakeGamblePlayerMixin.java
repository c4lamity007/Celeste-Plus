package dev.c4lam.celeste.mixin.module;

import dev.c4lam.celeste.compat.FakeGambleHooks;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerEntity.class)
public abstract class FakeGamblePlayerMixin {
    @Inject(method = "dropSelectedItem", at = @At("HEAD"), cancellable = true)
    private void celeste$localDrop(boolean entireStack, CallbackInfoReturnable<Boolean> cir) {
        Boolean result = FakeGambleHooks.dropSelected((ClientPlayerEntity) (Object) this, entireStack);
        if (result != null) cir.setReturnValue(result);
    }
}
