package net.minecraft.item;
import net.minecraft.entity.player.PlayerEntity;import net.minecraft.util.Hand;import net.minecraft.util.hit.BlockHitResult;import net.minecraft.util.math.BlockPos;
public final class ItemPlacementContext {private final PlayerEntity player;private final BlockHitResult hit;
 public ItemPlacementContext(PlayerEntity player,Hand hand,ItemStack stack,BlockHitResult hit){this.player=player;this.hit=hit;}
 public BlockPos getBlockPos(){return player.world.getBlockState(hit.getBlockPos()).isReplaceable()?hit.getBlockPos():hit.getBlockPos().offset(hit.getSide());}
}
