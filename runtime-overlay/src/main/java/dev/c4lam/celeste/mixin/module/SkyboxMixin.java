package dev.c4lam.celeste.mixin.module;

import dev.c4lam.celeste.compat.SkyboxHooks;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.SkyRendering;
import net.minecraft.client.render.state.SkyRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces only a successfully drawn, safe overworld sky; vanilla is otherwise untouched. */
@Mixin(SkyRendering.class)
public abstract class SkyboxMixin {
    @Inject(method = "updateRenderState", at = @At("RETURN"))
    private void celeste$extractSkybox(ClientWorld world, float tickProgress, Camera camera,
                                        SkyRenderState state, CallbackInfo callback) {
        SkyboxHooks.extract(this, world, camera, state);
    }

    @Inject(method = "renderTopSky", at = @At("HEAD"), cancellable = true)
    private void celeste$drawSkybox(int color, CallbackInfo callback) {
        if (SkyboxHooks.draw(this)) callback.cancel();
    }

    @Inject(method = "renderGlowingSky", at = @At("HEAD"), cancellable = true)
    private void celeste$skyboxOwnHorizon(MatrixStack matrices, float angle, int color, CallbackInfo callback) {
        if (SkyboxHooks.ownsSky(this)) callback.cancel();
    }

    @Inject(method = "renderCelestialBodies", at = @At("HEAD"), cancellable = true)
    private void celeste$skyboxOwnCelestials(MatrixStack matrices, float sun, float moon, float stars,
                                              MoonPhase phase, float rain, float brightness, CallbackInfo callback) {
        if (SkyboxHooks.ownsSky(this)) callback.cancel();
    }

    @Inject(method = "renderSkyDark", at = @At("HEAD"), cancellable = true)
    private void celeste$skyboxOwnLowerHemisphere(CallbackInfo callback) {
        if (SkyboxHooks.ownsSky(this)) callback.cancel();
    }
}
