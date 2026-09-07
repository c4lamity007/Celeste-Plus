package dev.c4lam.celeste.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.c4lam.celeste.compat.InteractionSlotHooks;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerSlotMixin {
    @Redirect(
        method = "syncSelectedSlot",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/entity/player/PlayerInventory;getSelectedSlot()I"
        )
    )
    private int celeste$keepSilentServerSlot(PlayerInventory inventory) {
        return InteractionSlotHooks.synchronizationSlot(inventory);
    }

    @WrapMethod(method = {"attackBlock", "updateBlockBreakingProgress"})
    private boolean celeste$withSilentBreakingStack(BlockPos pos, Direction direction,
                                                   Operation<Boolean> original) {
        InteractionSlotHooks.beginBreaking(this);
        try {
            return original.call(pos, direction);
        } finally {
            // Includes Fabric cancellation returns, which an ordinary RETURN
            // injector does not necessarily see, and exceptional exits.
            InteractionSlotHooks.endBreaking(this);
        }
    }
}
