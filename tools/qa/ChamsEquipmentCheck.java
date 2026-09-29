package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.compat.ChamsEquipmentAccess;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.util.math.Vector2f;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

/** Runs real 1.21.11 geometry emitters without starting Minecraft or creating a GPU. */
public final class ChamsEquipmentCheck {
    private static final Identifier BODY = Identifier.of("test", "body.png");
    private static final Identifier ARMOR = Identifier.of("test", "armor.png");
    private static final Identifier ITEM = Identifier.of("test", "item_atlas.png");
    private static int cases;

    public static void main(String[] args) {
        testBudgets();
        testBodyFallback();
        testRangesAndReuse();
        testBoundsAndUvs();
        testTextureHandles();
        testRealModelPart();
        testRealItemQuad();
        testNonEquipmentCommands();
        System.out.println("PASS Chams equipment: " + cases + " cases; real Minecraft geometry; no GPU/game check.");
    }

    private static void testBudgets() {
        var body = new PlayerModelSnapshot.CaptureBuffer();
        body.reset();
        for (int i = 0; i < 128; i++) quad(body, 0);
        eq(512, done(body).vertexCount(), "body budget remains 512");
        quad(body, 0);
        check(body.finish(0, Vec3d.ZERO) == null, "body overflow invalidates incomplete geometry");
        var equipped = new PlayerModelSnapshot.CaptureBuffer(PlayerModelSnapshot.MAX_EQUIPPED_VERTICES);
        equipped.reset();
        equipped.textureRange(BODY);
        for (int i = 0; i < 2048; i++) quad(equipped, 0);
        eq(8192, done(equipped).vertexCount(), "equipment budget supports 8192");
        quad(equipped, 0);
        check(equipped.finish(0, Vec3d.ZERO) == null, "equipment overflow invalidates incomplete geometry");
        for (int budget : new int[] {0, 3, 5, 8196}) {
            boolean refused = false;
            try { new PlayerModelSnapshot.CaptureBuffer(budget); }
            catch (IllegalArgumentException expected) { refused = true; }
            check(refused, "invalid vertex budget refused: " + budget);
        }
        pass("bounded capture budgets");
    }

    private static void testRangesAndReuse() {
        var buffer = new PlayerModelSnapshot.CaptureBuffer(8192);
        buffer.reset();
        buffer.textureRange(BODY); quad(buffer, 0);
        buffer.textureRange(BODY); quad(buffer, 1);
        buffer.textureRange(ARMOR); quad(buffer, 2);
        buffer.textureRange(ITEM); quad(buffer, 3);
        var snapshot = done(buffer);
        eq(List.of(new PlayerModelSnapshot.TextureRange(0, 8, BODY),
                   new PlayerModelSnapshot.TextureRange(8, 4, ARMOR),
                   new PlayerModelSnapshot.TextureRange(12, 4, ITEM)), snapshot.textureRanges(),
            "contiguous equal materials coalesce; transitions keep vertex offsets");
        boolean refused = false;
        try { snapshot.textureRanges().clear(); }
        catch (UnsupportedOperationException expected) { refused = true; }
        check(refused, "snapshot texture ranges immutable");
        buffer.reset(); buffer.textureRange(ITEM); quad(buffer, 100);
        var later = done(buffer);
        eq(16, snapshot.vertexCount(), "buffer reuse cannot mutate prior vertex data");
        close(0, snapshot.localBounds().minX, "old bounds retained");
        eq(3, snapshot.textureRanges().size(), "buffer reset cannot mutate prior range list");
        check(!snapshot.compatibleWith(later), "changed equipment topology does not interpolate");
        pass("immutable material ranges and buffer reuse");

        buffer.reset();
        for (int i = 0; i < PlayerModelSnapshot.MAX_TEXTURE_RANGES; i++) {
            buffer.textureRange(i % 2 == 0 ? BODY : ARMOR); quad(buffer, i);
        }
        eq(32, done(buffer).textureRanges().size(), "32 range ceiling accepted");
        buffer.textureRange(ITEM); quad(buffer, 40);
        check(buffer.finish(0, Vec3d.ZERO) == null, "range overflow rejected");
        buffer.reset(); buffer.textureRange(null); quad(buffer, 0);
        check(buffer.finish(0, Vec3d.ZERO) == null, "missing explicit texture cannot silently fill cutouts");
        buffer.reset(); buffer.textureRange(BODY); buffer.vertex(0, 0, 0);
        buffer.textureRange(ARMOR);
        check(buffer.finish(0, Vec3d.ZERO) == null, "material switch cannot split a quad");
        pass("material bounds and malformed capture refusal");
    }

