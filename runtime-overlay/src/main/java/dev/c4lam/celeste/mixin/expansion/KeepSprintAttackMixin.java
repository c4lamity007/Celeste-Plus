package dev.c4lam.celeste.mixin.expansion;

import dev.c4lam.celeste.compat.KeepSprintHooks;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class KeepSprintAttackMixin {
    @Inject(method = "attackEntity", at = @At("HEAD"))
    private void celeste$captureSprintBeforeAttack(PlayerEntity player, Entity target,
                                                    CallbackInfo callback) {
        KeepSprintHooks.capture(this, player);
    }

    @Inject(method = "attackEntity", at = @At("RETURN"))
    private void celeste$restartSprintAfterAttack(PlayerEntity player, Entity target,
                                                  CallbackInfo callback) {
        KeepSprintHooks.afterAttack(this, target);
    }
}
