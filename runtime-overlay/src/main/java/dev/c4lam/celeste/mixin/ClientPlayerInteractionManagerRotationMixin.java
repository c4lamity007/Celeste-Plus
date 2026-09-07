package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.compat.RotationHooks;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerRotationMixin {
    @Redirect(
        method = "method_41929",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/entity/player/PlayerEntity;getYaw()F"
        )
    )
    private float celeste$itemUsePacketYaw(PlayerEntity player) {
        return RotationHooks.itemYaw(player);
    }

    @Redirect(
        method = "method_41929",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/entity/player/PlayerEntity;getPitch()F"
        )
    )
    private float celeste$itemUsePacketPitch(PlayerEntity player) {
        return RotationHooks.itemPitch(player);
    }
}
