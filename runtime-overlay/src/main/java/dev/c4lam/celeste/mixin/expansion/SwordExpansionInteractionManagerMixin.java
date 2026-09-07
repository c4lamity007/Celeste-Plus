package dev.c4lam.celeste.mixin.expansion;

import dev.c4lam.celeste.module.expansion.sword.SwordAttackCoordinator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Emits a tap signal only from the real vanilla entity-attack method. */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class SwordExpansionInteractionManagerMixin {
    @Inject(method = "attackEntity", at = @At("HEAD"))
    private void celeste$swordExpansionOnAttack(PlayerEntity player, Entity target,
                                                CallbackInfo callback) {
        dev.c4lam.celeste.compat.GameplayCallbacks.entityAttack(target);
    }
}
