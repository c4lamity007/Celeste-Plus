package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.compat.PresentationHooks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents the game HUD and crosshair from showing behind Celeste's GUI. */
@Mixin(value = InGameHud.class)
public abstract class InGameHudMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void celeste$hideHud(
        DrawContext context,
        RenderTickCounter tickCounter,
        CallbackInfo callback
    ) {
        if (PresentationHooks.hideHud((InGameHud) (Object) this, context, tickCounter)) {
            callback.cancel();
        }
    }
}
