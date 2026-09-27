package dev.c4lam.celeste.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.c4lam.celeste.compat.RotationHooks;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityRotationMixin {
    @ModifyReturnValue(method = "isCamera", at = @At("RETURN"))
    private boolean celeste$freecamKeepsLocalPlayerLive(boolean vanilla) {
        return vanilla || RotationHooks.keepLocalPlayerControl((ClientPlayerEntity) (Object) this);
    }

    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerEntity;getYaw()F"
        )
    )
    private float celeste$mountedMovementPacketYaw(ClientPlayerEntity player) {
        return RotationHooks.outgoingYaw(player);
    }

    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerEntity;getPitch()F"
        )
    )
    private float celeste$mountedMovementPacketPitch(ClientPlayerEntity player) {
        return RotationHooks.outgoingPitch(player);
    }

    @Redirect(
        method = "sendMovementPackets",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerEntity;getYaw()F"
        )
    )
    private float celeste$movementPacketYaw(ClientPlayerEntity player) {
        return RotationHooks.outgoingYaw(player);
    }

    @Redirect(
        method = "sendMovementPackets",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerEntity;getPitch()F"
        )
    )
    private float celeste$movementPacketPitch(ClientPlayerEntity player) {
        return RotationHooks.outgoingPitch(player);
    }
}
