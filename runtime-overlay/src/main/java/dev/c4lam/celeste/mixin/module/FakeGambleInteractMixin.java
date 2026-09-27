package dev.c4lam.celeste.mixin.module;

import dev.c4lam.celeste.compat.FakeGambleHooks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class FakeGambleInteractMixin {
    @Inject(method = "interactBlock", at = @At("HEAD"), cancellable = true)
    private void celeste$noteFakeGambleDispenser(ClientPlayerEntity player, Hand hand, BlockHitResult hit,
                                                 CallbackInfoReturnable<ActionResult> cir) {
        ActionResult result = FakeGambleHooks.interactBlock(player, hand, hit);
        if (result != null) cir.setReturnValue(result);
    }

    @Inject(method = "interactItem", at = @At("HEAD"), cancellable = true)
    private void celeste$localItem(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        ActionResult result = FakeGambleHooks.interactItem(player, hand);
        if (result != null) cir.setReturnValue(result);
    }
    @Inject(method = "interactEntity", at = @At("HEAD"), cancellable = true)
    private void celeste$localEntity(PlayerEntity player, Entity entity, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        ActionResult result = FakeGambleHooks.interactEntity(player, hand);
        if (result != null) cir.setReturnValue(result);
    }
    @Inject(method = "interactEntityAtLocation", at = @At("HEAD"), cancellable = true)
    private void celeste$localEntityAt(PlayerEntity player, Entity entity, EntityHitResult hit, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        ActionResult result = FakeGambleHooks.interactEntity(player, hand);
        if (result != null) cir.setReturnValue(result);
    }
    @Inject(method = "attackEntity", at = @At("HEAD"), cancellable = true)
    private void celeste$localAttack(PlayerEntity player, Entity entity, CallbackInfo ci) {
        if (FakeGambleHooks.attackEntity(player)) ci.cancel();
    }
    @Inject(method = "attackBlock", at = @At("HEAD"), cancellable = true)
    private void celeste$localAttackBlock(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        Boolean result = FakeGambleHooks.attackBlock(pos, direction);
        if (result != null) cir.setReturnValue(result);
    }
    @Inject(method = "updateBlockBreakingProgress", at = @At("HEAD"), cancellable = true)
    private void celeste$localProgress(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        Boolean result = FakeGambleHooks.updateBlockBreakingProgress(pos, direction);
        if (result != null) cir.setReturnValue(result);
    }
    @Inject(method = "breakBlock", at = @At("HEAD"), cancellable = true)
    private void celeste$localBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Boolean result = FakeGambleHooks.breakBlock(pos);
        if (result != null) cir.setReturnValue(result);
    }
    @Inject(method = "cancelBlockBreaking", at = @At("HEAD"), cancellable = true)
    private void celeste$localCancel(CallbackInfo ci) { if (FakeGambleHooks.cancelBlockBreaking()) ci.cancel(); }

    @Inject(method = "clickCreativeStack", at = @At("HEAD"), cancellable = true)
    private void celeste$localCreativeStack(ItemStack stack, int slot, CallbackInfo ci) {
        if (FakeGambleHooks.creativeStack(stack, slot)) ci.cancel();
    }
    @Inject(method = "dropCreativeStack", at = @At("HEAD"), cancellable = true)
    private void celeste$localCreativeDrop(ItemStack stack, CallbackInfo ci) {
        if (FakeGambleHooks.creativeDrop(stack)) ci.cancel();
    }
}
