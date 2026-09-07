package dev.c4lam.celeste.mixin.module;

import dev.c4lam.celeste.compat.FakeGambleHooks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.world.WorldEventHandler;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldEventHandler.class)
public abstract class FakeGambleWorldEventMixin {
    @Shadow
    @Final
    private ClientWorld world;

    @Inject(method = "processWorldEvent", at = @At("HEAD"), cancellable = true)
    private void celeste$fakeGambleDispense(int eventId, BlockPos pos, int data, CallbackInfo ci) {
        if (FakeGambleHooks.worldEvent((WorldEventHandler) (Object) this, world, eventId, pos, data)) {
            ci.cancel();
        }
    }
}
