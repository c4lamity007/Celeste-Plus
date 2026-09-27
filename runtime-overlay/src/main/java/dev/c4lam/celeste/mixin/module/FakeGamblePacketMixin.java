package dev.c4lam.celeste.mixin.module;

import dev.c4lam.celeste.compat.FakeGambleHooks;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only the owned-item offhand swap is local; unrelated packet traffic is untouched. */
@Mixin(ClientCommonNetworkHandler.class)
public abstract class FakeGamblePacketMixin {
    @Inject(method = "sendPacket", at = @At("HEAD"), cancellable = true)
    private void celeste$localOffhandSwap(Packet<?> packet, CallbackInfo ci) {
        if (FakeGambleHooks.packet(packet)) ci.cancel();
    }
}
