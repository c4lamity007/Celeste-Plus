package dev.c4lam.celeste.module.impl.visual.expansion;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

/** Camera-space grip placement. No Minecraft state, screen pixels or item-model substitutions. */
public final class HandModelsPose {
    public static final float DEPTH = 0.72F;
    public static final float CENTER_DEPTH = 0.82F;
    public static final float ARC_RADIUS = 1.64F;
    public static final float ARC_CENTER_Z = ARC_RADIUS - CENTER_DEPTH;
    public static final float AWAY_PITCH = -10.0F;

    private HandModelsPose() { }

    public static Matrix4f transform(boolean right, double x, double y, double aspect, double fov) {
        return transform(right, x, y, aspect, fov, 1.0);
    }

    /** The center grid cell keeps the physical hand's native display context. */
    public static boolean screenRight(boolean physicalRight, double x) {
        x = HandModelsLayout.bounded(physicalRight, x, .78).x();
        return x < .49 ? false : x > .51 ? true : physicalRight;
    }

    public static Matrix4f transform(boolean right, double x, double y, double aspect, double fov, double itemSize) {
        return transform(right, x, y, aspect, fov, itemSize, false);
    }

    /** Convenience for the vanilla handheld sword basis and its in-plane lean. */
    public static Matrix4f transform(boolean right, double x, double y, double aspect, double fov,
                                     double itemSize, boolean flatHandheld) {
        return transform(right, x, y, aspect, fov, itemSize,
            flatHandheld ? new Quaternionf().rotationY((float) (Math.PI * .5)) : null, flatHandheld);
    }

    /** Cylindrical wall: horizontal curvature only; vertical placement never changes orientation. */
    public static Matrix4f transform(boolean right, double x, double y, double aspect, double fov,
                                     double itemSize, Quaternionfc facingCorrection, boolean swordLean) {
        HandModelsLayout.Position position = HandModelsLayout.bounded(right, x, y);
        aspect = Double.isFinite(aspect) && aspect > 0 ? aspect : 16.0 / 9.0;
        fov = Double.isFinite(fov) ? Math.clamp(fov, 1.0, 179.0) : 70.0;
        double tangent = Math.tan(Math.toRadians(fov * 0.5));
        double ray = (2.0 * position.x() - 1.0) * tangent * aspect;
        // A cylinder centered behind the eye is gentler than the old camera-
        // centered semicircle. Intersect its front with the cursor's view ray.
        double wall = ARC_RADIUS * ARC_RADIUS - ARC_CENTER_Z * ARC_CENTER_Z;
        double depth = wall / (ARC_CENTER_Z + Math.sqrt(ARC_CENTER_Z * ARC_CENTER_Z + (1 + ray * ray) * wall));
        double horizontal = ray * depth;
        double angle = Math.atan2(horizontal, depth + ARC_CENTER_Z);
        double halfHeight = depth * tangent;
        double height = (1.0 - 2.0 * position.y()) * halfHeight;
        Matrix4f pose = new Matrix4f()
            .translation((float) horizontal, (float) height, (float) -depth)
            .rotateY((float) -angle)
            .rotateX((float) Math.toRadians(AWAY_PITCH));
        if (swordLean) {
            // Same front face on either side. Reverse lean by rolling in its
            // own plane, never by flipping the mesh or exposing its back.
            // The sprite's 45-degree diagonal plus its authored 25-degree roll
            // points at 70 degrees; adding 40 makes the matching 110-degree lean.
            double side = Math.clamp((position.x() - .46) / .08, 0.0, 1.0);
            side = side * side * (3.0 - 2.0 * side);
            pose.rotateZ((float) Math.toRadians(40.0 * (1.0 - side)));
        }
        if (facingCorrection != null) pose.rotate(facingCorrection);
        return pose.scale((float) HandModelsLayout.size(itemSize))
            .translate(right ? -0.56F : 0.56F, 0.52F, DEPTH);
    }
}
