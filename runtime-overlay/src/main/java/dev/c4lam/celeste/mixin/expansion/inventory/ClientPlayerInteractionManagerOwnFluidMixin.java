package dev.c4lam.celeste.mixin.expansion.inventory;

import dev.c4lam.celeste.compat.OwnFluidHooks;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerOwnFluidMixin {
    @Inject(method = "interactItem", at = @At("HEAD"))
    private void celeste$captureOwnFluidPlace(PlayerEntity player, Hand hand,
                                              CallbackInfoReturnable<ActionResult> cir) {
        OwnFluidHooks.capture(this, player, hand);
    }

    @Inject(method = "interactItem", at = @At("RETURN"))
    private void celeste$markOwnFluidPlace(PlayerEntity player, Hand hand,
                                           CallbackInfoReturnable<ActionResult> cir) {
        OwnFluidHooks.afterInteract(this, cir.getReturnValue());
    }
}
