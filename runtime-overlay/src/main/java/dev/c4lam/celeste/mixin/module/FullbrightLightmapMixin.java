package dev.c4lam.celeste.mixin.module;

import dev.c4lam.celeste.compat.AtmosphereHooks;
import net.minecraft.client.render.LightmapTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LightmapTextureManager.class)
public abstract class FullbrightLightmapMixin {
    @Shadow private boolean dirty;

    private static final String PUT_FLOAT =
        "Lcom/mojang/blaze3d/buffers/Std140Builder;putFloat(F)Lcom/mojang/blaze3d/buffers/Std140Builder;";

    @Inject(method = "update(F)V", at = @At("HEAD"))
    private void celeste$refreshAnimatedLightmap(float tickProgress, CallbackInfo ci) {
        // Both enable and disable fades must animate between ticks. Once settled,
        // leave vanilla's dirty flag and normal lightmap update budget untouched.
        if (AtmosphereHooks.refreshLightmap()) {
            dirty = true;
        }
    }

    @ModifyArg(method = "update(F)V", at = @At(value = "INVOKE", target = PUT_FLOAT, ordinal = 3), index = 0)
    private float celeste$adjustNightVisionLightmap(float vanilla) {
        return AtmosphereHooks.nightVision(vanilla);
    }

    @ModifyArg(method = "update(F)V", at = @At(value = "INVOKE", target = PUT_FLOAT, ordinal = 6), index = 0)
    private float celeste$adjustGammaLightmap(float vanilla) {
        return AtmosphereHooks.gamma(vanilla);
    }
}
