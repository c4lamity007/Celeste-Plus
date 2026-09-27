package net.minecraft.util.math;
public final class Vec3d {
 public final double x,y,z;public Vec3d(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
 public Vec3d add(Vec3d other){return new Vec3d(x+other.x,y+other.y,z+other.z);}
 public Vec3d add(double x,double y,double z){return new Vec3d(this.x+x,this.y+y,this.z+z);}
 public Vec3d subtract(Vec3d other){return new Vec3d(x-other.x,y-other.y,z-other.z);}
 public Vec3d multiply(double scale){return new Vec3d(x*scale,y*scale,z*scale);}
 public double lengthSquared(){return x*x+y*y+z*z;}public double squaredDistanceTo(Vec3d other){return subtract(other).lengthSquared();}
 public static Vec3d ofCenter(BlockPos pos){return new Vec3d(pos.x()+.5,pos.y()+.5,pos.z()+.5);}
}