    private static void testBodyFallback() {
        var buffer = new PlayerModelSnapshot.CaptureBuffer(8192);
        buffer.reset(); buffer.textureRange(BODY); quad(buffer, 0); buffer.preserveBody();
        buffer.textureRange(ITEM);
        for (int i = 0; i < 2048; i++) quad(buffer, 100);
        var snapshot = done(buffer);
        eq(4, snapshot.vertexCount(), "oversized equipment falls back to complete body");
        close(1, snapshot.localBounds().maxX, "fallback drops all oversized item bounds");
        eq(List.of(new PlayerModelSnapshot.TextureRange(0, 4, BODY)), snapshot.textureRanges(), "fallback uses body material only");
        buffer.reset(); buffer.textureRange(BODY); quad(buffer, 0); buffer.preserveBody();
        buffer.textureRange(ARMOR); buffer.vertex(Float.NaN, 0, 0);
        eq(4, done(buffer).vertexCount(), "malformed equipment retains complete body");
        buffer.reset(); buffer.textureRange(ITEM); buffer.vertex(Float.NaN, 0, 0);
        check(buffer.finish(0, Vec3d.ZERO) == null, "reset cannot reuse another frame's saved body");
        pass("equipment failure preserves bounded body-only fallback");
    }

    private static void testBoundsAndUvs() {
        var buffer = new PlayerModelSnapshot.CaptureBuffer(8192);
        buffer.reset(); buffer.textureRange(BODY);
        quad(buffer, 0);
        buffer.textureRange(ITEM); quad(buffer, -3);
        var snapshot = done(buffer);
        Box box = snapshot.localBounds();
        close(-3, box.minX, "offhand extension contributes to bounds");
        close(1, box.maxX, "body bound preserved");
        var out = new Recording();
        snapshot.emitSkinMask(out, new MatrixStack().peek(), Vec3d.ZERO, Vec3d.ZERO, 0, 0xffffff, 1);
        eq(8, out.vertices.size(), "mask emits all body/equipment vertices");
        int[] bits = {Float.floatToRawIntBits(0.1234567f), Float.floatToRawIntBits(-0.0f)};
        eq(bits[0], Float.floatToRawIntBits(out.uv.getFirst()[0]), "U retained bit-for-bit");
        eq(bits[1], Float.floatToRawIntBits(out.uv.getFirst()[1]), "negative zero V retained bit-for-bit");
        buffer.reset(); buffer.textureRange(ITEM); quad(buffer, 0);
        var itemOnly = done(buffer);
        check(!snapshot.compatibleWith(itemOnly), "body+item not compatible with item-only geometry");
        pass("equipment bounds and texture UV preservation");
    }

    private static void testTextureHandles() {
        eq(ARMOR, ChamsEquipmentAccess.texture(RenderLayers.armorCutoutNoCull(ARMOR)), "armor texture reflection resolves named 1.21.11 members");
        eq(ITEM, ChamsEquipmentAccess.texture(RenderLayers.entityCutoutNoCull(ITEM)), "item atlas identifier retained");
        check(ChamsEquipmentAccess.texture(RenderLayers.lines()) == null, "non-textured layer safely omitted");
        pass("real RenderLayer texture handles");
    }

    private static void testRealModelPart() {
        ModelPart part = new ModelPart(List.of(new ModelPart.Cuboid(0, 0, -4, -2, -1,
            8, 4, 2, 0, 0, 0, false, 64, 32, EnumSet.allOf(Direction.class))), Map.of());
        part.setOrigin(8, 4, 0);
        var matrices = new MatrixStack();
        matrices.translate(1, 2, 3);
        var expected = new Recording();
        part.render(matrices, expected, 0xf000f0, 0, 0xffffffff);
        var buffer = new PlayerModelSnapshot.CaptureBuffer(8192);
        buffer.reset();
        var queue = new EquipmentCaptureQueue(buffer);
        queue.submitModelPart(part, matrices, RenderLayers.armorCutoutNoCull(ARMOR), 0xf000f0, 0, null);
        var snapshot = done(buffer);
        var actual = new Recording();
        snapshot.emitSkinMask(actual, new MatrixStack().peek(), Vec3d.ZERO, Vec3d.ZERO, 0, 0xffffff, 1);
        sameGeometry(expected, actual, "model-part capture matches vanilla posed cuboid");
        eq(24, snapshot.vertexCount(), "all armor cuboid sides captured");
        eq(ARMOR, snapshot.textureRanges().getFirst().texture(), "model-part material retained");
        pass("real posed model-part geometry");
    }

