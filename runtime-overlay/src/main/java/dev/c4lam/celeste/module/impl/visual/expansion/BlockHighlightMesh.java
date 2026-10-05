package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/** Exact, bounded union boundary for a block's VoxelShape boxes. No game dependency. */
public final class BlockHighlightMesh {
    public static final int MAX_FACES = 192;
    public static final int MAX_BOXES = 96;
    public static final int MAX_AXIS_CELLS = 32;

    private BlockHighlightMesh() { }

    public record Cuboid(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double min(int axis) { return axis == 0 ? minX : axis == 1 ? minY : minZ; }
        double max(int axis) { return axis == 0 ? maxX : axis == 1 ? maxY : maxZ; }
    }

    /**
     * Only exterior faces of the union, with outward winding and white color.
     * Coordinate compression preserves exact slab/stair/fence dimensions rather
     * than rasterizing to a coarse grid or substituting an enclosing box.
     * Coplanar cells are greedily merged. Invalid or over-budget shapes return
     * no mesh at all: a truncated, open highlight must never be displayed.
     */
    public static List<SolidVisualMesh.Face> surface(List<Cuboid> boxes, int maxFaces) {
        if (boxes == null || boxes.isEmpty() || boxes.size() > MAX_BOXES || maxFaces < 6) return List.of();
        int limit = Math.min(MAX_FACES, maxFaces);
        for (Cuboid box : boxes) {
            if (box == null) return List.of();
            for (int axis = 0; axis < 3; axis++) {
                if (!localCoordinate(box.min(axis)) || !localCoordinate(box.max(axis))
                    || box.min(axis) >= box.max(axis)) return List.of();
            }
        }
        double[][] edges = new double[3][];
        int[] size = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            TreeSet<Double> values = new TreeSet<>();
            for (Cuboid box : boxes) {
                // Signed zero is a single plane, not a zero-width grid cell.
                values.add(zero(box.min(axis)));
                values.add(zero(box.max(axis)));
                if (values.size() > MAX_AXIS_CELLS + 1) return List.of();
            }
            edges[axis] = values.stream().mapToDouble(Double::doubleValue).toArray();
            size[axis] = edges[axis].length - 1;
        }
        // At most 32^3 cells and 96 input cuboids, independent of world size.
        boolean[] filled = new boolean[size[0] * size[1] * size[2]];
        for (Cuboid box : boxes) {
            int x0 = Arrays.binarySearch(edges[0], zero(box.minX()));
            int x1 = Arrays.binarySearch(edges[0], zero(box.maxX()));
            int y0 = Arrays.binarySearch(edges[1], zero(box.minY()));
            int y1 = Arrays.binarySearch(edges[1], zero(box.maxY()));
            int z0 = Arrays.binarySearch(edges[2], zero(box.minZ()));
            int z1 = Arrays.binarySearch(edges[2], zero(box.maxZ()));
            for (int x = x0; x < x1; x++) for (int y = y0; y < y1; y++)
                Arrays.fill(filled, (x * size[1] + y) * size[2] + z0,
                    (x * size[1] + y) * size[2] + z1, true);
        }
        List<SolidVisualMesh.Face> result = new ArrayList<>(Math.min(24, limit));
        for (int axis = 0; axis < 3; axis++) {
            int u = (axis + 1) % 3, v = (axis + 2) % 3;
            int width = size[u], height = size[v];
            byte[] mask = new byte[width * height];
            for (int plane = 0; plane <= size[axis]; plane++) {
                for (int row = 0; row < height; row++) for (int column = 0; column < width; column++) {
                    boolean before = occupied(filled, size, axis, plane - 1, column, row);
                    boolean after = occupied(filled, size, axis, plane, column, row);
                    mask[row * width + column] = (byte) (before == after ? 0 : before ? 1 : -1);
                }
                for (int row = 0; row < height; row++) for (int column = 0; column < width; column++) {
                    byte direction = mask[row * width + column];
                    if (direction == 0) continue;
                    if (result.size() >= limit) return List.of();
                    int span = 1;
                    while (column + span < width && mask[row * width + column + span] == direction) span++;
                    int rows = 1;
                    extend: while (row + rows < height) {
                        for (int i = 0; i < span; i++)
                            if (mask[(row + rows) * width + column + i] != direction) break extend;
                        rows++;
                    }
                    for (int j = 0; j < rows; j++)
                        Arrays.fill(mask, (row + j) * width + column, (row + j) * width + column + span, (byte) 0);
                    double level = edges[axis][plane];
                    SolidVisualMesh.Point a = point(axis, level, edges[u][column], edges[v][row]);
                    SolidVisualMesh.Point b = point(axis, level, edges[u][column + span], edges[v][row]);
                    SolidVisualMesh.Point c = point(axis, level, edges[u][column + span], edges[v][row + rows]);
                    SolidVisualMesh.Point d = point(axis, level, edges[u][column], edges[v][row + rows]);
                    result.add(direction > 0 ? face(a, b, c, d, 0xFFFFFFFF) : face(a, d, c, b, 0xFFFFFFFF));
                }
            }
        }
        return List.copyOf(result);
    }

    /** Translate/tint a complete cached local mesh, or emit nothing if it cannot fit. */
    public static List<SolidVisualMesh.Face> materialize(List<SolidVisualMesh.Face> mesh,
                                                        double x, double y, double z, int argb, int faceBudget) {
        if (mesh == null || mesh.isEmpty() || mesh.size() > Math.min(MAX_FACES, faceBudget)
            || (argb >>> 24) == 0 || !worldCoordinate(x) || !worldCoordinate(y) || !worldCoordinate(z)) return List.of();
        List<SolidVisualMesh.Face> result = new ArrayList<>(mesh.size());
        for (SolidVisualMesh.Face face : mesh) {
            if (face == null || !valid(face.a()) || !valid(face.b()) || !valid(face.c()) || !valid(face.d())) return List.of();
            result.add(face(offset(face.a(), x, y, z), offset(face.b(), x, y, z),
                offset(face.c(), x, y, z), offset(face.d(), x, y, z), argb));
        }
        return List.copyOf(result);
    }

    private static boolean occupied(boolean[] cells, int[] size, int axis, int plane, int u, int v) {
        if (plane < 0 || plane >= size[axis]) return false;
        int x = axis == 0 ? plane : axis == 1 ? v : u;
        int y = axis == 1 ? plane : axis == 2 ? v : u;
        int z = axis == 2 ? plane : axis == 0 ? v : u;
        return cells[(x * size[1] + y) * size[2] + z];
    }

    private static SolidVisualMesh.Point point(int axis, double plane, double u, double v) {
        return axis == 0 ? new SolidVisualMesh.Point(plane, u, v)
            : axis == 1 ? new SolidVisualMesh.Point(v, plane, u) : new SolidVisualMesh.Point(u, v, plane);
    }
    private static SolidVisualMesh.Face face(SolidVisualMesh.Point a, SolidVisualMesh.Point b,
                                             SolidVisualMesh.Point c, SolidVisualMesh.Point d, int color) {
        return new SolidVisualMesh.Face(a, b, c, d, color, color, 0, 1);
    }
    private static SolidVisualMesh.Point offset(SolidVisualMesh.Point p, double x, double y, double z) {
        return new SolidVisualMesh.Point(p.x() + x, p.y() + y, p.z() + z);
    }
    private static boolean valid(SolidVisualMesh.Point p) {
        return p != null && localCoordinate(p.x()) && localCoordinate(p.y()) && localCoordinate(p.z());
    }
    private static boolean localCoordinate(double value) { return Double.isFinite(value) && Math.abs(value) <= 16; }
    private static boolean worldCoordinate(double value) { return Double.isFinite(value) && Math.abs(value) <= 60_000_000; }
    private static double zero(double value) { return value == 0 ? 0 : value; }
}
