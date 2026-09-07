package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.hud.ServerTickRateTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerTimeMixin {
    @Inject(method = "onWorldTimeUpdate", at = @At("TAIL"))
    private void celeste$measureServerTickRate(WorldTimeUpdateS2CPacket packet, CallbackInfo ci) {
        dev.c4lam.celeste.compat.GameplayCallbacks.time(packet);
    }
}
