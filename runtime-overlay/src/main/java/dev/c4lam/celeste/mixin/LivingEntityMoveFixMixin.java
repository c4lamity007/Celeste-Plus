package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.compat.RotationHooks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMoveFixMixin {
    @Redirect(
        method = "calcGlidingVelocity",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;getRotationVector()Lnet/minecraft/util/math/Vec3d;")
    )
    private Vec3d celeste$glidingRotationVector(LivingEntity entity) {
        return RotationHooks.glidingVector(entity);
    }

    @Redirect(
        method = "calcGlidingVelocity",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;getPitch()F")
    )
    private float celeste$glidingPitch(LivingEntity entity) {
        return RotationHooks.glidingPitch(entity);
    }

    @Redirect(
        method = "jump",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;getYaw()F")
    )
    private float celeste$sprintJumpYaw(LivingEntity entity) {
        return RotationHooks.sprintJumpYaw(entity);
    }
}
