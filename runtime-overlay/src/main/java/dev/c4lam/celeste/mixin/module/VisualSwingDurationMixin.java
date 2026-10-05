package dev.c4lam.celeste.mixin.module;

import dev.c4lam.celeste.module.delivery.ModuleHooks;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Alters only the local presentation duration; server attack timing is untouched. */
@Mixin(LivingEntity.class)
public abstract class VisualSwingDurationMixin {
    @Inject(method = "getHandSwingDuration", at = @At("RETURN"), cancellable = true)
    private void celeste$visualSwingDuration(CallbackInfoReturnable<Integer> callback) {
        callback.setReturnValue(durationFor((LivingEntity) (Object) this, callback.getReturnValue()));
    }
 private static int durationFor(net.minecraft.entity.LivingEntity entity, int vanilla) { return (int) ModuleHooks.invoke("swing_speed", "swing.duration", vanilla, entity, vanilla); }
}
