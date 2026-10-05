package dev.c4lam.celeste.mixin.module;

import dev.celestebeta.host.VisualHookBridge;
import dev.c4lam.celeste.compat.HandRenderingHooks;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HeldItemRenderer.class)
public abstract class HandModelsHeldItemMixin {
    @ModifyVariable(method = "renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemDisplayContext;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V",
        at = @At("HEAD"), argsOnly = true)
    private ItemDisplayContext celeste$screenFacing(ItemDisplayContext original, LivingEntity entity, ItemStack item,
                                                   ItemDisplayContext context, MatrixStack matrices,
                                                   OrderedRenderCommandQueue queue, int light) {
        return HandRenderingHooks.displayContext(original, item);
    }

    @WrapMethod(method = "renderItem(FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/network/ClientPlayerEntity;I)V")
    private void celeste$handShaders(float tickDelta, MatrixStack matrices, OrderedRenderCommandQueue queue,
                                    ClientPlayerEntity player, int light, Operation<Void> original) {
        VisualHookBridge.HandPass capture = HandRenderingHooks.begin();
        boolean success = false;
        try {
            // Includes the deferred dispatcher and immediate-provider flushes at the method tail.
            original.call(tickDelta, matrices, queue, player, light);
            success = true;
        } finally { VisualHookBridge.finishHands(capture, success); }
    }

    @Inject(method = "renderFirstPersonItem", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/util/math/MatrixStack;push()V", ordinal = 0, shift = At.Shift.AFTER))
    private void celeste$handModels(AbstractClientPlayerEntity player, float tickDelta, float pitch,
                                   Hand hand, float swingProgress, ItemStack item, float equipProgress,
                                   MatrixStack matrices, OrderedRenderCommandQueue queue, int light,
                                   CallbackInfo callback) {
        HandRenderingHooks.transform(player, hand, item, matrices);
    }
}
