package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.compat.GameplayCallbacks;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerTotemPopMixin {
    @Inject(method = "onEntityStatus", at = @At("TAIL"))
    private void celeste$observeTotemPop(EntityStatusS2CPacket packet, CallbackInfo ci) {
        GameplayCallbacks.entityStatus(packet);
    }
}
