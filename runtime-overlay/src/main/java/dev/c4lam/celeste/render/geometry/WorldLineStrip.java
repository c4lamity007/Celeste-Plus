package dev.c4lam.celeste.render.geometry;
import java.util.List;
import net.minecraft.util.math.Vec3d;
/** Parent-owned line vertices; no module implementation is retained in renderer state. */
public record WorldLineStrip(List<Vec3d> points, int color, float width) { }
