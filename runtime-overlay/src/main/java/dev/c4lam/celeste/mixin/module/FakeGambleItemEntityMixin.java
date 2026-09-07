package dev.c4lam.celeste.mixin.module;

import dev.c4lam.celeste.module.impl.visual.expansion.FakeGambleFeature;
import dev.c4lam.celeste.compat.FakeGambleHooks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public abstract class FakeGambleItemEntityMixin {
    @Shadow
    private void tryMerge() {
    }

    @Inject(method = "onPlayerCollision", at = @At("HEAD"), cancellable = true)
    private void celeste$fakeGamblePickup(PlayerEntity player, CallbackInfo ci) {
        if (FakeGambleFeature.tryPickup((ItemEntity) (Object) this, player)) {
            ci.cancel();
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void celeste$fakeGambleClientPhysics(CallbackInfo ci) {
        ItemEntity self = (ItemEntity) (Object) this;
        int action = FakeGambleHooks.itemTickAction(self);
        if (action == FakeGambleHooks.SKIP) return;
        if (action == FakeGambleHooks.MERGE_AND_TICK) {
            this.tryMerge();
        }
        FakeGambleFeature.clientItemTick(self);
    }
}
