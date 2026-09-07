package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.compat.RotationHooks;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Entity.class)
public abstract class EntityMoveFixMixin {
    @Redirect(
        method = "updateVelocity",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;getYaw()F")
    )
    private float celeste$movementYaw(Entity entity) {
        return RotationHooks.movementYaw(entity);
    }
}
