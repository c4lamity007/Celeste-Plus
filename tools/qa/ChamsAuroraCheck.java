import java.nio.*;
import java.nio.file.*;
import java.util.zip.*;
import org.lwjgl.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.stb.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/** Actual GLSL/driver regression using a synthetic silhouette fixture; NOT an in-game screenshot. */
public class ChamsAuroraCheck extends ImpactCheck {
    static final int W = 1200, H = 600;
    static Path changedResources;
    static int fill, composite, mask, maskColor, main, mainDepth, energyTexture;
    static final float[] TINT = {.20f, .70f, 1f, .68f};

    static String source(String filename) throws Exception {
        Path relative = Path.of("assets/celeste/shaders/core", filename);
        Path staged = changedResources.resolve(relative);
        return Files.readString(Files.exists(staged) ? staged : resources.resolve(relative));
    }
    static int material(String stem, String override) throws Exception {
        int vertex = shader(GL_VERTEX_SHADER, preprocess(source(stem + ".vsh")));
        int fragment = shader(GL_FRAGMENT_SHADER, preprocess(override == null ? source(stem + ".fsh") : override));
        int program = glCreateProgram(); glAttachShader(program, vertex); glAttachShader(program, fragment);
        glBindAttribLocation(program, 0, "Position"); glBindAttribLocation(program, 2, "Color");
        glLinkProgram(program);
        if (glGetProgrami(program, GL_LINK_STATUS) == 0) throw new AssertionError(glGetProgramInfoLog(program));
        String[] blocks = {"DynamicTransforms", "Projection", "Globals"};
        for (int i = 0; i < blocks.length; i++) {
            int index = glGetUniformBlockIndex(program, blocks[i]);
            if (index != GL_INVALID_INDEX) glUniformBlockBinding(program, index, i);
        }
        int block = glGetUniformBlockIndex(program, "Globals");
        if (block != GL_INVALID_INDEX) {
            int index = glGetUniformIndices(program, "GameTime");
            if (index == GL_INVALID_INDEX || glGetActiveUniformsi(program, index, GL_UNIFORM_OFFSET) != 44
                || glGetActiveUniformBlocki(program, block, GL_UNIFORM_BLOCK_DATA_SIZE) > 64)
                throw new AssertionError("Minecraft Globals layout changed");
        }
        glUseProgram(program); int sampler = glGetUniformLocation(program, "InSampler");
        if (sampler >= 0) glUniform1i(sampler, 0);
        int energy = glGetUniformLocation(program, "EnergySampler"); if(energy >= 0)glUniform1i(energy,1);
        glDeleteShader(vertex); glDeleteShader(fragment); return program;
    }
    static int fbo(int color, int depth) {
        int value = glGenFramebuffers(); glBindFramebuffer(GL_FRAMEBUFFER, value);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
        if (depth != 0) glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new AssertionError("Incomplete FBO");
        return value;
    }
    static void rect(float x1, float y1, float x2, float y2, float z, float[] color) {
        float[] xy = {x1,y1,x2,y1,x2,y2,x1,y1,x2,y2,x1,y2};
        FloatBuffer vertices = BufferUtils.createFloatBuffer(72);
        for (int i = 0; i < 6; i++) vertices.put(xy[i*2]).put(xy[i*2+1]).put(z)
            .put(0).put(0).put(color).put(0).put(0).put(1);
        vertices.flip(); glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STREAM_DRAW); glDrawArrays(GL_TRIANGLES, 0, 6);
    }
    static void body(boolean reverse) {
        float[][] parts = {{-.235f,.50f,.235f,.91f,-.35f},{-.27f,-.24f,.27f,.53f,-.30f},
            {-.49f,-.28f,-.21f,.54f,-.38f},{.21f,-.28f,.49f,.54f,-.38f},
            {-.265f,-.94f,-.005f,-.19f,-.25f},{.005f,-.94f,.265f,-.19f,-.25f},
            {-.278f,-.25f,.278f,.54f,-.32f},{-.242f,.495f,.242f,.92f,-.36f},
            {-.498f,-.29f,-.205f,.55f,-.4f},{.205f,-.29f,.498f,.55f,-.4f}};
        for (int i=0;i<parts.length;i++) { float[] r=parts[reverse ? parts.length-1-i : i]; rect(r[0],r[1],r[2],r[3],r[4],TINT); }
    }
    static void beginMask() {
        glBindFramebuffer(GL_FRAMEBUFFER, mask); glViewport(0,0,W,H); glUseProgram(fill);
        glDisable(GL_BLEND); glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL); glDepthMask(true);
        glClearColor(0,0,0,0); glClearDepth(1); glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
    }
    static float[] read() {
        FloatBuffer pixels=BufferUtils.createFloatBuffer(W*H*4); glReadPixels(0,0,W,H,GL_RGBA,GL_FLOAT,pixels);
        float[] values=new float[pixels.remaining()]; pixels.get(values); return values;
    }
    static void time(float seconds) {
        ByteBuffer data=BufferUtils.createByteBuffer(64); data.putFloat(32,W).putFloat(36,H).putFloat(44,seconds/1200f);
        glBindBuffer(GL_UNIFORM_BUFFER,globals); glBufferSubData(GL_UNIFORM_BUFFER,0,data);
    }
    static float[] render(int program, float seconds) {
        glBindFramebuffer(GL_FRAMEBUFFER,main); glViewport(0,0,W,H);
        glDisable(GL_DEPTH_TEST); glDepthMask(false); glDisable(GL_BLEND);
        time(seconds); glUseProgram(program); glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,energyTexture);
        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,maskColor); glDrawArrays(GL_TRIANGLES,0,3);
        float[] values=read();
        for(float value:values) if(!Float.isFinite(value)||value<0||value>1.000001f) throw new AssertionError("Unbounded/nonfinite shader output");
        return values;
    }
    record FlowError(double mean, float peak, int visiblePixels, int samples) { }
    static FlowError flowError(float[] later, float[] earlier, int offsetX, int offsetY) {
        double sum=0;float peak=0;int visible=0,samples=0;
        // A constant full-frame mask isolates material motion. Stay farther from
        // all mask boundaries than both the halo radius and the tested offsets.
        for(int y=64;y<H-64;y++)for(int x=64;x<W-64;x++) {
            int a=(x+y*W)*4,b=(x+offsetX+(y+offsetY)*W)*4;
            if(later[a+3]!=TINT[3]||earlier[b+3]!=TINT[3])
                throw new AssertionError("Vertical-flow fixture changed its constant coverage alpha");
            boolean changed=false;
            for(int c=0;c<3;c++) {
                float delta=Math.abs(later[a+c]-earlier[b+c]);
                sum+=delta;peak=Math.max(peak,delta);changed|=delta>1f/255;
            }
            if(changed)visible++;
            samples++;
        }
        return new FlowError(sum/(samples*3),peak,visible,samples);
    }
    static boolean verticallyAligned(FlowError error) {
        // Exact pixel displacement avoids interpolation/filter tolerance entirely.
        // Float UBO/time math can straddle the driver's finite-precision bilinear
        // texture-weight boundary: isolated real-image pixels can differ by just
        // over one 8-bit level even with mean error below1e-6. Bound the peak to
        // two levels, >one-level outliers to <.01%, and mean5e-6 (about .0013 of
        // a level). No image resampling is used; wrong-direction controls below
        // must visibly differ at >5% of pixels with mean error above.002.
        return error.peak()<=2f/255&&error.mean()<=5e-6
            &&error.visiblePixels()<Math.max(1,error.samples()/10000);
    }
    static void visiblyDifferent(FlowError error,String label) {
        if(error.mean()<=.002||error.visiblePixels()<error.samples()/20)
            throw new AssertionError(label+" did not visibly differ: "+error);
    }
    static void verticalFlow(int program,boolean previousControl) {
        beginMask();rect(-1,-1,1,1,-.2f,TINT);
        int rejected=0,cases=0;
        for(float firstTime:new float[]{.375f,2.125f}) {
            float[] first=render(program,firstTime);
            for(int shift:new int[]{12,36}) {
                // 0.16 framebuffer-heights/sec at H600 is96px/sec. These two
                // fractional intervals (.125/.375s) produce exact12/36px shifts;
                // the independent existing1/60s test still checks subpixel time.
                float interval=shift/(.16f*H);
                float[] later=render(program,firstTime+interval);
                // OpenGL Y grows upward: a later pixel reads the earlier row below.
                FlowError aligned=flowError(later,first,0,-shift);cases++;
                if(previousControl) {
                    if(!verticallyAligned(aligned))rejected++;
                    System.out.printf(java.util.Locale.ROOT,"Previous chams control t=%.3f dt=%.3f: vertical-only mean=%.6f peak=%.6f%n",
                        firstTime,interval,aligned.mean(),aligned.peak());
                    continue;
                }
                if(!verticallyAligned(aligned))
                    throw new AssertionError("Chams is not a pure upward field translation at t="+firstTime+", dt="+interval+": "+aligned);
                visiblyDifferent(flowError(later,first,0,0),"Frozen/static material control");
                visiblyDifferent(flowError(later,first,0,shift),"Wrong vertical direction control");
                visiblyDifferent(flowError(later,first,-shift,-shift),"Leftward drift control");
                visiblyDifferent(flowError(later,first,shift,-shift),"Rightward drift control");
                System.out.printf(java.util.Locale.ROOT,"PASS chams vertical flow t=%.3f dt=%.3f: upward%dpx, x-offset0, mean=%.8f peak=%.8f across%d pixels, >one-level outliers=%d; alpha exact; static/downward/left/right controls visibly differ%n",
                    firstTime,interval,shift,aligned.mean(),aligned.peak(),aligned.samples(),aligned.visiblePixels());
            }
        }
        if(previousControl) {
            if(rejected!=cases)throw new AssertionError("Previous drifting shader passed a vertical-only case: rejected="+rejected+"/"+cases);
            System.out.println("PASS previous actual chams fragment rejected by unchanged vertical-translation bounds at "+rejected+"/"+cases+" fractional-time cases");
        }
    }
    public static void main(String[] args) throws Exception {
        resources=Path.of(args[0]); minecraft=new ZipFile(args[1]); output=Path.of(args[2]); changedResources=Path.of(args[3]);
        GLFWErrorCallback callback=GLFWErrorCallback.createPrint(System.err).set(); if(!glfwInit())throw new AssertionError("GLFW unavailable");
        glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE); glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3); glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(W,H,"Chams GPU preview fixture (hidden)",0,0); if(window==0)throw new AssertionError("No GL context");
        glfwMakeContextCurrent(window); GL.createCapabilities();
        fill=material("unified_chams_fill",null); composite=material("unified_chams_composite",null);
        String fragment=source("unified_chams_composite.fsh");
        String noRimSource=fragment.replace("float rim = clamp((1.0 - neighbors * 0.125) * 2.4, 0.0, 1.0);","float rim = 0.0;");
        if(noRimSource.equals(fragment))throw new AssertionError("No-rim comparison needs updating");
        int noRim=material("unified_chams_composite",noRimSource);
        int flat=material("unified_chams_composite","#version 330\nuniform sampler2D InSampler;out vec4 fragColor;void main(){fragColor=texelFetch(InSampler,ivec2(gl_FragCoord.xy),0);}");
        ByteBuffer dynamic=BufferUtils.createByteBuffer(160); matrix(dynamic,0); matrix(dynamic,96); for(int i=0;i<4;i++)dynamic.putFloat(64+i*4,1); buffer(0,dynamic);
        ByteBuffer projection=BufferUtils.createByteBuffer(64); matrix(projection,0); buffer(1,projection); globals=buffer(2,BufferUtils.createByteBuffer(64));
        glBindVertexArray(glGenVertexArrays()); vertexBuffer=glGenBuffers(); glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);
        Path originalResources=resources;resources=changedResources;energyTexture=texture("assets/celeste/textures/visual/plasma_flow.png");resources=originalResources;
        int[] sizes={3,2,4,3}; int offset=0; for(int i=0;i<sizes.length;i++){glEnableVertexAttribArray(i);glVertexAttribPointer(i,sizes[i],GL_FLOAT,false,48,offset);offset+=sizes[i]*4;}
        maskColor=attachment(false,W,H); mask=fbo(maskColor,attachment(true,W,H));
        mainDepth=attachment(true,W,H); main=fbo(attachment(false,W,H),mainDepth);
        glDepthMask(true); glClearDepth(.63); glClear(GL_DEPTH_BUFFER_BIT); glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,0,0);
        beginMask(); glViewport(W/2,0,W/2,H); body(false); float[] firstMask=read();
        beginMask(); glViewport(W/2,0,W/2,H); body(true); float[] maskPixels=read();
        if(!java.util.Arrays.equals(firstMask,maskPixels))throw new AssertionError("Face-order-dependent mask");
        int covered=0; for(int p=0;p<W*H;p++)if(maskPixels[p*4+3]>.003f){covered++; for(int c=0;c<4;c++)if(Math.abs(maskPixels[p*4+c]-TINT[c])>1e-6)throw new AssertionError("Internal fill seam");}
        if(covered<100000)throw new AssertionError("Missing silhouette");
        float[] first=render(composite,2),fractional=render(composite,2+1f/60),later=render(composite,2.5f); int changed=0,frameChanged=0;
        for(int p=0;p<W*H;p++) {
            int at=p*4; boolean delta=false,frameDelta=false;
            for(int c=0;c<3;c++){delta|=Math.abs(first[at+c]-later[at+c])>1f/255;frameDelta|=Math.abs(first[at+c]-fractional[at+c])>1e-5;}
            if(delta)changed++; if(frameDelta)frameChanged++;
            for(float[] result:new float[][]{first,fractional,later}) {
                if(result[at+3]!=maskPixels[at+3])throw new AssertionError("Changed union alpha");
                if(maskPixels[at+3]==0 && (result[at]!=0||result[at+1]!=0||result[at+2]!=0))throw new AssertionError("Transparent pixel color leak");
            }
        }
        if(changed<1000||frameChanged<1000)throw new AssertionError("Frame animation missing");
        beginMask();rect(-.8f,-.7f,0,.7f,-.2f,new float[]{.2f,.7f,1,.15f});rect(0,-.7f,.8f,.7f,-.2f,new float[]{.8f,.2f,.9f,.7f});
        float[] edged=render(composite,2),plain=render(noRim,2);int seamPixels=0;
        for(int y=150;y<450;y++)for(int x=590;x<610;x++){int at=(x+y*W)*4;for(int c=0;c<4;c++)if(edged[at+c]!=plain[at+c])throw new AssertionError("False interior alpha/color rim");seamPixels++;}
        beginMask();for(float value:render(composite,7))if(value!=0)throw new AssertionError("Empty input leak");
        beginMask();rect(-1,-1,-.95f,1,-.2f,TINT);float[] clipped=render(composite,2);for(int y=0;y<H;y++)for(int x=W-32;x<W;x++)for(int c=0;c<4;c++)if(clipped[(x+y*W)*4+c]!=0)throw new AssertionError("Screen edge wrapped");
        // Moving only the silhouette must not pull its animated material along.
        // Interior samples exclude the legitimate geometry-following occupancy rim.
        beginMask();rect(-.8f,-.8f,.7f,.7f,-.2f,TINT);float[] overlayAnchor=render(composite,2);
        beginMask();rect(-.6f,-.7f,.9f,.8f,-.2f,TINT);float[] overlayMoved=render(composite,2);
        int overlayPixels=0,shiftedCoverage=0;
        for(int p=0;p<W*H;p++)if(overlayAnchor[p*4+3]!=overlayMoved[p*4+3])shiftedCoverage++;
        for(int y=H*13/60;y<H*47/60;y++)for(int x=W*28/120;x<W*98/120;x++) {
            int at=(x+y*W)*4;
            if(overlayAnchor[at+3]==0||overlayMoved[at+3]==0)throw new AssertionError("Chams overlay fixture missing common coverage");
            for(int c=0;c<4;c++)if(overlayAnchor[at+c]!=overlayMoved[at+c])throw new AssertionError("Chams overlay pattern follows moving silhouette");
            overlayPixels++;
        }
        if(shiftedCoverage<W*H/50)throw new AssertionError("Chams silhouette did not actually move");
        System.out.println("PASS chams screen overlay: "+overlayPixels+" identical interior pixels at frozen time despite "+shiftedCoverage+" moved silhouette pixels; rim still follows coverage");
        verticalFlow(composite,false);
        String previousFragment=System.getProperty("celeste.test.previousChamsFragment");
        if(previousFragment!=null&&!previousFragment.isBlank()) {
            int previous=material("unified_chams_composite",Files.readString(Path.of(previousFragment)));
            try {verticalFlow(previous,true);} finally {glDeleteProgram(previous);}
        }
        beginMask();glViewport(0,0,W/2,H);body(false);glViewport(W/2,0,W/2,H);body(false);
        glBindFramebuffer(GL_FRAMEBUFFER,main);glViewport(0,0,W,H);glDisable(GL_DEPTH_TEST);glDepthMask(false);glClearColor(.025f,.03f,.045f,1);glClear(GL_COLOR_BUFFER_BIT);
        glEnable(GL_BLEND);glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);glBindTexture(GL_TEXTURE_2D,maskColor);glEnable(GL_SCISSOR_TEST);
        glScissor(0,0,W/2,H);glUseProgram(flat);glDrawArrays(GL_TRIANGLES,0,3);time(2.5f);glScissor(W/2,0,W/2,H);glUseProgram(composite);glDrawArrays(GL_TRIANGLES,0,3);glDisable(GL_SCISSOR_TEST);
        ByteBuffer preview=BufferUtils.createByteBuffer(W*H*4);glReadPixels(0,0,W,H,GL_RGBA,GL_UNSIGNED_BYTE,preview);
        STBImageWrite.stbi_flip_vertically_on_write(true);if(!STBImageWrite.stbi_write_png(output.resolve("chams-gpu-preview.png").toString(),W,H,4,preview,W*4))throw new AssertionError("PNG write failed");
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,mainDepth,0);FloatBuffer depth=BufferUtils.createFloatBuffer(W*H);glReadPixels(0,0,W,H,GL_DEPTH_COMPONENT,GL_FLOAT,depth);for(int i=0;i<depth.limit();i++)if(Math.abs(depth.get(i)-.63f)>1e-6)throw new AssertionError("Terrain depth changed");
        if(glGetError()!=GL_NO_ERROR)throw new AssertionError("OpenGL error");
        System.out.println("PASS actual GLSL compile/link; finite bounded RGBA; uniform/order-independent mask="+covered+"; exact-alpha/depth="+(W*H)+"; internal seam pixels="+seamPixels+"; animated half-second="+changed+" fractional-frame="+frameChanged+"; empty input and screen-edge isolation");
        System.out.println("GPU PREVIEW: synthetic silhouette fixture, flat fill LEFT / production aurora RIGHT; NOT an in-game screenshot.");
        glfwDestroyWindow(window);glfwTerminate();callback.free();minecraft.close();
    }
}
