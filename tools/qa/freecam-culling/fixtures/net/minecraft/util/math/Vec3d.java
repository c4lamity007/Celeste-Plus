package net.minecraft.util.math;
public final class Vec3d {
    public final double x, y, z;
    public Vec3d(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
    public double lengthSquared() { return x*x + y*y + z*z; }
    public Vec3d add(Vec3d other) { return new Vec3d(x+other.x, y+other.y, z+other.z); }
    public Vec3d multiply(double factor) { return new Vec3d(x*factor, y*factor, z*factor); }
    public Vec3d normalize() {
        double length = Math.sqrt(lengthSquared());
        return length < 1.0E-12D ? new Vec3d(0, 0, 0) : multiply(1.0D/length);
    }
}
