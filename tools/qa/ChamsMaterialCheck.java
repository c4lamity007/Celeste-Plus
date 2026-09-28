import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/** Exact staged GLSL on the local GPU. The synthetic silhouette preview is NOT Minecraft QA. */
public final class ChamsMaterialCheck extends ChamsAuroraCheck {
    private static final String[] NAMES = {"Pearl", "Flow", "Mist"};
    private static final float[] NEUTRAL = {.62f, .62f, .62f, .68f};
    private static int checks;

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    private static void coloredBody(float[] tint) {
        float[][] parts = {{-.235f,.50f,.235f,.91f,-.35f},{-.27f,-.24f,.27f,.53f,-.30f},
            {-.49f,-.28f,-.21f,.54f,-.38f},{.21f,-.28f,.49f,.54f,-.38f},
            {-.265f,-.94f,-.005f,-.19f,-.25f},{.005f,-.94f,.265f,-.19f,-.25f},
            {-.278f,-.25f,.278f,.54f,-.32f},{-.242f,.495f,.242f,.92f,-.36f},
            {-.498f,-.29f,-.205f,.55f,-.4f},{.205f,-.29f,.498f,.55f,-.4f}};
        for (float[] r : parts) rect(r[0], r[1], r[2], r[3], r[4], tint);
    }

    private static void exactCoverage(float[] maskPixels, float[] result, String label) {
        for (int p = 0; p < W * H; p++) {
            int at = p * 4;
            check(result[at + 3] == maskPixels[at + 3], label + ": coverage alpha changed");
            if (maskPixels[at + 3] == 0F) {
                check(result[at] == 0F && result[at + 1] == 0F && result[at + 2] == 0F,
                    label + ": material leaks outside the silhouette");
            }
        }
    }

    private static int changedPixels(float[] first, float[] later, float threshold) {
        int count = 0;
        for (int p = 0; p < W * H; p++) {
            int at = p * 4;
            if (Math.abs(first[at] - later[at]) > threshold
                || Math.abs(first[at + 1] - later[at + 1]) > threshold
                || Math.abs(first[at + 2] - later[at + 2]) > threshold) count++;
        }
        return count;
    }

