package dev.c4lam.celeste.mixin;

import dev.c4lam.celeste.compat.RotationHooks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives vanilla local prediction the server-selected stack during one silent
 * action without ever changing the player's visible selected-slot index.
 */
@Mixin(PlayerInventory.class)
public abstract class PlayerInventorySlotMixin {
    @Inject(method = "getSelectedStack", at = @At("HEAD"), cancellable = true)
    private void celeste$getServerSelectedStack(CallbackInfoReturnable<ItemStack> cir) {
        PlayerInventory inventory = (PlayerInventory)(Object)this;
        Object[] result = RotationHooks.selectedStack(inventory, null, false);
        if (result != null) cir.setReturnValue((ItemStack) result[0]);
    }

    @Inject(method = "setSelectedStack", at = @At("HEAD"), cancellable = true)
    private void celeste$setServerSelectedStack(ItemStack stack,
                                                 CallbackInfoReturnable<ItemStack> cir) {
        PlayerInventory inventory = (PlayerInventory)(Object)this;
        Object[] result = RotationHooks.selectedStack(inventory, stack, true);
        if (result != null) cir.setReturnValue((ItemStack) result[0]);
    }
}
