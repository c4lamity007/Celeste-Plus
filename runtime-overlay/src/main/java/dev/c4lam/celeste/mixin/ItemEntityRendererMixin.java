package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.module.impl.visual.expansion.ItemEspFeature;
import net.minecraft.client.render.entity.ItemEntityRenderer;
import net.minecraft.client.render.entity.state.ItemEntityRenderState;
import net.minecraft.entity.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds Item ESP's real model outline after vanilla prepares the render state. */
@Mixin(ItemEntityRenderer.class)
public abstract class ItemEntityRendererMixin {
    @Inject(method = "updateRenderState(Lnet/minecraft/entity/ItemEntity;Lnet/minecraft/client/render/entity/state/ItemEntityRenderState;F)V",
        at = @At("TAIL"))
    private void celeste$itemHighlight(ItemEntity entity, ItemEntityRenderState state,
                                       float tickProgress, CallbackInfo callback) {
        ItemEspFeature.applyModelHighlight(entity, state);
    }
}
