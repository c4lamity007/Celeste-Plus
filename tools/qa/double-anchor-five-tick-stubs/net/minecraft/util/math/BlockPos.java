package net.minecraft.util.math;
public record BlockPos(int x,int y,int z) {
 public int getX(){return x;}public int getY(){return y;}public int getZ(){return z;}
 public BlockPos toImmutable(){return this;}public BlockPos up(){return new BlockPos(x,y+1,z);}
 public BlockPos down(){return new BlockPos(x,y-1,z);}public BlockPos offset(Direction side){return new BlockPos(x+side.x,y+side.y,z+side.z);}
 public Vec3d toCenterPos(){return Vec3d.ofCenter(this);}
}
