package dev.c4lam.celeste.mixin.module;

import dev.c4lam.celeste.compat.FakeGambleHooks;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientWorld.class)
public abstract class FakeGambleWorldMixin {
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void celeste$captureLocalPlacement(BlockPos pos, BlockState state, int flags, int depth, CallbackInfoReturnable<Boolean> cir) {
        FakeGambleHooks.beforeSetBlockState((ClientWorld) (Object) this, pos);
    }
    @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
    private void celeste$retireUpdatedGhost(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        FakeGambleHooks.serverBlockUpdate((ClientWorld) (Object) this, pos, state);
    }
    @Inject(method = "onChunkUnload", at = @At("HEAD"))
    private void celeste$retireUnloadedGhost(long chunk, CallbackInfo ci) {
        FakeGambleHooks.chunkUnloaded((ClientWorld) (Object) this, chunk);
    }
}
