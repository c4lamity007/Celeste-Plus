package dev.c4lam.celeste.render.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded offline glTF 2 GLB importer. It never follows a URI or executes model code. */
public final class GlbImporter {
    public static final int MAX_FILE_BYTES = 64 * 1024 * 1024;
    public static final int MAX_VERTICES = 500_000;
    public static final int MAX_INDICES = 1_500_000;
    public static final int MAX_NODES = 1024;
    public static final int MAX_JOINTS = 256;
    public static final int MAX_IMAGE_DIMENSION = 4096;
    private static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    private static final int MAX_DECODED_BYTES = 64 * 1024 * 1024;
    private static final int MAX_IMAGE_PIXELS = 16 * 1024 * 1024;
    private final JsonObject json;
    private final byte[] bin;
    private final List<View> views = new ArrayList<>();
    private final List<Accessor> accessors = new ArrayList<>();
    private final Map<Integer, float[]> floatCache = new HashMap<>();
    private final Map<Integer, int[]> integerCache = new HashMap<>();
    private long decodedBytes;
    private long decodedPixels;
    private int vertices;
    private int indices;
    private int primitiveCount;

    private GlbImporter(JsonObject json, byte[] bin) { this.json = json; this.bin = bin; }

    public static GlbModel load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw bad("Select one regular .glb model file");
        if (Files.size(path) > MAX_FILE_BYTES) throw bad("GLB exceeds the 64 MiB file limit");
        try (InputStream input = Files.newInputStream(path)) {
            return parse(input.readNBytes(MAX_FILE_BYTES + 1));
        }
    }

    public static GlbModel parse(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 20 || bytes.length > MAX_FILE_BYTES) {
            throw bad("GLB must be between 20 bytes and 64 MiB");
        }
        try {
            ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            check(input.getInt() == 0x46546c67, "Not a binary glTF (.glb) file");
            check(input.getInt() == 2, "Only GLB version 2 is supported");
            check(Integer.toUnsignedLong(input.getInt()) == bytes.length, "GLB length does not match its header");
            byte[] binary = null;
            JsonObject document = null;
            int chunks = 0;
            while (input.hasRemaining()) {
                check(input.remaining() >= 8, "Truncated GLB chunk header");
                long length = Integer.toUnsignedLong(input.getInt());
                int type = input.getInt();
                check(length % 4 == 0 && length <= input.remaining(), "Invalid or truncated GLB chunk length");
                check(++chunks <= 2, "Only JSON plus one embedded BIN chunk are supported");
                if (type == 0x4e4f534a && document == null && chunks == 1) {
                    check(length <= MAX_JSON_BYTES, "GLB JSON exceeds the 8 MiB limit");
                    ByteBuffer text = input.slice(input.position(), (int) length);
                    String decoded;
                    try {
                        decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(text).toString();
                    } catch (CharacterCodingException failure) { throw bad("GLB JSON is not valid UTF-8"); }
                    try (JsonReader reader = new JsonReader(new StringReader(decoded))) {
                        reader.setStrictness(Strictness.STRICT);
                        document = object(readJson(reader, 0, new int[]{0}), "GLB JSON root");
                        check(reader.peek() == JsonToken.END_DOCUMENT, "Trailing data after GLB JSON");
                    }
                    input.position(input.position() + (int) length);
                } else if (type == 0x004e4942 && document != null && binary == null) {
                    binary = new byte[(int) length];
                    input.get(binary);
                } else throw bad("Unsupported GLB chunk; expected JSON followed by one BIN chunk");
            }
            check(document != null && binary != null, "A self-contained GLB JSON and BIN chunk are required");
            return new GlbImporter(document, binary).read();
        } catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException failure) {
            throw new IOException("Invalid GLB data: " + failure.getMessage(), failure);
        }
    }

    private static JsonElement readJson(JsonReader reader, int depth, int[] count) throws IOException {
        check(depth <= 64 && ++count[0] <= 300_000, "GLB JSON nesting or element count exceeds the limit");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject result = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    check(key.length() <= 256 && !result.has(key), "Overlong or duplicate JSON property");
                    result.add(key, readJson(reader, depth + 1, count));
                }
                reader.endObject();
                yield result;
            }
            case BEGIN_ARRAY -> {
                JsonArray result = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) result.add(readJson(reader, depth + 1, count));
                reader.endArray();
                yield result;
            }
            case STRING -> {
                String value = reader.nextString();
                check(value.length() <= 8192, "GLB JSON string exceeds the limit; embed image data in BIN, not a URI");
                yield new JsonPrimitive(value);
            }
            case NUMBER -> {
                String value = reader.nextString();
                check(value.length() <= 64 && Double.isFinite(Double.parseDouble(value)), "Non-finite or overlong JSON number");
                yield new JsonPrimitive(new BigDecimal(value));
            }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw bad("Unexpected GLB JSON token");
        };
    }

    private GlbModel read() throws IOException {
        JsonObject asset = object(json.get("asset"), "asset");
        check("2.0".equals(string(asset, "version", "")), "Only glTF asset version 2.0 is supported");
        check(!asset.has("minVersion") || "2.0".equals(string(asset, "minVersion", "")), "This model requires a newer glTF version");
        JsonArray required = array(json, "extensionsRequired", 64);
        check(required.isEmpty(), "Required glTF extensions are unsupported; export standard uncompressed glTF 2.0: " + required);
        readBuffers();
        List<GlbModel.Image> images = readImages();
        List<Texture> textures = readTextures(images.size());
        List<GlbModel.Material> materials = readMaterials(textures);
        List<GlbModel.Mesh> meshes = readMeshes(materials);
        List<GlbModel.Node> nodes = readNodes(meshes.size());
        int[][] hierarchy = hierarchy(nodes);
        List<GlbModel.Skin> skins = readSkins(nodes, hierarchy[0]);
        for (GlbModel.Node node : nodes) {
            if (node.skin() < 0) continue;
            check(node.skin() < skins.size() && node.mesh() >= 0, "A skin must reference an existing mesh and skin");
            int jointCount = skins.get(node.skin()).joints().length;
            for (GlbModel.Primitive primitive : meshes.get(node.mesh()).primitives()) {
                check(primitive.joints() != null, "Skinned mesh requires JOINTS_0 and WEIGHTS_0");
                for (int joint : primitive.joints()) check(joint < jointCount, "Vertex joint index is outside its skin palette");
            }
        }
        List<GlbModel.Animation> animations = readAnimations(nodes);
        int[] roots = sceneRoots(nodes, hierarchy[0]);
        boolean[] visible = new boolean[nodes.size()];
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        for (int root : roots) pending.add(root);
        while (!pending.isEmpty()) {
            int node = pending.removeFirst();
            visible[node] = true;
            for (int child : nodes.get(node).children()) pending.addLast(child);
        }
        int[] selected = Arrays.stream(hierarchy[1]).filter(node -> visible[node]).toArray();
        check(Arrays.stream(selected).anyMatch(node -> nodes.get(node).mesh() >= 0), "The selected scene contains no mesh to render");
        GlbModel model = new GlbModel(meshes, nodes, skins, materials, images, animations, roots, selected, hierarchy[1]);
        // Finite individual transforms can still overflow when composed. Validate
        // the bind hierarchy/palettes once before handing any model to a renderer.
        new GlbPose(model);
        return model;
    }

    private void readBuffers() throws IOException {
        JsonArray buffers = array(json, "buffers", 1);
        check(buffers.size() == 1, "Exactly one embedded GLB buffer is supported");
        JsonObject buffer = object(buffers.get(0), "buffer");
        noUri(buffer, "buffer");
        int length = integer(buffer, "byteLength", -1);
        check(length >= 0 && length <= bin.length && bin.length - length <= 3, "BIN length or padding differs from buffer.byteLength");
        for (JsonElement element : array(json, "bufferViews", 8192)) {
            JsonObject value = object(element, "bufferView");
            noExtensions(value, "bufferView compression");
            check(integer(value, "buffer", -1) == 0, "Buffer view references an external buffer");
            int start = integer(value, "byteOffset", 0), size = integer(value, "byteLength", -1);
            int stride = integer(value, "byteStride", 0);
            check(start >= 0 && size > 0 && (long) start + size <= length, "Buffer view exceeds the embedded BIN data");
            check(stride == 0 || stride >= 4 && stride <= 252 && stride % 4 == 0 && stride <= size, "Unsupported buffer view stride");
            views.add(new View(start, size, stride));
        }
        for (JsonElement element : array(json, "accessors", 8192)) {
            JsonObject value = object(element, "accessor");
            check(!value.has("sparse"), "Sparse accessors are unsupported; export dense vertex/animation buffers");
            noExtensions(value, "accessor extensions");
            int view = index(value, "bufferView", views.size());
            int offset = integer(value, "byteOffset", 0), count = integer(value, "count", -1);
            int component = integer(value, "componentType", -1);
            int width = switch (component) { case 5120, 5121 -> 1; case 5122, 5123 -> 2; case 5125, 5126 -> 4; default -> throw bad("Unsupported accessor component type"); };
            String type = string(value, "type", "");
            int components = switch (type) { case "SCALAR" -> 1; case "VEC2" -> 2; case "VEC3" -> 3; case "VEC4" -> 4; case "MAT4" -> 16; default -> throw bad("Unsupported accessor shape: " + type); };
            check(!type.equals("MAT4") || component == 5126, "Only floating-point MAT4 accessors are supported");
            View data = views.get(view);
            int size = width * components, stride = data.stride == 0 ? size : data.stride;
            check(count > 0 && count <= MAX_INDICES && offset >= 0 && offset % width == 0
                    && (data.start + offset) % width == 0 && stride >= size && stride % width == 0
                    && (long) offset + (long) (count - 1) * stride + size <= data.length, "Accessor range, count, alignment or stride is invalid");
            boolean normalized = bool(value, "normalized", false);
            check(!normalized || component != 5125 && component != 5126, "Only byte/short accessors may be normalized");
            accessors.add(new Accessor(data.start + offset, count, component, components, stride, normalized));
        }
    }

    private List<GlbModel.Image> readImages() throws IOException {
        List<GlbModel.Image> result = new ArrayList<>();
        for (JsonElement element : array(json, "images", 32)) {
            JsonObject image = object(element, "image");
            noUri(image, "image");
            String mime = string(image, "mimeType", "");
            check(mime.equals("image/png") || mime.equals("image/jpeg"), "Only embedded PNG or JPEG base-color images are supported");
            View view = views.get(index(image, "bufferView", views.size()));
            check(view.stride == 0, "Embedded image buffer view cannot have byteStride");
            boolean png = view.length >= 8 && bin[view.start] == (byte) 137 && bin[view.start + 1] == 80
                    && bin[view.start + 2] == 78 && bin[view.start + 3] == 71 && bin[view.start + 4] == 13
                    && bin[view.start + 5] == 10 && bin[view.start + 6] == 26 && bin[view.start + 7] == 10;
            boolean jpeg = view.length >= 3 && bin[view.start] == (byte) 255 && bin[view.start + 1] == (byte) 216 && bin[view.start + 2] == (byte) 255;
            check(mime.equals("image/png") ? png : jpeg, "Embedded image signature does not match its PNG/JPEG MIME type");
            try (MemoryCacheImageInputStream stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bin, view.start, view.length))) {
                var readers = ImageIO.getImageReaders(stream);
                check(readers.hasNext(), "No Java PNG/JPEG decoder is available");
                ImageReader reader = readers.next();
                try {
                    String format = reader.getFormatName();
                    check(format.equalsIgnoreCase("png") || format.equalsIgnoreCase("jpeg") || format.equalsIgnoreCase("jpg"), "Unexpected image decoder format");
                    reader.setInput(stream, true, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    long pixels = (long) width * height;
                    check(width > 0 && height > 0 && width <= MAX_IMAGE_DIMENSION && height <= MAX_IMAGE_DIMENSION,
                            "Embedded image exceeds 4096 x 4096 pixels");
                    check((decodedPixels += pixels) <= MAX_IMAGE_PIXELS, "Embedded images exceed the total 64 MiB decoded RGBA limit");
                    BufferedImage decoded = reader.read(0);
                    check(decoded != null && decoded.getWidth() == width && decoded.getHeight() == height, "Could not decode embedded image");
                    byte[] rgba = new byte[(int) pixels * 4];
                    int[] row = new int[width];
                    for (int y = 0; y < height; y++) {
                        decoded.getRGB(0, y, width, 1, row, 0, width);
                        for (int x = 0; x < width; x++) {
                            int pixel = row[x], at = (y * width + x) * 4;
                            rgba[at] = (byte) (pixel >>> 16); rgba[at + 1] = (byte) (pixel >>> 8);
                            rgba[at + 2] = (byte) pixel; rgba[at + 3] = (byte) (pixel >>> 24);
                        }
                    }
                    decoded.flush();
                    result.add(new GlbModel.Image(string(image, "name", "Image " + result.size()), width, height, rgba));
                } finally { reader.dispose(); }
            }
        }
        return result;
    }

    private List<Texture> readTextures(int images) throws IOException {
        List<Texture> result = new ArrayList<>();
        JsonArray samplers = array(json, "samplers", 256);
        for (JsonElement element : array(json, "textures", 256)) {
            JsonObject texture = object(element, "texture");
            noExtensions(texture, "compressed/extended textures");
            int image = index(texture, "source", images), s = 10497, t = 10497;
            boolean linear = true;
            if (texture.has("sampler")) {
                JsonObject sampler = object(samplers.get(index(texture, "sampler", samplers.size())), "texture sampler");
                s = integer(sampler, "wrapS", 10497); t = integer(sampler, "wrapT", 10497);
                check(wrap(s) && wrap(t), "Unsupported texture wrapping mode");
                int mag = integer(sampler, "magFilter", 9729), min = integer(sampler, "minFilter", 9987);
                check(mag == 9728 || mag == 9729, "Unsupported magnification filter");
                check(min == 9728 || min == 9729 || min >= 9984 && min <= 9987, "Unsupported minification filter");
                linear = mag == 9729;
            }
            result.add(new Texture(image, s, t, linear));
        }
        return result;
    }

    private List<GlbModel.Material> readMaterials(List<Texture> textures) throws IOException {
        List<GlbModel.Material> result = new ArrayList<>();
        for (JsonElement element : array(json, "materials", 256)) {
            JsonObject material = object(element, "material");
            JsonObject pbr = material.has("pbrMetallicRoughness") ? object(material.get("pbrMetallicRoughness"), "PBR material") : new JsonObject();
            float[] factor = vector(pbr, "baseColorFactor", new float[]{1, 1, 1, 1});
            for (float value : factor) check(value >= 0 && value <= 1, "Base color factor must be within [0,1]");
            Texture texture = new Texture(-1, 10497, 10497, true);
            if (pbr.has("baseColorTexture")) {
                JsonObject info = object(pbr.get("baseColorTexture"), "base-color texture");
                noExtensions(info, "texture transforms");
                check(integer(info, "texCoord", 0) == 0, "Base-color textures must use TEXCOORD_0");
                texture = textures.get(index(info, "index", textures.size()));
            }
            GlbModel.AlphaMode alpha;
            try { alpha = GlbModel.AlphaMode.valueOf(string(material, "alphaMode", "OPAQUE")); }
            catch (IllegalArgumentException failure) { throw bad("Unsupported material alpha mode"); }
            float cutoff = number(material, "alphaCutoff", .5f);
            check(cutoff >= 0, "Alpha cutoff must not be negative");
            result.add(new GlbModel.Material(string(material, "name", "Material " + result.size()), factor,
                    texture.image, bool(material, "doubleSided", false), alpha, cutoff, texture.s, texture.t, texture.linear));
        }
        result.add(new GlbModel.Material("Default", new float[]{1, 1, 1, 1}, -1, false, GlbModel.AlphaMode.OPAQUE, .5f, 10497, 10497, true));
        return result;
    }

    private List<GlbModel.Mesh> readMeshes(List<GlbModel.Material> materials) throws IOException {
        List<GlbModel.Mesh> result = new ArrayList<>();
        for (JsonElement element : array(json, "meshes", 256)) {
            JsonObject mesh = object(element, "mesh");
            check(!mesh.has("weights"), "Morph targets are unsupported; export skeletal animation without shape keys");
            List<GlbModel.Primitive> primitives = new ArrayList<>();
            JsonArray entries = array(mesh, "primitives", 1024);
            check(!entries.isEmpty(), "Mesh contains no primitives");
            for (JsonElement entry : entries) {
                check(++primitiveCount <= 1024, "Model exceeds 1024 material primitives");
                JsonObject primitive = object(entry, "mesh primitive");
                noExtensions(primitive, "compressed/extended mesh primitives");
                check(integer(primitive, "mode", 4) == 4, "Only indexed TRIANGLES are supported; triangulate the mesh before export");
                check(!primitive.has("targets"), "Morph targets are unsupported; export skeletal animation without shape keys");
                JsonObject attributes = object(primitive.get("attributes"), "vertex attributes");
                for (String key : attributes.keySet()) {
                    check(!key.startsWith("JOINTS_") || key.equals("JOINTS_0"), "More than four joint influences are unsupported; export JOINTS_0/WEIGHTS_0 only");
                    check(!key.startsWith("WEIGHTS_") || key.equals("WEIGHTS_0"), "More than four joint influences are unsupported; export JOINTS_0/WEIGHTS_0 only");
                }
                int positions = index(attributes, "POSITION", accessors.size());
                requireAccessor(positions, 3, 5126, false, "POSITION");
                float[] xyz = floats(positions);
                int count = xyz.length / 3;
                check((vertices += count) <= MAX_VERTICES, "Model exceeds 500,000 total primitive vertices");
                check(primitive.has("indices"), "Non-indexed meshes are unsupported; export indexed triangles");
                int indexAccessor = index(primitive, "indices", accessors.size());
                Accessor indexData = accessors.get(indexAccessor);
                check(indexData.components == 1 && !indexData.normalized && unsigned(indexData.component), "Triangle indices must be unsigned SCALAR values");
                int[] elements = integers(indexAccessor);
                check(elements.length % 3 == 0 && (indices += elements.length) <= MAX_INDICES, "Triangle index count must be a multiple of three and total at most 1,500,000");
                for (int value : elements) check(value < count && value != (indexData.component == 5121 ? 255 : indexData.component == 5123 ? 65535 : -1), "Triangle index is invalid or outside POSITION");
                float[] normal;
                if (attributes.has("NORMAL")) {
                    int accessor = index(attributes, "NORMAL", accessors.size());
                    requireAccessor(accessor, 3, 5126, false, "NORMAL");
                    check(accessors.get(accessor).count == count, "NORMAL count differs from POSITION");
                    normal = floats(accessor);
                } else normal = generateNormals(xyz, elements);
                float[] uv;
                if (attributes.has("TEXCOORD_0")) {
                    int accessor = index(attributes, "TEXCOORD_0", accessors.size());
                    requireFloatOrNormalized(accessor, 2, "TEXCOORD_0");
                    check(accessors.get(accessor).count == count, "TEXCOORD_0 count differs from POSITION");
                    uv = floats(accessor);
                } else { reserve((long) count * 2 * Float.BYTES); uv = new float[count * 2]; }
                int[] joints = null;
                float[] weights = null;
                check(attributes.has("JOINTS_0") == attributes.has("WEIGHTS_0"), "JOINTS_0 and WEIGHTS_0 must occur together");
                if (attributes.has("JOINTS_0")) {
                    int j = index(attributes, "JOINTS_0", accessors.size());
                    Accessor ja = accessors.get(j);
                    check(ja.components == 4 && !ja.normalized && (ja.component == 5121 || ja.component == 5123), "JOINTS_0 must be unsigned byte/short VEC4");
                    check(ja.count == count, "Joint/weight count differs from POSITION");
                    joints = integers(j);
                    int w = index(attributes, "WEIGHTS_0", accessors.size());
                    requireFloatOrNormalized(w, 4, "WEIGHTS_0");
                    check(accessors.get(w).count == count, "Joint/weight count differs from POSITION");
                    // A shared accessor may be used elsewhere; normalize a private per-primitive copy.
                    reserve((long) count * 4 * Float.BYTES);
                    weights = floats(w).clone();
                    for (int vertex = 0; vertex < count; vertex++) {
                        double sum = 0;
                        for (int i = 0; i < 4; i++) {
                            float weight = weights[vertex * 4 + i];
                            check(weight >= 0 && weight <= 1, "Skin weights must be within [0,1]");
                            sum += weight;
                        }
                        check(sum > 1e-8, "Every skinned vertex needs at least one positive weight");
                        for (int i = 0; i < 4; i++) weights[vertex * 4 + i] /= (float) sum;
                    }
                }
                int material = primitive.has("material") ? index(primitive, "material", materials.size() - 1) : materials.size() - 1;
                check(materials.get(material).image() < 0 || attributes.has("TEXCOORD_0"), "Textured primitive is missing TEXCOORD_0");
                primitives.add(new GlbModel.Primitive(xyz, normal, uv, joints, weights, elements, material));
            }
            result.add(new GlbModel.Mesh(string(mesh, "name", "Mesh " + result.size()), primitives));
        }
        return result;
    }

    private List<GlbModel.Node> readNodes(int meshCount) throws IOException {
        List<GlbModel.Node> result = new ArrayList<>();
        JsonArray entries = array(json, "nodes", MAX_NODES);
        for (JsonElement element : entries) {
            JsonObject node = object(element, "node");
            check(!node.has("weights"), "Animated morph weights are unsupported");
            int mesh = node.has("mesh") ? index(node, "mesh", meshCount) : -1;
            int skin = integer(node, "skin", -1);
            check(!node.has("skin") || skin >= 0, "Negative skin index");
            int[] children = nodeIndices(array(node, "children", MAX_NODES), entries.size(), "child node");
            float[] t = vector(node, "translation", new float[]{0, 0, 0});
            float[] r = vector(node, "rotation", new float[]{0, 0, 0, 1});
            float[] s = vector(node, "scale", new float[]{1, 1, 1});
            normalizeQuaternion(r, 0);
            float[] matrix = null;
            if (node.has("matrix")) {
                check(!node.has("translation") && !node.has("rotation") && !node.has("scale"), "Node cannot combine matrix and TRS");
                matrix = vector(node, "matrix", new float[16]);
                affine(matrix, 0, "Node matrix");
                for (int a = 0; a < 3; a++) for (int b = a + 1; b < 3; b++) {
                    double dot = 0, aa = 0, bb = 0;
                    for (int row = 0; row < 3; row++) {
                        double x = matrix[a * 4 + row], y = matrix[b * 4 + row];
                        dot += x * y; aa += x * x; bb += y * y;
                    }
                    check(Math.abs(dot) <= 1e-4 * Math.sqrt(aa * bb) + 1e-8, "Node matrix contains unsupported shear; export TRS transforms");
                }
            }
            result.add(new GlbModel.Node(string(node, "name", "Node " + result.size()), mesh, skin, children, t, r, s, matrix));
        }
        return result;
    }

    private static int[][] hierarchy(List<GlbModel.Node> nodes) throws IOException {
        int[] parents = new int[nodes.size()], order = new int[nodes.size()];
        Arrays.fill(parents, -1);
        for (int node = 0; node < nodes.size(); node++) for (int child : nodes.get(node).children()) {
            check(child != node && parents[child] == -1, "Node hierarchy contains self-parenting or multiple parents");
            parents[child] = node;
        }
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < nodes.size(); i++) if (parents[i] == -1) queue.add(i);
        int count = 0;
        while (!queue.isEmpty()) {
            int node = queue.removeFirst();
            order[count++] = node;
            for (int child : nodes.get(node).children()) queue.addLast(child);
        }
        check(count == nodes.size(), "Node hierarchy contains a cycle");
        return new int[][]{parents, order};
    }

    private List<GlbModel.Skin> readSkins(List<GlbModel.Node> nodes, int[] parents) throws IOException {
        List<GlbModel.Skin> result = new ArrayList<>();
        for (JsonElement element : array(json, "skins", 64)) {
            JsonObject skin = object(element, "skin");
            int[] joints = nodeIndices(array(skin, "joints", MAX_JOINTS), nodes.size(), "skin joint");
            check(joints.length > 0, "Skin must contain at least one joint");
            int common = root(joints[0], parents);
            for (int joint : joints) check(root(joint, parents) == common, "Skin joints must belong to one node hierarchy");
            if (skin.has("skeleton")) {
                int skeleton = index(skin, "skeleton", nodes.size());
                for (int joint : joints) {
                    int cursor = joint;
                    while (cursor >= 0 && cursor != skeleton) cursor = parents[cursor];
                    check(cursor == skeleton, "Skin skeleton must be an ancestor of all joints");
                }
            }
            float[] inverse;
            if (skin.has("inverseBindMatrices")) {
                int accessor = index(skin, "inverseBindMatrices", accessors.size());
                requireAccessor(accessor, 16, 5126, false, "inverseBindMatrices");
                check(accessors.get(accessor).count >= joints.length, "Inverse-bind matrix count is smaller than skin joints");
                reserve((long) joints.length * 16 * Float.BYTES);
                inverse = Arrays.copyOf(floats(accessor), joints.length * 16);
                for (int i = 0; i < joints.length; i++) affine(inverse, i * 16, "Inverse-bind matrix");
            } else {
                reserve((long) joints.length * 16 * Float.BYTES);
                inverse = new float[joints.length * 16];
                for (int i = 0; i < joints.length; i++) for (int diagonal = 0; diagonal < 4; diagonal++) inverse[i * 16 + diagonal * 5] = 1;
            }
            result.add(new GlbModel.Skin(string(skin, "name", "Skin " + result.size()), joints, inverse));
        }
        return result;
    }

    private List<GlbModel.Animation> readAnimations(List<GlbModel.Node> nodes) throws IOException {
        List<GlbModel.Animation> result = new ArrayList<>();
        int channelsTotal = 0;
        for (JsonElement element : array(json, "animations", 128)) {
            JsonObject animation = object(element, "animation");
            JsonArray samplers = array(animation, "samplers", 4096);
            List<GlbModel.Channel> channels = new ArrayList<>();
            Set<String> targets = new HashSet<>();
            float duration = 0;
            for (JsonElement entry : array(animation, "channels", 4096)) {
                check(++channelsTotal <= 4096, "Model exceeds 4096 animation channels");
                JsonObject channel = object(entry, "animation channel");
                JsonObject target = object(channel.get("target"), "animation target");
                noExtensions(target, "animation target extensions");
                int node = index(target, "node", nodes.size());
                check(nodes.get(node).matrix() == null, "Animated nodes must use TRS, not a matrix; re-export animation as TRS");
                String path = string(target, "path", "");
                GlbModel.Target kind = switch (path) {
                    case "translation" -> GlbModel.Target.TRANSLATION;
                    case "rotation" -> GlbModel.Target.ROTATION;
                    case "scale" -> GlbModel.Target.SCALE;
                    default -> throw bad("Unsupported animation target '" + path + "'; only translation, rotation and scale are supported");
                };
                check(targets.add(node + ":" + path), "Animation has duplicate channels for the same node property");
                JsonObject sampler = object(samplers.get(index(channel, "sampler", samplers.size())), "animation sampler");
                String interpolation = string(sampler, "interpolation", "LINEAR");
                check(!interpolation.equals("CUBICSPLINE"), "CUBICSPLINE animation is unsupported; resample/export the clip with LINEAR or STEP interpolation");
                GlbModel.Interpolation mode;
                try { mode = GlbModel.Interpolation.valueOf(interpolation); }
                catch (IllegalArgumentException failure) { throw bad("Only LINEAR and STEP animation interpolation are supported"); }
                int input = index(sampler, "input", accessors.size()), output = index(sampler, "output", accessors.size());
                requireAccessor(input, 1, 5126, false, "animation times");
                requireAccessor(output, kind == GlbModel.Target.ROTATION ? 4 : 3, 5126, false, "animation values");
                check(accessors.get(input).count <= 100_000 && accessors.get(input).count == accessors.get(output).count,
                        "Animation input/output key counts differ or exceed 100,000 keys");
                float[] times = floats(input), values = floats(output);
                float previous = -1;
                for (float time : times) {
                    check(time >= 0 && time > previous && time <= 86400, "Animation times must increase strictly within [0,86400] seconds");
                    previous = time;
                }
                if (kind == GlbModel.Target.ROTATION) {
                    reserve((long) values.length * Float.BYTES);
                    values = values.clone();
                    for (int i = 0; i < values.length; i += 4) normalizeQuaternion(values, i);
                }
                duration = Math.max(duration, times[times.length - 1]);
                channels.add(new GlbModel.Channel(node, kind, mode, times, values));
            }
            check(!channels.isEmpty(), "Animation clip has no channels");
            result.add(new GlbModel.Animation(string(animation, "name", "Animation " + result.size()), duration, channels));
        }
        return result;
    }

    private int[] sceneRoots(List<GlbModel.Node> nodes, int[] parents) throws IOException {
        JsonArray scenes = array(json, "scenes", 256);
        if (scenes.isEmpty()) {
            check(!json.has("scene"), "Default scene references a missing scene");
            return java.util.stream.IntStream.range(0, nodes.size()).filter(node -> parents[node] < 0).toArray();
        }
        // When an exporter omits 'scene', use its first scene rather than drawing all scenes together.
        int selected = json.has("scene") ? index(json, "scene", scenes.size()) : 0;
        int[] chosen = null;
        for (int i = 0; i < scenes.size(); i++) {
            int[] roots = nodeIndices(array(object(scenes.get(i), "scene"), "nodes", MAX_NODES), nodes.size(), "scene root");
            for (int root : roots) check(parents[root] == -1, "Scene root is also a child node");
            if (i == selected) chosen = roots;
        }
        return chosen;
    }

    private float[] floats(int index) throws IOException {
        float[] cached = floatCache.get(index);
        if (cached != null) return cached;
        Accessor accessor = accessors.get(index);
        reserve((long) accessor.count * accessor.components * Float.BYTES);
        float[] result = new float[accessor.count * accessor.components];
        ByteBuffer data = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN);
        int width = accessor.component == 5120 || accessor.component == 5121 ? 1 : accessor.component == 5122 || accessor.component == 5123 ? 2 : 4;
        for (int item = 0; item < accessor.count; item++) for (int component = 0; component < accessor.components; component++) {
            int at = accessor.start + item * accessor.stride + component * width;
            float value = switch (accessor.component) {
                case 5120 -> accessor.normalized ? Math.max(-1, data.get(at) / 127f) : data.get(at);
                case 5121 -> accessor.normalized ? Byte.toUnsignedInt(data.get(at)) / 255f : Byte.toUnsignedInt(data.get(at));
                case 5122 -> accessor.normalized ? Math.max(-1, data.getShort(at) / 32767f) : data.getShort(at);
                case 5123 -> accessor.normalized ? Short.toUnsignedInt(data.getShort(at)) / 65535f : Short.toUnsignedInt(data.getShort(at));
                case 5125 -> Integer.toUnsignedLong(data.getInt(at));
                case 5126 -> data.getFloat(at);
                default -> throw bad("Unsupported accessor component");
            };
            check(Float.isFinite(value), "Vertex, matrix or animation accessor contains NaN or infinity");
            result[item * accessor.components + component] = value;
        }
        floatCache.put(index, result);
        return result;
    }

    private int[] integers(int index) throws IOException {
        int[] cached = integerCache.get(index);
        if (cached != null) return cached;
        Accessor accessor = accessors.get(index);
        reserve((long) accessor.count * accessor.components * Integer.BYTES);
        int[] result = new int[accessor.count * accessor.components];
        ByteBuffer data = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN);
        int width = accessor.component == 5121 ? 1 : accessor.component == 5123 ? 2 : 4;
        for (int item = 0; item < accessor.count; item++) for (int component = 0; component < accessor.components; component++) {
            int at = accessor.start + item * accessor.stride + component * width;
            long value = switch (accessor.component) {
                case 5121 -> Byte.toUnsignedInt(data.get(at));
                case 5123 -> Short.toUnsignedInt(data.getShort(at));
                case 5125 -> Integer.toUnsignedLong(data.getInt(at));
                default -> throw bad("Indices must use unsigned integer components");
            };
            check(value <= Integer.MAX_VALUE, "Index exceeds the supported integer range");
            result[item * accessor.components + component] = (int) value;
        }
        integerCache.put(index, result);
        return result;
    }

    private float[] generateNormals(float[] positions, int[] elements) throws IOException {
        reserve((long) positions.length * Float.BYTES);
        float[] result = new float[positions.length];
        for (int i = 0; i < elements.length; i += 3) {
            int a = elements[i] * 3, b = elements[i + 1] * 3, c = elements[i + 2] * 3;
            double x1 = (double) positions[b] - positions[a], y1 = (double) positions[b + 1] - positions[a + 1], z1 = (double) positions[b + 2] - positions[a + 2];
            double x2 = (double) positions[c] - positions[a], y2 = (double) positions[c + 1] - positions[a + 1], z2 = (double) positions[c + 2] - positions[a + 2];
            double x = y1 * z2 - z1 * y2, y = z1 * x2 - x1 * z2, z = x1 * y2 - y1 * x2;
            double length = Math.sqrt(x * x + y * y + z * z);
            if (length < 1e-20) continue;
            float nx = (float) (x / length), ny = (float) (y / length), nz = (float) (z / length);
            result[a] += nx; result[a + 1] += ny; result[a + 2] += nz;
            result[b] += nx; result[b + 1] += ny; result[b + 2] += nz;
            result[c] += nx; result[c + 1] += ny; result[c + 2] += nz;
        }
        for (int i = 0; i < result.length; i += 3) {
            double length = Math.sqrt((double) result[i] * result[i] + (double) result[i + 1] * result[i + 1] + (double) result[i + 2] * result[i + 2]);
            if (length < 1e-20) { result[i + 1] = 1; continue; }
            for (int j = 0; j < 3; j++) result[i + j] /= (float) length;
        }
        return result;
    }

    private void requireAccessor(int index, int components, int component, boolean normalized, String name) throws IOException {
        Accessor value = accessors.get(index);
        check(value.components == components && value.component == component && value.normalized == normalized, "Unsupported " + name + " accessor type");
    }
    private void requireFloatOrNormalized(int index, int components, String name) throws IOException {
        Accessor value = accessors.get(index);
        check(value.components == components && (value.component == 5126 && !value.normalized
                || (value.component == 5121 || value.component == 5123) && value.normalized),
                name + " must use float or normalized unsigned byte/short components");
    }
    private void reserve(long bytes) throws IOException {
        check(bytes >= 0 && (decodedBytes += bytes) <= MAX_DECODED_BYTES, "Decoded geometry/animation exceeds the 64 MiB limit");
    }
    private static int root(int node, int[] parents) { while (parents[node] >= 0) node = parents[node]; return node; }
    private static boolean unsigned(int type) { return type == 5121 || type == 5123 || type == 5125; }
    private static boolean wrap(int mode) { return mode == 10497 || mode == 33071 || mode == 33648; }
    private static void affine(float[] matrix, int offset, String name) throws IOException {
        check(Math.abs(matrix[offset + 3]) <= 1e-6 && Math.abs(matrix[offset + 7]) <= 1e-6
                && Math.abs(matrix[offset + 11]) <= 1e-6 && Math.abs(matrix[offset + 15] - 1) <= 1e-6,
                name + " must be affine with last row [0,0,0,1]");
    }
    private static void normalizeQuaternion(float[] values, int offset) throws IOException {
        double length = 0;
        for (int i = 0; i < 4; i++) length += (double) values[offset + i] * values[offset + i];
        check(length > 1e-16 && Double.isFinite(length), "Rotation quaternion has zero or invalid length");
        double inverse = 1 / Math.sqrt(length);
        for (int i = 0; i < 4; i++) values[offset + i] *= (float) inverse;
    }
    private static int[] nodeIndices(JsonArray values, int size, String name) throws IOException {
        int[] result = new int[values.size()];
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < result.length; i++) {
            int value = exactInt(values.get(i), name);
            check(value >= 0 && value < size && seen.add(value), "Invalid or duplicate " + name + " index");
            result[i] = value;
        }
        return result;
    }
    private static void noUri(JsonObject value, String name) throws IOException {
        check(!value.has("uri"), "External/data URIs are unsupported for " + name + "; embed PNG/JPEG and geometry in one GLB BIN chunk");
    }
    private static void noExtensions(JsonObject value, String name) throws IOException {
        check(!value.has("extensions") || object(value.get("extensions"), name).isEmpty(), "Unsupported " + name + "; export standard glTF 2.0 data");
    }
    private static JsonObject object(JsonElement value, String name) throws IOException {
        check(value != null && value.isJsonObject(), "Missing or invalid " + name + " object");
        return value.getAsJsonObject();
    }
    private static JsonArray array(JsonObject owner, String key, int maximum) throws IOException {
        if (!owner.has(key)) return new JsonArray();
        JsonElement value = owner.get(key);
        check(value.isJsonArray() && value.getAsJsonArray().size() <= maximum, "Invalid or excessive " + key + " array");
        return value.getAsJsonArray();
    }
    private static int index(JsonObject owner, String key, int size) throws IOException {
        int value = integer(owner, key, -1);
        check(value >= 0 && value < size, "Missing or invalid " + key + " index");
        return value;
    }
    private static int integer(JsonObject owner, String key, int fallback) throws IOException {
        return owner.has(key) ? exactInt(owner.get(key), key) : fallback;
    }
    private static int exactInt(JsonElement value, String name) throws IOException {
        check(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(), "Invalid integer " + name);
        try { return value.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException failure) { throw bad("Invalid integer range for " + name); }
    }
    private static String string(JsonObject owner, String key, String fallback) throws IOException {
        if (!owner.has(key)) return fallback;
        JsonElement value = owner.get(key);
        check(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(), "Invalid string " + key);
        return value.getAsString();
    }
    private static boolean bool(JsonObject owner, String key, boolean fallback) throws IOException {
        if (!owner.has(key)) return fallback;
        JsonElement value = owner.get(key);
        check(value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean(), "Invalid boolean " + key);
        return value.getAsBoolean();
    }
    private static float number(JsonObject owner, String key, float fallback) throws IOException {
        return owner.has(key) ? finite(owner.get(key), key) : fallback;
    }
    private static float finite(JsonElement value, String name) throws IOException {
        check(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(), "Invalid number " + name);
        float result = value.getAsFloat();
        check(Float.isFinite(result), "Non-finite " + name);
        return result;
    }
    private static float[] vector(JsonObject owner, String key, float[] fallback) throws IOException {
        if (!owner.has(key)) return fallback;
        JsonArray values = array(owner, key, fallback.length);
        check(values.size() == fallback.length, "Invalid vector length for " + key);
        float[] result = new float[fallback.length];
        for (int i = 0; i < result.length; i++) result[i] = finite(values.get(i), key);
        return result;
    }
    private static IOException bad(String message) { return new IOException(message); }
    private static void check(boolean condition, String message) throws IOException { if (!condition) throw bad(message); }
    private record View(int start, int length, int stride) {}
    private record Accessor(int start, int count, int component, int components, int stride, boolean normalized) {}
    private record Texture(int image, int s, int t, boolean linear) {}
}
