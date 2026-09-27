package net.minecraft.world;
import net.minecraft.util.math.Vec3d;import net.minecraft.entity.Entity;
public final class RaycastContext {public enum ShapeType{OUTLINE}public enum FluidHandling{NONE}public RaycastContext(Vec3d from,Vec3d to,ShapeType shape,FluidHandling fluid,Entity entity){} }
