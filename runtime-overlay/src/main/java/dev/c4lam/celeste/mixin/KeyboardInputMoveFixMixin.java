package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.compat.RotationHooks;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMoveFixMixin extends Input {
    @Inject(method = "tick", at = @At("TAIL"))
    private void celeste$correctSilentMovement(CallbackInfo callbackInfo) {
        var correction = RotationHooks.keyboardCorrection(this, movementVector, playerInput);
        if (correction != null) {
            movementVector = correction.movement();
            playerInput = correction.keys();
        }
    }
}
