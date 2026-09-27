package net.minecraft.entity;
import java.util.UUID;import net.minecraft.util.math.*;
public class Entity {
 public boolean alive=true,removed;public final UUID id=UUID.randomUUID();public Vec3d position=new Vec3d(0,0,0);
 public boolean isAlive(){return alive;}public boolean canHit(){return true;}public boolean isAttackable(){return true;}public boolean isRemoved(){return removed;}
 public Vec3d getEyePos(){return position.add(0,1.6,0);}public UUID getUuid(){return id;}public double squaredDistanceTo(Entity other){return position.squaredDistanceTo(other.position);}
 public Box getBoundingBox(){return new Box(position.x-.3,position.y,position.z-.3,position.x+.3,position.y+1.8,position.z+.3);}
}
