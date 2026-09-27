package dev.c4lam.celeste.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.c4lam.celeste.compat.RotationHooks;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps the real local body in 1.21.11's entity render-state list while Freecam is detached. */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererFreecamMixin {
    @ModifyExpressionValue(
        method = "fillEntityRenderStates",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/Camera;getFocusedEntity()Lnet/minecraft/entity/Entity;",
            ordinal = 3
        )
    )
    private Entity celeste$renderDetachedFreecamBody(Entity vanillaFocus) {
        return RotationHooks.renderedCameraFocus(vanillaFocus);
    }
}
