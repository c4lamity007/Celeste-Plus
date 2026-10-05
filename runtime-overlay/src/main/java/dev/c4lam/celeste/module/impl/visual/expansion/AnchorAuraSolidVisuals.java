package dev.c4lam.celeste.module.impl.visual.expansion;

import net.minecraft.util.math.Vec3d;
import java.util.List;

/** Minecraft adapter for the bounded, independently tested volume geometry. */
final class AnchorAuraSolidVisuals {
    private AnchorAuraSolidVisuals() { }

    static int append(List<AnchorAuraWorldGlowRenderer.Quad> output, List<AnchorAuraSolidVisualMesh.Node> nodes,
                      AnchorAuraSolidVisualMesh.Point referenceAxis, int sides, int limit, boolean closed) {
        if (output == null) return 0;
        int available = Math.min(768, limit) - output.size();
        if (available < Math.max(4, sides)) return 0;
        return faces(output, AnchorAuraSolidVisualMesh.tube(nodes, referenceAxis, sides, available, closed), limit);
    }

    static int faces(List<AnchorAuraWorldGlowRenderer.Quad> output, List<AnchorAuraSolidVisualMesh.Face> faces, int limit) {
        if (output == null || faces == null) return 0;
        int before = output.size();
        int cap = Math.min(768, limit);
        for (AnchorAuraSolidVisualMesh.Face face : faces) {
            if (output.size() >= cap) break;
            output.add(new AnchorAuraWorldGlowRenderer.Quad(AnchorAuraWorldGlowRenderer.Texture.VOLUME,
                point(face.a()), point(face.b()), point(face.c()), point(face.d()),
                face.color(), face.endColor(), face.uStart(), face.uEnd()));
        }
        return output.size() - before;
    }

    private static Vec3d point(AnchorAuraSolidVisualMesh.Point point) {
        return new Vec3d(point.x(), point.y(), point.z());
    }
}
