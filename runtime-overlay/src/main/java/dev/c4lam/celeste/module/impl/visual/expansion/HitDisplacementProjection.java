package dev.c4lam.celeste.module.impl.visual.expansion;

import org.joml.Matrix4fc;
import org.joml.Vector4f;

/** The same event transform followed by model-view transform used for world vertices. */
public final class HitDisplacementProjection {
    private HitDisplacementProjection() { }

    public static HitDisplacementWorld.Point view(HitDisplacementWorld.Point at,
            HitDisplacementWorld.Point camera, Matrix4fc eventTransform, Matrix4fc modelView) {
        if (at == null || camera == null || !at.finite() || !camera.finite()
            || eventTransform == null || modelView == null || !eventTransform.isFinite() || !modelView.isFinite()) return null;
        // Subtract in doubles before conversion so distant world coordinates stay precise.
        Vector4f view = new Vector4f((float)(at.x()-camera.x()), (float)(at.y()-camera.y()),
            (float)(at.z()-camera.z()), 1);
        eventTransform.transform(view);
        modelView.transform(view);
        return Float.isFinite(view.x) && Float.isFinite(view.y) && Float.isFinite(view.z)
            ? new HitDisplacementWorld.Point(view.x, view.y, view.z) : null;
    }
}
