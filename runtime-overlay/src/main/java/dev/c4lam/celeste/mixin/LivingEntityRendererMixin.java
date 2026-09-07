package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.compat.PresentationHooks;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<T extends LivingEntity> {
    @Inject(
        method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void celeste$replacePlayerModel(LivingEntityRenderState state,
                                            MatrixStack matrices,
                                            OrderedRenderCommandQueue queue,
                                            CameraRenderState cameraState,
                                            CallbackInfo callbackInfo) {
        if (PresentationHooks.replacePlayerModel(state)) callbackInfo.cancel();
    }

    @Inject(
        method = "updateRenderState(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;F)V",
        at = @At("TAIL")
    )
    private void celeste$applyServerRotation(LivingEntity entity,
                                              LivingEntityRenderState state,
                                              float tickDelta,
                                              CallbackInfo callbackInfo) {
        PresentationHooks.applyServerRotation(entity, state);
    }
}