    private static void testRealItemQuad() {
        long uv = Vector2f.toLong(0.321f, 0.678f);
        BakedQuad baked = new BakedQuad(new Vector3f(-.5f, 0, 0), new Vector3f(.5f, 0, 0),
            new Vector3f(.5f, 1, 0), new Vector3f(-.5f, 1, 0), uv, uv, uv, uv, -1, Direction.SOUTH, null, false, 0);
        var matrices = new MatrixStack(); matrices.translate(-2, .25, 1);
        matrices.scale(.5f, 2f, 1f);
        var buffer = new PlayerModelSnapshot.CaptureBuffer(8192); buffer.reset();
        var queue = new EquipmentCaptureQueue(buffer);
        queue.submitItem(matrices, ItemDisplayContext.THIRD_PERSON_LEFT_HAND, 0xf000f0, 0, 0,
            new int[0], List.of(baked), RenderLayers.entityCutoutNoCull(ITEM), ItemRenderState.Glint.STANDARD);
        var snapshot = done(buffer);
        eq(4, snapshot.vertexCount(), "enchanted held-item emits base once, no duplicate glint");
        close(-2.25, snapshot.localBounds().minX, "left-hand item transform retained");
        close(-1.75, snapshot.localBounds().maxX, "left-hand scale retained");
        close(2.25, snapshot.localBounds().maxY, "item height transform retained");
        var actual = new Recording();
        snapshot.emitSkinMask(actual, new MatrixStack().peek(), Vec3d.ZERO, Vec3d.ZERO, 0, 0xffffff, 1);
        eq(Float.floatToRawIntBits(.321f), Float.floatToRawIntBits(actual.uv.getFirst()[0]), "baked item U retained");
        eq(Float.floatToRawIntBits(.678f), Float.floatToRawIntBits(actual.uv.getFirst()[1]), "baked item V retained");
        eq(ITEM, snapshot.textureRanges().getFirst().texture(), "baked item atlas retained");
        pass("real baked held-item/offhand quad and glint suppression");
    }

    private static void testNonEquipmentCommands() {
        var buffer = new PlayerModelSnapshot.CaptureBuffer(8192); buffer.reset();
        var queue = new EquipmentCaptureQueue(buffer);
        queue.submitFire(null, null, null);
        queue.submitShadowPieces(null, 1, List.of());
        queue.submitLabel(null, null, 0, null, false, 0, 0, null);
        queue.submitLeash(null, null);
        check(buffer.finish(0, Vec3d.ZERO) == null, "labels/fire/shadows/leashes never enter mask");
        check(queue.getBatchingQueue(17) == queue, "batched feature submissions use owned immediate queue");
        pass("non-equipment submissions excluded");
    }

    private static void quad(PlayerModelSnapshot.CaptureBuffer out, float x) {
        for (float[] p : new float[][] {{x, 0, 0}, {x + 1, 0, 0}, {x + 1, 1, 0}, {x, 1, 0}})
            out.vertex(p[0], p[1], p[2]).texture(0.1234567f, -0.0f).normal(0, 0, 1);
    }
    private static PlayerModelSnapshot done(PlayerModelSnapshot.CaptureBuffer b) {
        var result = b.finish(0, Vec3d.ZERO); check(result != null, "valid capture must finish"); return result;
    }
    private static void sameGeometry(Recording expected, Recording actual, String label) {
        eq(expected.vertices.size(), actual.vertices.size(), label + " size");
        for (int i = 0; i < expected.vertices.size(); i++) {
            for (int axis = 0; axis < 3; axis++) close(expected.vertices.get(i)[axis], actual.vertices.get(i)[axis], label);
            for (int axis = 0; axis < 2; axis++) eq(Float.floatToRawIntBits(expected.uv.get(i)[axis]),
                Float.floatToRawIntBits(actual.uv.get(i)[axis]), label + " UV bits");
        }
    }
    private static void pass(String name) { cases++; System.out.println("PASS " + name); }
    private static void check(boolean valid, String text) { if (!valid) throw new AssertionError(text); }
    private static void eq(Object a, Object b, String text) { check(a.equals(b), text + ": " + a + " != " + b); }
    private static void close(double a, double b, String text) { check(Math.abs(a - b) < .000001, text + ": " + a + " != " + b); }
    private static final class Recording implements VertexConsumer {
        final List<float[]> vertices = new ArrayList<>();
        final List<float[]> uv = new ArrayList<>();
        public VertexConsumer vertex(float x, float y, float z) { vertices.add(new float[]{x,y,z}); return this; }
        public VertexConsumer texture(float u, float v) { uv.add(new float[]{u,v}); return this; }
        public VertexConsumer color(int r, int g, int b, int a) { return this; }
        public VertexConsumer color(int argb) { return this; }
        public VertexConsumer overlay(int u, int v) { return this; }
        public VertexConsumer light(int u, int v) { return this; }
        public VertexConsumer normal(float x, float y, float z) { return this; }
        public VertexConsumer lineWidth(float width) { return this; }
    }
}
