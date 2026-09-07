package dev.c4lam.celeste.mixin.module;

import dev.c4lam.celeste.module.impl.combat.AuraServerState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes server facts after vanilla handles each packet; never cancels or edits one. */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class AuraServerStateMixin {
    @Inject(method = "onBlockUpdate", at = @At("TAIL"))
    private void celeste$auraBlock(BlockUpdateS2CPacket packet, CallbackInfo callback) {
        dev.c4lam.celeste.compat.GameplayCallbacks.block(packet);
    }

    @Inject(method = "onChunkDeltaUpdate", at = @At("TAIL"))
    private void celeste$auraBlocks(ChunkDeltaUpdateS2CPacket packet, CallbackInfo callback) {
        dev.c4lam.celeste.compat.GameplayCallbacks.blocks(packet);
    }

    @Inject(method = "onEntitySpawn", at = @At("TAIL"))
    private void celeste$auraSpawn(EntitySpawnS2CPacket packet, CallbackInfo callback) {
        dev.c4lam.celeste.compat.GameplayCallbacks.spawn(packet);
    }

    @Inject(method = "onEntitiesDestroy", at = @At("TAIL"))
    private void celeste$auraDestroy(EntitiesDestroyS2CPacket packet, CallbackInfo callback) {
        dev.c4lam.celeste.compat.GameplayCallbacks.destroy(packet);
    }
}
