package net.minecraft.util.hit;
import net.minecraft.util.math.*;
public final class BlockHitResult extends HitResult {
 private final BlockPos pos;private final Direction side;
 public BlockHitResult(Vec3d hit,Direction side,BlockPos pos,boolean inside){super(Type.BLOCK,hit);this.pos=pos;this.side=side;}
 private BlockHitResult(){super(Type.MISS,new Vec3d(0,0,0));pos=new BlockPos(0,0,0);side=Direction.UP;}
 public static BlockHitResult miss(){return new BlockHitResult();}
 public BlockPos getBlockPos(){return pos;}public Direction getSide(){return side;}
 public BlockHitResult withBlockPos(BlockPos pos){return new BlockHitResult(getPos(),side,pos,false);}
}
