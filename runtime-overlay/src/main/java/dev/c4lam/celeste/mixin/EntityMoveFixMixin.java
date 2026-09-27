package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.compat.RotationHooks;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMoveFixMixin {
    @Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
    private void celeste$freecamLook(double cursorDeltaX, double cursorDeltaY, CallbackInfo ci) {
        if (RotationHooks.redirectLook((Entity) (Object) this, cursorDeltaX, cursorDeltaY)) {
            ci.cancel();
        }
    }

    @Redirect(
        method = "updateVelocity",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getYaw()F")
    )
    private float celeste$movementYaw(Entity entity) {
        return RotationHooks.movementYaw(entity);
    }
}
