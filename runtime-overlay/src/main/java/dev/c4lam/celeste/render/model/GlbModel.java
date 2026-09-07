package dev.c4lam.celeste.render.model;

import java.util.List;

/**
 * Cached, validated GLB geometry. Arrays are owned by this model and are read-only
 * to callers; reusing them avoids copying geometry on every animation frame.
 * Coordinates and matrices retain glTF's right-handed, Y-up, column-major convention.
 */
public record GlbModel(List<Mesh> meshes, List<Node> nodes, List<Skin> skins,
                       List<Material> materials, List<Image> images, List<Animation> animations,
                       int[] sceneRoots, int[] sceneNodes, int[] topologicalOrder) {
    public GlbModel {
        meshes = List.copyOf(meshes);
        nodes = List.copyOf(nodes);
        skins = List.copyOf(skins);
        materials = List.copyOf(materials);
        images = List.copyOf(images);
        animations = List.copyOf(animations);
    }

    public record Mesh(String name, List<Primitive> primitives) {
        public Mesh { primitives = List.copyOf(primitives); }
    }

    /** JOINTS/WEIGHTS are null for rigid geometry; material always indexes materials(). */
    public record Primitive(float[] positions, float[] normals, float[] texCoords,
                            int[] joints, float[] weights, int[] indices, int material) {
        public int vertexCount() { return positions.length / 3; }
    }

    /** matrix is null for TRS nodes; mesh and skin use -1 for absent. */
    public record Node(String name, int mesh, int skin, int[] children,
                       float[] translation, float[] rotation, float[] scale, float[] matrix) {}

    public record Skin(String name, int[] joints, float[] inverseBindMatrices) {}

    /** image indexes images(); -1 means an untextured base-color factor. */
    public record Material(String name, float[] baseColorFactor, int image,
                           boolean doubleSided, AlphaMode alphaMode, float alphaCutoff,
                           int wrapS, int wrapT, boolean linearFiltering) {}

    /** Top-to-bottom RGBA8, straight alpha; PNG/JPEG decoding happens once at import. */
    public record Image(String name, int width, int height, byte[] rgba) {}

    public record Animation(String name, float duration, List<Channel> channels) {
        public Animation { channels = List.copyOf(channels); }
    }

    public record Channel(int node, Target target, Interpolation interpolation,
                          float[] times, float[] values) {}

    public enum Target { TRANSLATION, ROTATION, SCALE }
    public enum Interpolation { LINEAR, STEP }
    public enum AlphaMode { OPAQUE, MASK, BLEND }
}
