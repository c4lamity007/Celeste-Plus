package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.module.delivery.ModuleHooks;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to handle knockback delay by delaying incoming entity motion and explode packets.
 */
@Mixin(value = net.minecraft.client.network.ClientPlayNetworkHandler.class, priority = 2001)
public abstract class ClientPlayNetworkHandlerKnockbackDelayMixin {
    @Inject(method = "onEntityVelocityUpdate", at = @At("HEAD"), cancellable = true)
    private void celeste$handleEntityMotion(EntityVelocityUpdateS2CPacket packet, CallbackInfo ci) {
        if ((boolean) ModuleHooks.invoke("knockback_delay", "knockback.packet", false, packet)) ci.cancel();
    }

    @Inject(method = "onExplosion", at = @At("HEAD"), cancellable = true)
    private void celeste$handleExplode(ExplosionS2CPacket packet, CallbackInfo ci) {
        if ((boolean) ModuleHooks.invoke("knockback_delay", "knockback.packet", false, packet)) ci.cancel();
    }
}