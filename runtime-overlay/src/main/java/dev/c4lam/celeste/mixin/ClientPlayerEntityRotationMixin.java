package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.compat.RotationHooks;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityRotationMixin {
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