    private static void terrainDepth(String label) {
        glBindFramebuffer(GL_FRAMEBUFFER, main);
        FloatBuffer depth = BufferUtils.createFloatBuffer(W * H);
        glReadPixels(0, 0, W, H, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
        for (int i = 0; i < depth.limit(); i++) {
            check(Math.abs(depth.get(i) - .63F) < 1e-6F, label + ": terrain depth changed");
        }
    }

    private static void verifyStyle(int program, int noRim, String label) {
        beginMask(); glViewport(W / 3, 0, W / 3, H); body(false);
        float[] maskPixels = read();
        float[] first = render(program, 3F);
        float[] fractional = render(program, 3F + 1F / 60F);
        float[] later = render(program, 3.5F);
        exactCoverage(maskPixels, first, label);
        exactCoverage(maskPixels, fractional, label);
        exactCoverage(maskPixels, later, label);
        int animated = changedPixels(first, later, 1F / 255F);
        int frameAnimated = changedPixels(first, fractional, 1e-5F);
        check(animated > 1000 && frameAnimated > 1000, label + ": missing continuous animation");
        float[] plain = render(noRim, 3F);
        check(changedPixels(first, plain, 1e-5F) > 300,
            label + ": the no-rim comparison does not distinguish the actual outer rim");
        for (float seconds : new float[] {0F, 3600F, 100000F}) {
            exactCoverage(maskPixels, render(program, seconds), label + " long-time output");
        }

        // A neutral input must remain neutral, and the material must still be visible.
        beginMask(); rect(-.8F, -.8F, .8F, .8F, -.2F, NEUTRAL);
        float[] neutral = render(program, 3F);
        float minimum = 1F, maximum = 0F;
        for (int y = 100; y < H - 100; y++) for (int x = 200; x < W - 200; x++) {
            int at = (x + y * W) * 4;
            check(Math.abs(neutral[at] - neutral[at + 1]) < 1e-6F
                && Math.abs(neutral[at] - neutral[at + 2]) < 1e-6F,
                label + ": neutral tint acquires an unrelated hue");
            minimum = Math.min(minimum, neutral[at]); maximum = Math.max(maximum, neutral[at]);
        }
        check(maximum - minimum > .02F, label + ": neutral material is flat");

        // Adjacent differently colored/transparent parts form ONE occupied silhouette.
        beginMask();
        rect(-.8F, -.7F, 0F, .7F, -.2F, new float[] {.2F, .7F, 1F, .15F});
        rect(0F, -.7F, .8F, .7F, -.2F, new float[] {.8F, .2F, .9F, .7F});
        float[] edged = render(program, 3F), unedged = render(noRim, 3F);
        int seamPixels = 0;
        for (int y = 150; y < 450; y++) for (int x = W / 2 - 10; x < W / 2 + 10; x++) {
            int at = (x + y * W) * 4;
            for (int c = 0; c < 4; c++) check(edged[at + c] == unedged[at + c],
                label + ": false rim at an interior color/alpha boundary");
            seamPixels++;
        }
        beginMask();
        for (float value : render(program, 7F)) check(value == 0F, label + ": empty mask leaks color");
        beginMask(); rect(-1F, -1F, -.95F, 1F, -.2F, TINT);
        float[] clipped = render(program, 3F);
        for (int y = 0; y < H; y++) for (int x = W - 32; x < W; x++) {
            for (int c = 0; c < 4; c++) check(clipped[(x + y * W) * 4 + c] == 0F,
                label + ": screen edge wraps to the other side");
        }
        terrainDepth(label);
        check(glGetError() == GL_NO_ERROR, label + ": OpenGL error");
        System.out.printf(Locale.ROOT,
            "PASS %s: exact alpha/outside coverage; finite bounded output at 6 times; untouched terrain depth; fractional animation=%d, half-second=%d; neutral range=%.3f; occupancy-only rim at %d interior seam pixels; empty/edge isolation%n",
            label, frameAnimated, animated, maximum - minimum, seamPixels);
    }

    private static void preview(int[] programs) throws Exception {
        beginMask();
        for (int i = 0; i < programs.length; i++) {
            glViewport(i * W / 3, 0, W / 3, H);
            coloredBody(new float[] {.38F, .65F, .9F, .84F});
        }
        glBindFramebuffer(GL_FRAMEBUFFER, main); glViewport(0, 0, W, H);
        glDisable(GL_DEPTH_TEST); glDepthMask(false);
        glClearColor(.035F, .042F, .059F, 1F); glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, maskColor);
        glEnable(GL_SCISSOR_TEST); time(3F);
        for (int i = 0; i < programs.length; i++) {
            glScissor(i * W / 3, 0, W / 3, H); glUseProgram(programs[i]);
            glDrawArrays(GL_TRIANGLES, 0, 3);
        }
        glDisable(GL_SCISSOR_TEST); glDisable(GL_BLEND);
        ByteBuffer pixels = BufferUtils.createByteBuffer(W * H * 4);
        glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        int header = 88;
        BufferedImage picture = new BufferedImage(W, H + header, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int at = (x + (H - 1 - y) * W) * 4;
            picture.setRGB(x, y + header, (pixels.get(at) & 255) << 16
                | (pixels.get(at + 1) & 255) << 8 | pixels.get(at + 2) & 255);
        }
        Graphics2D graphics = picture.createGraphics();
        try {
            graphics.setColor(new Color(9, 11, 15)); graphics.fillRect(0, 0, W, header);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setFont(new Font("SansSerif", Font.PLAIN, 16)); graphics.setColor(new Color(159, 171, 191));
            graphics.drawString("GPU shader preview · synthetic player silhouettes · NOT an in-game screenshot", 24, 27);
            graphics.setFont(new Font("SansSerif", Font.BOLD, 23)); graphics.setColor(new Color(231, 237, 247));
            for (int i = 0; i < NAMES.length; i++) {
                int labelWidth = graphics.getFontMetrics().stringWidth(NAMES[i]);
                graphics.drawString(NAMES[i], i * W / 3 + (W / 3 - labelWidth) / 2, 66);
            }
        } finally { graphics.dispose(); }
        check(ImageIO.write(picture, "png", output.resolve("chams-material-gpu-preview.png").toFile()), "preview PNG write");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("resource directory, Minecraft jar, output directory required");
        resources = Path.of(args[0]).toRealPath();
        changedResources = resources; // One explicit resource tree. No fallback to a different build.
        output = Path.of(args[2]).toAbsolutePath(); Files.createDirectories(output);
        Path shaderFile = resources.resolve("assets/celeste/shaders/core/chams_material.fsh");
        String fragment = Files.readString(shaderFile);
        String noRimFragment = fragment.replace("float rim = (1.0 - neighbors * .25) * .32;", "float rim = 0.0;");
        check(!noRimFragment.equals(fragment), "occupancy-rim comparison requires the actual current shader");
        String shaderSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(shaderFile)));
        minecraft = new ZipFile(args[1]);
        GLFWErrorCallback callback = GLFWErrorCallback.createPrint(System.err).set();
        long window = 0L;
        try {
            check(glfwInit(), "GLFW unavailable");
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE); glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3); glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            window = glfwCreateWindow(W, H, "Chams material GPU fixture (hidden)", 0, 0);
            check(window != 0, "No GL context"); glfwMakeContextCurrent(window); GL.createCapabilities();
            System.out.println("GPU: " + glGetString(GL_RENDERER) + " / " + glGetString(GL_VERSION));
            System.out.println("Exact staged chams_material.fsh SHA-256: " + shaderSha);
            fill = material("unified_chams_fill", null);
            int[] programs = new int[3], noRim = new int[3];
            for (int style = 0; style < programs.length; style++) {
                String define = "#version 330\n#define CHAMS_MATERIAL " + style;
                programs[style] = material("unified_chams_composite", fragment.replace("#version 330", define));
                noRim[style] = material("unified_chams_composite", noRimFragment.replace("#version 330", define));
            }
            ByteBuffer dynamic = BufferUtils.createByteBuffer(160); matrix(dynamic, 0); matrix(dynamic, 96);
            for (int i = 0; i < 4; i++) dynamic.putFloat(64 + i * 4, 1F); buffer(0, dynamic);
            ByteBuffer projection = BufferUtils.createByteBuffer(64); matrix(projection, 0); buffer(1, projection);
            globals = buffer(2, BufferUtils.createByteBuffer(64));
            glBindVertexArray(glGenVertexArrays()); vertexBuffer = glGenBuffers(); glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
            int[] sizes = {3, 2, 4, 3}; int offset = 0;
            for (int i = 0; i < sizes.length; i++) {
                glEnableVertexAttribArray(i); glVertexAttribPointer(i, sizes[i], GL_FLOAT, false, 48, offset);
                offset += sizes[i] * 4;
            }
            maskColor = attachment(false, W, H); mask = fbo(maskColor, attachment(true, W, H));
            mainDepth = attachment(true, W, H); main = fbo(attachment(false, W, H), mainDepth);
            glDepthMask(true); glClearDepth(.63); glClear(GL_DEPTH_BUFFER_BIT);
            beginMask(); glViewport(W / 3, 0, W / 3, H); body(false); float[] normalOrder = read();
            beginMask(); glViewport(W / 3, 0, W / 3, H); body(true); float[] reverseOrder = read();
            check(Arrays.equals(normalOrder, reverseOrder), "body/clothing mask depends on face order");
            for (int p = 0; p < W * H; p++) if (normalOrder[p * 4 + 3] > .001F) {
                for (int c = 0; c < 4; c++) check(normalOrder[p * 4 + c] == TINT[c], "body/clothing seam in silhouette fill");
            }
            for (int style = 0; style < programs.length; style++) verifyStyle(programs[style], noRim[style], NAMES[style]);
            preview(programs); terrainDepth("preview"); check(glGetError() == GL_NO_ERROR, "preview OpenGL error");
            Files.writeString(output.resolve("result.json"), "{\n  \"status\": \"passed\",\n  \"kind\": \"gpu-preview\",\n  \"in_game\": false,\n  \"fragment_sha256\": \"" + shaderSha
                + "\",\n  \"styles\": [\"Pearl\", \"Flow\", \"Mist\"],\n  \"checks\": " + checks
                + ",\n  \"preview\": \"chams-material-gpu-preview.png\"\n}\n");
            System.out.println("PASS all three exact material defines; assertions=" + checks);
            System.out.println("GPU PREVIEW ONLY: " + output.resolve("chams-material-gpu-preview.png"));
        } finally {
            if (window != 0L) glfwDestroyWindow(window);
            glfwTerminate(); callback.free(); minecraft.close();
        }
    }
}
