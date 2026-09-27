package net.minecraft.util.hit;
import net.minecraft.util.math.Vec3d;
public class HitResult {public enum Type {MISS,BLOCK,ENTITY}private final Type type;private final Vec3d pos;public HitResult(Type type,Vec3d pos){this.type=type;this.pos=pos;}public Type getType(){return type;}public Vec3d getPos(){return pos;} }
