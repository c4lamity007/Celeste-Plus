package net.minecraft.util.hit;
import net.minecraft.entity.Entity;
public final class EntityHitResult extends HitResult {private final Entity entity;public EntityHitResult(Entity entity){super(Type.ENTITY,entity.getEyePos());this.entity=entity;}public Entity getEntity(){return entity;} }
