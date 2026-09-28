import java.nio.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import org.lwjgl.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.stb.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/** Actual production shader driver regression. Hidden GLFW only; not a Minecraft integration test. */
public class ImpactCheck {
    static Path resources, output;
    static ZipFile minecraft;
    static int globals, vertexBuffer, frame, colorTexture, depthTexture, blankTexture;
    static final int TILE = Integer.getInteger("celeste.test.tile", 320);
    static final float[] TIMES = {3f, 3f + 1f / 60f, 3.5f};
    static final float[] CYAN = {.16f, .72f, 1f, .8f};
    record Material(String name, int program, int texture, String texturePath) {}

    static String preprocess(String source) throws Exception {
        Matcher match = Pattern.compile("#moj_import <minecraft:([^>]+)>").matcher(source);
        StringBuffer result = new StringBuffer();
        while (match.find()) {
            var entry = minecraft.getEntry("assets/minecraft/shaders/include/" + match.group(1));
            if (entry == null) throw new AssertionError("Missing Minecraft include " + match.group(1));
            String include;
            try (var stream = minecraft.getInputStream(entry)) {
                include = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("(?m)^#version[^\\r\\n]*", "");
            }
            match.appendReplacement(result, Matcher.quoteReplacement(preprocess(include)));
        }
        match.appendTail(result);
        return result.toString();
    }
    static int shader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) throw new AssertionError(glGetShaderInfoLog(shader));
        return shader;
    }
    static boolean analyticRing(String name) { return name.equals("jump") || name.equals("jump_lean"); }
    static boolean analytic(String name) { return analyticRing(name) || name.equals("bloom"); }
    static int program(String name) throws Exception {
        Path core = resources.resolve("assets/celeste/shaders/core");
        int vertex = shader(GL_VERTEX_SHADER, preprocess(Files.readString(core.resolve(name.equals("water") || name.equals("volume") ? "world_" + name + ".vsh" : "world_vfx.vsh")))
            .replace("#version 330", "#version 330\n#define ECHO_MESH " + (name.equals("echo") ? 1 : 0)));
        int fragment = shader(GL_FRAGMENT_SHADER, preprocess(Files.readString(core.resolve("world_" + name + ".fsh"))));
        int program = glCreateProgram();
        glAttachShader(program, vertex); glAttachShader(program, fragment);
        glBindAttribLocation(program, 0, "Position"); glBindAttribLocation(program, 1, "UV0");
        glBindAttribLocation(program, 2, "Color"); glBindAttribLocation(program, 3, "Normal");
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == 0) throw new AssertionError(name + ": " + glGetProgramInfoLog(program));
        glDeleteShader(vertex); glDeleteShader(fragment);
        String[] blocks = {"DynamicTransforms", "Projection", "Globals"};
        for (int binding = 0; binding < blocks.length; binding++) {
            int index = glGetUniformBlockIndex(program, blocks[binding]);
            if (index != GL_INVALID_INDEX) glUniformBlockBinding(program, index, binding);
        }
        int block = glGetUniformBlockIndex(program, "Globals");
        if (block == GL_INVALID_INDEX && !analytic(name)) throw new AssertionError(name + ": required Globals UBO missing");
        if (block != GL_INVALID_INDEX) {
            int size = glGetActiveUniformBlocki(program, block, GL_UNIFORM_BLOCK_DATA_SIZE);
            int binding = glGetActiveUniformBlocki(program, block, GL_UNIFORM_BLOCK_BINDING);
            int index = glGetUniformIndices(program, "GameTime");
            if (index == GL_INVALID_INDEX) index = glGetUniformIndices(program, "Globals.GameTime");
            int timeOffset = index == GL_INVALID_INDEX ? -1 : glGetActiveUniformsi(program, index, GL_UNIFORM_OFFSET);
            int screenIndex = glGetUniformIndices(program, "ScreenSize");
            if (screenIndex == GL_INVALID_INDEX) screenIndex = glGetUniformIndices(program, "Globals.ScreenSize");
            int screenOffset = screenIndex == GL_INVALID_INDEX ? -1 : glGetActiveUniformsi(program, screenIndex, GL_UNIFORM_OFFSET);
            // This AMD driver reports 56 bytes (last declared scalar end), without tail padding.
            // The allocated/bound 64-byte std140 buffer covers the complete block either way.
            if (index == GL_INVALID_INDEX || screenOffset != 32 || size < 48 || size > 64 || binding != 2 || timeOffset != 44)
                throw new AssertionError(name + ": actual Globals UBO layout mismatch: size=" + size + ", binding=" + binding + ", index=" + index + ", GameTime offset=" + timeOffset);
            System.out.println("Globals " + name + ": binding=" + binding + ", driver data size=" + size + ", allocated=64, GameTime offset=" + timeOffset);
        }
        glUseProgram(program);
        int sampler = glGetUniformLocation(program, "Sampler0");
        if (sampler >= 0) glUniform1i(sampler, 0);
        System.out.println("PASS driver compile/link: world_" + name + "; actual Minecraft includes; " + (analytic(name) ? "Procedural material, no sampler/Globals dependency" : "Globals binding 2 / 64 bytes / GameTime offset 44"));
        return program;
    }
    static int buffer(int binding, ByteBuffer data) {
        int buffer = glGenBuffers(); glBindBuffer(GL_UNIFORM_BUFFER, buffer);
        glBufferData(GL_UNIFORM_BUFFER, data, GL_DYNAMIC_DRAW);
        glBindBufferBase(GL_UNIFORM_BUFFER, binding, buffer); return buffer;
    }
    static void matrix(ByteBuffer data, int at) {
        for (int i = 0; i < 16; i++) data.putFloat(at + i * 4, i % 5 == 0 ? 1 : 0);
    }
    static int texture(String relative) {
        if (relative.equals("white")) return solidTexture(255);
        int[] width = {0}, height = {0}, channels = {0};
        Path source = resources.resolve(relative);
        ByteBuffer pixels = STBImage.stbi_load(source.toString(), width, height, channels, 4);
        if (pixels == null) throw new AssertionError("Texture " + relative + ": " + STBImage.stbi_failure_reason());
        int zero = 0, partial = 0;
        for (int p = 3; p < pixels.limit(); p += 4) {
            int alpha = pixels.get(p) & 255;
            if (alpha == 0) zero++; else if (alpha < 255) partial++;
        }
        int result = textureData(width[0], height[0], pixels);
        STBImage.stbi_image_free(pixels);
        System.out.println("Texture " + relative + " " + width[0] + "x" + height[0] + ": transparent=" + zero + ", partial-alpha=" + partial);
        return result;
    }
    static int solidTexture(int alpha) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(4);
        pixels.put(0, (byte)255).put(1, (byte)255).put(2, (byte)255).put(3, (byte)alpha);
        return textureData(1, 1, pixels);
    }
    static int textureData(int width, int height, ByteBuffer pixels) {
        int result = glGenTextures(); glBindTexture(GL_TEXTURE_2D, result);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE); return result;
    }
    static int attachment(boolean depth, int width, int height) {
        int result = glGenTextures(); glBindTexture(GL_TEXTURE_2D, result);
        glTexImage2D(GL_TEXTURE_2D, 0, depth ? GL_DEPTH_COMPONENT32F : GL_RGBA32F, width, height, 0,
            depth ? GL_DEPTH_COMPONENT : GL_RGBA, GL_FLOAT, (ByteBuffer)null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST); return result;
    }
    static int framebuffer(int width, int height, boolean withDepth) {
        int result = glGenFramebuffers(); glBindFramebuffer(GL_FRAMEBUFFER, result);
        colorTexture = attachment(false, width, height);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTexture, 0);
        if (withDepth) {
            depthTexture = attachment(true, width, height);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthTexture, 0);
        }
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new AssertionError("Incomplete float FBO");
        return result;
    }
    static void quad(float alpha) {
        float[] xy = {-1,-1, 1,-1, 1,1, -1,-1, 1,1, -1,1};
        FloatBuffer vertices = BufferUtils.createFloatBuffer(72);
        for (int i = 0; i < 6; i++) {
            float x = xy[i * 2], y = xy[i * 2 + 1];
            vertices.put(x).put(y).put(-.3f).put((x + 1) * .5f).put((y + 1) * .5f)
                .put(CYAN[0]).put(CYAN[1]).put(CYAN[2]).put(alpha).put(0).put(0).put(1);
        }
        vertices.flip(); glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STREAM_DRAW); glDrawArrays(GL_TRIANGLES, 0, 6);
    }
    static void draw(Material material, float seconds, float alpha, int texture) {
        draw(material, seconds, alpha, texture, TILE, TILE);
    }
    static void draw(Material material, float seconds, float alpha, int texture, int width, int height) {
        ByteBuffer values = BufferUtils.createByteBuffer(64);
        values.putFloat(32, width).putFloat(36, height).putFloat(44, seconds / 1200f);
        glBindBuffer(GL_UNIFORM_BUFFER, globals); glBufferSubData(GL_UNIFORM_BUFFER, 0, values);
        glUseProgram(material.program); glBindTexture(GL_TEXTURE_2D, texture); quad(alpha);
    }
    static float[] render(Material material, float seconds, float alpha, int texture) {
        glBindFramebuffer(GL_FRAMEBUFFER, frame); glViewport(0, 0, TILE, TILE);
        glDisable(GL_BLEND);
        if (material.name.equals("ribbon") || material.name.equals("pulse") || material.name.equals("volume")) glDisable(GL_DEPTH_TEST);
        else glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(true); glClearDepth(.63); glClearColor(0, 0, 0, 0);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT); glDepthMask(false);
        draw(material, seconds, alpha, texture);
        FloatBuffer read = BufferUtils.createFloatBuffer(TILE * TILE * 4);
        glReadPixels(0, 0, TILE, TILE, GL_RGBA, GL_FLOAT, read);
        float[] values = new float[read.remaining()]; read.get(values);
        FloatBuffer depth = BufferUtils.createFloatBuffer(TILE * TILE);
        glReadPixels(0, 0, TILE, TILE, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
        for (int i = 0; i < depth.limit(); i++) if (Math.abs(depth.get(i) - .63f) > 1e-6f)
            throw new AssertionError(material.name + ": depth changed at " + i);
        if (glGetError() != GL_NO_ERROR) throw new AssertionError("GL render/readback error");
        return values;
    }
    static void check(Material material) {
        float[] first = render(material, TIMES[0], CYAN[3], material.texture);
        float[] fractional = render(material, TIMES[1], CYAN[3], material.texture);
        float[] later = render(material, TIMES[2], CYAN[3], material.texture);
        int visible = 0, partial = 0, changed = 0, fractionalChanged = 0;
        float maxRgb = 0, maxAlpha = 0, maxEdge = 0;
        for (int p = 0; p < TILE * TILE; p++) {
            int at = p * 4; float alpha = first[at + 3];
            if (alpha > .003f) visible++;
            if (alpha > .003f && alpha < (material.name.equals("volume") ? .95f : .4f)) partial++;
            boolean movement = false, frameMovement = false;
            for (int c = 0; c < 4; c++) {
                for (float[] sample : new float[][] {first, fractional, later}) {
                    float value = sample[at + c];
                    if (!Float.isFinite(value) || value < -1e-6f || value > 1.0001f)
                        throw new AssertionError(material.name + ": unbounded output " + value + " channel " + c);
                }
                movement |= Math.abs(first[at + c] - later[at + c]) > 1f / 255f;
                frameMovement |= Math.abs(first[at + c] - fractional[at + c]) > 1e-5f;
                if (c < 3) maxRgb = Math.max(maxRgb, first[at + c]);
            }
            if (movement) changed++;
            if (frameMovement) fractionalChanged++;
            maxAlpha = Math.max(maxAlpha, alpha);
            int x = p % TILE, y = p / TILE;
            // Water is a joined 3D skirt: U wraps and V=1 is visible foam, only its base fades out.
            boolean edge = material.name.equals("volume") ? false : material.name.equals("water") ? y == 0
                : y == 0 || y == TILE - 1 || (!material.name.equals("ribbon") && (x == 0 || x == TILE - 1));
            if (edge) maxEdge = Math.max(maxEdge, alpha);
        }
        if (visible < 30 || partial < 10) throw new AssertionError(material.name + ": no visible/soft alpha silhouette");
        if (maxEdge > .015f) throw new AssertionError(material.name + ": visible quad boundary alpha=" + maxEdge);
        if (!analytic(material.name) && (changed < 30 || fractionalChanged < 30))
            throw new AssertionError(material.name + ": animation absent; half-second=" + changed + ", fractional=" + fractionalChanged);
        if (analyticRing(material.name)) {
            if (changed != 0 || fractionalChanged != 0) throw new AssertionError("Ring timing must be owned by the production CPU pose");
            int center=(TILE/2*TILE+TILE/2)*4+3;
            if(first[center]!=0) throw new AssertionError("Ring center must stay transparent");
            if(visible>TILE*TILE*.70) throw new AssertionError("Ring filled its bounding square");
        }
        float[] hidden = render(material, 4, 0, material.texture);
        for (float value : hidden) if (value != 0f) throw new AssertionError(material.name + ": zero vertex-alpha emitted a fragment");
        float[] emptyTexture = render(material, 4, CYAN[3], blankTexture);
        int analyticVisible = 0;
        for (int i = 3; i < emptyTexture.length; i += 4) if (emptyTexture[i] > .003f) analyticVisible++;
        System.out.printf(Locale.ROOT,
            "PASS %-7s visible=%d partial=%d changed(0.5s)=%d changed(1/60s)=%d maxRGB=%.4f maxAlpha=%.4f edgeAlpha=%.6f; zero vertex-alpha transparent; all depth unchanged; analytic pixels with transparent texture=%d%n",
            material.name, visible, partial, changed, fractionalChanged, maxRgb, maxAlpha, maxEdge, analyticVisible);
    }
    static float[] overlayPixels(Material material,int size,boolean remap,boolean move,float seconds,int white) {
        glBindFramebuffer(GL_FRAMEBUFFER,frame);glViewport(0,0,size,size);
        glDisable(GL_BLEND);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDepthMask(true);
        glClearDepth(.63);glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);glDepthMask(false);
        ByteBuffer values=BufferUtils.createByteBuffer(64);
        values.putFloat(32,size).putFloat(36,size).putFloat(44,seconds/1200f);
        glBindBuffer(GL_UNIFORM_BUFFER,globals);glBufferSubData(GL_UNIFORM_BUFFER,0,values);
        glUseProgram(material.program);glBindTexture(GL_TEXTURE_2D,white);
        // Constant UVs isolate animated fields from legitimate sprite/edge masks.
        // Each pair has matching static coverage (white texture removes asset asymmetry).
        float u=remap?.73f:.27f,v=.54f;
        switch(material.name) {
            case "pulse","jump","splash" -> {u=remap?.5f:.83f;v=remap?.83f:.5f;}
            case "prism" -> {u=remap?.75f:.25f;v=.5f;}
            case "echo" -> v=remap?.84f:.4f;
            case "droplet" -> {u=.5f;v=remap?.68f:.54f;}
        }
        float[] xy={-.8f,-.8f,.8f,-.8f,.8f,.8f,-.8f,-.8f,.8f,.8f,-.8f,.8f};
        FloatBuffer vertices=BufferUtils.createFloatBuffer(72);
        for(int i=0;i<6;i++)vertices.put(xy[i*2]+(move?.12f:0)).put(xy[i*2+1]+(move?.07f:0)).put(-.3f)
            .put(u).put(v).put(CYAN[0]).put(CYAN[1]).put(CYAN[2]).put(.35f).put(0).put(0).put(1);
        vertices.flip();glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);glBufferData(GL_ARRAY_BUFFER,vertices,GL_STREAM_DRAW);
        glDrawArrays(GL_TRIANGLES,0,6);
        FloatBuffer read=BufferUtils.createFloatBuffer(TILE*TILE*4);
        glReadPixels(0,0,TILE,TILE,GL_RGBA,GL_FLOAT,read);float[] result=new float[read.remaining()];read.get(result);
        for(float value:result)if(!Float.isFinite(value)||value<0||value>1.000001f)throw new AssertionError(material.name+": unbounded overlay fixture");
        FloatBuffer depth=BufferUtils.createFloatBuffer(TILE*TILE);glReadPixels(0,0,TILE,TILE,GL_DEPTH_COMPONENT,GL_FLOAT,depth);
        for(int i=0;i<depth.limit();i++)if(Math.abs(depth.get(i)-.63f)>1e-6)throw new AssertionError(material.name+": overlay fixture changed depth");
        return result;
    }
    static double overlaySample(float[] values,double x,double y,int channel) {
        int x0=(int)x,y0=(int)y,x1=Math.min(TILE-1,x0+1),y1=Math.min(TILE-1,y0+1);
        double dx=x-x0,dy=y-y0;
        return (values[(y0*TILE+x0)*4+channel]*(1-dx)+values[(y0*TILE+x1)*4+channel]*dx)*(1-dy)
            +(values[(y1*TILE+x0)*4+channel]*(1-dx)+values[(y1*TILE+x1)*4+channel]*dx)*dy;
    }
    static void profileCheck(Material material) {
        // Independent measured radial luminance targets; checks actual GPU RGB*alpha.
        double[] radii = {.2,.4,.5,.6,.7,.8,.9};
        double[] expected = material.name.equals("bloom")
            ? new double[] {.97319,.52837,.30196,.15833,.06939,.02218,.00450}
            : new double[] {0,.01336,.17286,.56180,1,.49081,.12678};
        float[] pixels = render(material, 3, CYAN[3], material.texture);
        for(int i=0;i<radii.length;i++) {
            int x=(int)Math.round((radii[i]+1)*.5*TILE-.5), y=TILE/2;
            int at=(y*TILE+x)*4;
            double light=pixels[at+2]*pixels[at+3]/(CYAN[2]*CYAN[3]);
            if(Math.abs(light-expected[i])>.035) throw new AssertionError(material.name+": radial brightness mismatch at "+radii[i]+": "+light);
            if(pixels[at+3]>.003) for(int c=0;c<3;c++)
                if(Math.abs(pixels[at+c]-CYAN[c])>1e-5) throw new AssertionError(material.name+": RGB must not dim alpha a second time");
        }
        System.out.println("PASS "+material.name+": measured radial brightness and single alpha attenuation");
    }
    static void overlayCheck(Material material) {
        int white=solidTexture(255);float seconds=1.7f;
        float[] anchor=overlayPixels(material,TILE,false,false,seconds,white);
        float[] remapped=overlayPixels(material,TILE,true,true,seconds,white);
        float[] later=overlayPixels(material,TILE,false,false,seconds+.11f,white);
        float[] remappedLater=overlayPixels(material,TILE,true,true,seconds+.11f,white);
        int common=0,changed=0,moved=0;double peak=0;
        for(int i=0;i<anchor.length;i+=4) {
            if((anchor[i+3]>0)!=(remapped[i+3]>0))moved++;
            if(anchor[i+3]==0||remapped[i+3]==0)continue;
            common++;boolean animated=false;
            for(int c=0;c<4;c++)animated|=Math.abs(anchor[i+c]-later[i+c])>1e-5;
            if(animated)changed++;
            if(material.name.equals("droplet")) {
                // Its lens mask changes with V; temporal alpha ratios cancel that
                // static shape while still detecting a UV-driven sparkle phase.
                peak=Math.max(peak,Math.abs(anchor[i+3]/later[i+3]-remapped[i+3]/remappedLater[i+3]));
            } else for(int c=0;c<4;c++)peak=Math.max(peak,Math.abs(anchor[i+c]-remapped[i+c]));
        }
        if(common<TILE*TILE/4||moved<TILE*TILE/50||changed<30||peak>2e-5)
            throw new AssertionError(material.name+": overlay follows moving geometry/UVs: peak="+peak+", common="+common+", moved="+moved+", animated="+changed);
        int small=TILE/2;float[] scaled=overlayPixels(material,small,false,false,seconds,white);double scalePeak=0;
        // Compare equal normalized framebuffer positions at two actual view sizes.
        // Bilinear readback tolerance covers half-pixel sample-center differences.
        for(int y=0;y<32;y++)for(int x=0;x<32;x++)for(int c=0;c<4;c++) {
            double nx=.22+.56*x/31.0,ny=.22+.56*y/31.0;
            scalePeak=Math.max(scalePeak,Math.abs(overlaySample(anchor,nx*TILE-.5,ny*TILE-.5,c)
                -overlaySample(scaled,nx*small-.5,ny*small-.5,c)));
        }
        glDeleteTextures(white);
        if(scalePeak>.0045)throw new AssertionError(material.name+": overlay pattern scale follows resolution: "+scalePeak);
        System.out.printf(Locale.ROOT,"PASS overlay %-7s frozen-time movement/UV peak=%.8f; %d common pixels, %d changed silhouette pixels; normalized %d/%d resolution peak=%.6f; time-only animation present; depth unchanged%n",
            material.name,peak,common,moved,TILE,small,scalePeak);
    }
    static void preview(List<Material> materials) {
        int width = TILE * materials.size(), height = TILE * TIMES.length;
        framebuffer(width, height, false); glViewport(0, 0, width, height);
        glDisable(GL_DEPTH_TEST); glDepthMask(false); glDisable(GL_BLEND);
        glClearColor(.025f, .03f, .045f, 1); glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_BLEND); glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        for (int row = 0; row < TIMES.length; row++) for (int col = 0; col < materials.size(); col++) {
            glViewport(col * TILE, (TIMES.length - row - 1) * TILE, TILE, TILE);
            Material material = materials.get(col);
            glBlendFuncSeparate(GL_SRC_ALPHA, material.name.equals("water") || material.name.equals("volume") ? GL_ONE_MINUS_SRC_ALPHA : GL_ONE,
                GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            draw(material, TIMES[row], CYAN[3], material.texture, width, height);
        }
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        STBImageWrite.stbi_flip_vertically_on_write(true);
        String filename = TILE == 320 ? "gpu-contact-sheet.png" : "gpu-contact-sheet-" + TILE + ".png";
        if (!STBImageWrite.stbi_write_png(output.resolve(filename).toString(), width, height, 4, pixels, width * 4))
            throw new AssertionError("PNG write failed");
        System.out.println("PREVIEW columns=" + materials.stream().map(Material::name).toList() + "; rows seconds=" + Arrays.toString(TIMES) + "; additive material on dark test background; " + width + "x" + height);
    }
    public static void main(String[] args) throws Exception {
        resources = Path.of(args[0]); minecraft = new ZipFile(args[1]); output = Path.of(args[2]);
        Properties texturePaths = new Properties();
        try (var input = Files.newInputStream(Path.of(args[3]))) { texturePaths.load(input); }
        GLFWErrorCallback callback = GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new AssertionError("GLFW unavailable");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE); glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3); glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(TILE, TILE, "Celeste material GPU regression (hidden)", 0, 0);
        if (window == 0) throw new AssertionError("OpenGL context unavailable");
        glfwMakeContextCurrent(window); GL.createCapabilities();
        System.out.println("Actual GPU: " + glGetString(GL_RENDERER) + "; OpenGL " + glGetString(GL_VERSION));
        ByteBuffer transforms = BufferUtils.createByteBuffer(160); matrix(transforms, 0); matrix(transforms, 96);
        for (int i = 0; i < 4; i++) transforms.putFloat(64 + i * 4, 1); buffer(0, transforms);
        ByteBuffer projection = BufferUtils.createByteBuffer(64); matrix(projection, 0); buffer(1, projection);
        globals = buffer(2, BufferUtils.createByteBuffer(64));
        int vao = glGenVertexArrays(); glBindVertexArray(vao); vertexBuffer = glGenBuffers(); glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
        int[] sizes = {3, 2, 4, 3}; int offset = 0;
        for (int i = 0; i < sizes.length; i++) { glEnableVertexAttribArray(i); glVertexAttribPointer(i, sizes[i], GL_FLOAT, false, 48, offset); offset += sizes[i] * 4; }
        glDisable(GL_CULL_FACE); blankTexture = solidTexture(0);
        List<Material> materials = new ArrayList<>();
        for (String name : new String[] {"ribbon", "pulse", "jump", "jump_lean", "splash", "droplet", "water", "volume", "bloom"}) {
            if (!Files.exists(resources.resolve("assets/celeste/shaders/core/world_" + name + ".fsh"))) {
                throw new AssertionError("Required production shader missing: " + name);
            }
            String path = texturePaths.getProperty(name);
            if (path == null) throw new AssertionError("Production texture path required for " + name);
            materials.add(new Material(name, program(name), texture(path), path));
        }
        frame = framebuffer(TILE, TILE, true);
        for (Material material : materials) {check(material);if(!analytic(material.name)) overlayCheck(material); if(material.name.equals("bloom") || material.name.equals("jump")) profileCheck(material);}
        // These pipelines have separate feature previews, but share the overlay contract.
        overlayCheck(new Material("prism",program("prism"),0,"white"));
        overlayCheck(new Material("echo",program("echo"),0,"white"));
        preview(materials);
        if (glGetError() != GL_NO_ERROR) throw new AssertionError("GL final error");
        glfwDestroyWindow(window); glfwTerminate(); callback.free(); minecraft.close();
        System.out.println("PASS all hidden-driver material checks. No Minecraft runtime/in-game integration was performed.");
    }
}
