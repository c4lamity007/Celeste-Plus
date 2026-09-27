package net.minecraft.util.math;
public final class Box {
 public final double x1,y1,z1,x2,y2,z2;
 public Box(double x1,double y1,double z1,double x2,double y2,double z2){this.x1=x1;this.y1=y1;this.z1=z1;this.x2=x2;this.y2=y2;this.z2=z2;}
 public Box(BlockPos pos){this(pos.x(),pos.y(),pos.z(),pos.x()+1,pos.y()+1,pos.z()+1);}
 public Box contract(double n){return new Box(x1+n,y1+n,z1+n,x2-n,y2-n,z2-n);}
 public Box expand(double n){return contract(-n);}public Box stretch(Vec3d v){return this;}
 public boolean intersects(Box b){return x1<b.x2&&x2>b.x1&&y1<b.y2&&y2>b.y1&&z1<b.z2&&z2>b.z1;}
}
