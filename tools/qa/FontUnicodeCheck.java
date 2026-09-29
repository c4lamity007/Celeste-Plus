package dev.c4lam.celeste.render.font;

import com.google.gson.Gson;
import dev.c4lam.celeste.render.GuiRenderer;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.util.math.MatrixStack;
import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Executes actual font measurement and both draw paths against all shipped atlases. */
public final class FontUnicodeCheck {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0], "src/main/resources/assets/celeste/fonts/sf_pro");
        for (String weight : List.of("regular", "semibold", "bold")) {
            FontData data = new Gson().fromJson(Files.readString(directory.resolve("sf_" + weight + ".json")), FontData.class);
            Map<Integer, MsdfGlyph> glyphs = new HashMap<>();
            for (FontData.GlyphData glyph : data.glyphs())
                glyphs.put(glyph.unicode(), new MsdfGlyph(glyph, data.atlas().width(), data.atlas().height()));
            Map<Integer, Map<Integer, Float>> kernings = new HashMap<>();
            if (data.kernings() != null) for (FontData.KerningData pair : data.kernings())
                kernings.computeIfAbsent(pair.leftChar(), ignored -> new HashMap<>()).put(pair.rightChar(), pair.advance());
            Font font = new Font(weight, new AbstractTexture(), data.atlas(), data.metrics(), glyphs, kernings);
            for (FontData.GlyphData glyph : data.glyphs()) {
                String text = new String(Character.toChars(glyph.unicode()));
                if (!java.text.Normalizer.isNormalized(text, java.text.Normalizer.Form.NFC)) {
                    equivalent(font, text, java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC), "canonical glyph normalization");
                    continue;
                }
                close(glyph.advance() * 11, font.width(text, 11), weight + " existing glyph width");
                List<List<Float>> normal = draw(font, text, false);
                check(normal.size() == (glyph.atlasBounds() == null ? 0 : 4), weight + " existing glyph retains ink");
                check(normal.equals(draw(font, text, true)), weight + " gradient geometry matches normal");
            }
            String supported = "Bior\u0119 co nale\u017cy Za\u017c\u00f3\u0142\u0107 g\u0119\u015bl\u0105 ja\u017a\u0144 Caf\u00e9 Fran\u00e7ais Espa\u00f1ol T\u00fcrk\u00e7e \u010ce\u0161tina Rom\u00e2n\u0103 \u041f\u0440\u0438\u0432\u0435\u0442 \u0401\u0436 \u0423\u043a\u0440\u0430\u0457\u043d\u0430 \u0406\u0456\u0407\u0457\u0404\u0454\u0490\u0491 \u040e\u045e";
            for (int point : supported.codePoints().distinct().toArray()) {
                check(glyphs.containsKey(point), weight + " real glyph " + point);
                if (!Character.isWhitespace(point))
                    check(!draw(font, new String(Character.toChars(point)), false).equals(draw(font, "\u25A1", false)),
                        weight + " language glyph does not render replacement " + point);
            }
            for (String sample : List.of("Caf\u00e9", "Za\u017c\u00f3\u0142\u0107 g\u0119\u015bl\u0105 ja\u017a\u0144", "\u0401\u0436 \u0419\u043e\u0433\u0430 \u0457"))
                equivalent(font, java.text.Normalizer.normalize(sample, java.text.Normalizer.Form.NFD), sample, "decomposed accents");
            equivalent(font, "Cafe\u0301\n\u0415\u0308\u0436", "Caf\u00e9\n\u0401\u0436", "multiline composed accents");
            equivalent(font, "\u4E2D", "\u25A1", "missing CJK exposes replacement");
            equivalent(font, "A\uD83D\uDE00V", "A\u25A1V", "supplementary character emits one replacement");
            equivalent(font, "A\u00A0V", "A V", "nonbreaking space preserves separation");
            equivalent(font, "\u2192\uFE0F", "\u2192", "variation selector preserves supported arrow");
            equivalent(font, "A\u200DV", "AV", "joiner has no ink or advance");
            equivalent(font, "A\r\nV", "A\nV", "CRLF matches newline");
            check(font.width("\u4E2D\uD83D\uDE00", 11) > 0, "unsupported-only title has measurable width");
            close(Math.max(font.width("AV", 11), font.width("\u4E2D", 11)),
                font.width("AV\n\u4E2D", 11), "multiline uses visible replacement width");
        }
        System.out.println("PASS font Unicode: " + checks + " actual atlas/measurement/draw assertions; GPU and Minecraft runtime not tested");
    }
    private static void equivalent(Font font, String source, String expected, String label) {
        close(font.width(expected, 11), font.width(source, 11), label + " width");
        check(draw(font, source, false).equals(draw(font, expected, false)), label + " draw");
        check(draw(font, source, true).equals(draw(font, expected, true)), label + " gradient");
    }
    private static List<List<Float>> draw(Font font, String text, boolean gradient) {
        if (gradient) font.drawGradient(new MatrixStack(), text, 4, 8, 11,
            Color.WHITE, Color.PINK, Color.WHITE, 0.5F, 0.2F);
        else font.draw(new MatrixStack(), text, 4, 8, 11, Color.WHITE);
        return List.copyOf(GuiRenderer.last.vertices);
    }
    private static void close(float expected, float actual, String label) {
        check(Math.abs(expected - actual) < 0.0001F, label + ": " + expected + " vs " + actual);
    }
    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }
}
