package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.List;
import org.joml.Quaternionf;
import org.joml.Vector3fc;

/** Deterministic model-facing classification, independent of Minecraft and its renderer. */
public final class HandModelsBasis {
    public static final int MAX_LAYERS = 8;

    public record Layer(boolean special, Vector3fc minimum, Vector3fc maximum,
                        Vector3fc rotationDegrees, Vector3fc scale) { }

    private HandModelsBasis() { }

    /** Null means preserve authored facing, never guess a plane for 3D/special models. */
    public static Quaternionf correction(List<Layer> layers, boolean left) {
        if (layers == null || layers.isEmpty() || layers.size() > MAX_LAYERS) return null;
        Quaternionf result = null;
        for (Layer layer : layers) {
            if (layer == null || layer.special() || !isFlatXY(layer.minimum(), layer.maximum())) return null;
            Vector3fc angles = layer.rotationDegrees(), scale = layer.scale();
            if (!finite(angles) || !finite(scale) || scale.x() <= 0 || scale.y() <= 0 || scale.z() <= 0) return null;
            float radians = (float) (Math.PI / 180.0);
            float x = angles.x() * radians;
            float y = angles.y() * radians * (left ? -1 : 1);
            float z = angles.z() * radians * (left ? -1 : 1);
            // Transformation.apply mirrors Y and Z, but NOT X, for a left hand.
            // Remove only its out-of-plane basis; retain its authored in-plane Z.
            Quaternionf authored = new Quaternionf().rotationXYZ(x, y, z);
            Quaternionf next = new Quaternionf().rotationZ(z).mul(authored.conjugate()).normalize();
            if (!Float.isFinite(next.x) || !Float.isFinite(next.y) || !Float.isFinite(next.z) || !Float.isFinite(next.w)) return null;
            if (result != null && Math.abs(result.dot(next)) < .99999F) return null;
            result = next;
        }
        return result;
    }

    public static boolean isFlatXY(Vector3fc minimum, Vector3fc maximum) {
        if (!finite(minimum) || !finite(maximum)) return false;
        float width = maximum.x() - minimum.x();
        float height = maximum.y() - minimum.y();
        float thickness = maximum.z() - minimum.z();
        return Float.isFinite(width) && Float.isFinite(height) && Float.isFinite(thickness)
            && width > .0001F && height > .0001F && thickness >= 0 && thickness <= .125F
            && thickness <= .25F * Math.min(width, height);
    }

    private static boolean finite(Vector3fc vector) {
        return vector != null && Float.isFinite(vector.x()) && Float.isFinite(vector.y()) && Float.isFinite(vector.z());
    }
}
