import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/** Actual exterior-only shaders on the local driver; synthetic geometry, not Minecraft QA. */
public final class ChamsGlowCheck extends ChamsAuroraCheck {
    private static int blur, blurFbo, blurColor, glow, checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static void linear(int texture) {
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }
    private static void blur() {
        glBindFramebuffer(GL_FRAMEBUFFER,blurFbo);glViewport(0,0,(W+1)/2,(H+1)/2);
        glDisable(GL_DEPTH_TEST);glDepthMask(false);glDisable(GL_BLEND);
        glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT);
        glUseProgram(blur);glActiveTexture(GL_TEXTURE0);linear(maskColor);
        glDrawArrays(GL_TRIANGLES,0,3);
    }
    private static void composite(boolean blend) {
        glBindFramebuffer(GL_FRAMEBUFFER,main);glViewport(0,0,W,H);
        glDisable(GL_DEPTH_TEST);glDepthMask(false);
        if(blend) {glEnable(GL_BLEND);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);}
        else glDisable(GL_BLEND);
        glUseProgram(glow);glActiveTexture(GL_TEXTURE1);linear(blurColor);
        glActiveTexture(GL_TEXTURE0);linear(maskColor);glDrawArrays(GL_TRIANGLES,0,3);
        glDisable(GL_BLEND);
    }
    private static float[] output() {
        blur();composite(false);
        float[] pixels=read();
        for(float value:pixels)check(Float.isFinite(value)&&value>=0&&value<=1.00001,"Finite bounded glow");
        return pixels;
    }
    private static float alpha(float[] pixels,int x,int y) {return pixels[(x+y*W)*4+3];}
    private static void depthUnchanged() {
        glBindFramebuffer(GL_FRAMEBUFFER,main);
        FloatBuffer depth=BufferUtils.createFloatBuffer(W*H);
        glReadPixels(0,0,W,H,GL_DEPTH_COMPONENT,GL_FLOAT,depth);
        for(int i=0;i<depth.limit();i++)check(Math.abs(depth.get(i)-.63f)<1e-6,"World depth untouched");
    }
    private static String sha(Path path)throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    private static void preview()throws Exception {
        beginMask();glViewport(W/3,0,W/3,H);body(false);blur();
        glBindFramebuffer(GL_FRAMEBUFFER,main);glViewport(0,0,W,H);
        glDisable(GL_DEPTH_TEST);glDepthMask(false);glDisable(GL_BLEND);
        glClearColor(.033f,.043f,.060f,1);glClear(GL_COLOR_BUFFER_BIT);
        float[] saved=TINT.clone();System.arraycopy(new float[]{.11f,.14f,.19f,1},0,TINT,0,4);
        glUseProgram(fill);glViewport(W/3,0,W/3,H);body(false);System.arraycopy(saved,0,TINT,0,4);
        composite(true);
        ByteBuffer pixels=BufferUtils.createByteBuffer(W*H*4);glReadPixels(0,0,W,H,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
        BufferedImage image=new BufferedImage(W,H+65,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<H;y++)for(int x=0;x<W;x++){
            int at=(x+(H-1-y)*W)*4;
            image.setRGB(x,y+65,(pixels.get(at)&255)<<16|(pixels.get(at+1)&255)<<8|pixels.get(at+2)&255);
        }
        Graphics2D g=image.createGraphics();
        g.setColor(new Color(9,11,16));g.fillRect(0,0,W,65);g.setColor(new Color(226,235,246));
        g.setFont(new Font("Segoe UI",Font.BOLD,22));g.drawString("Glow Chams / soft exterior bloom + crisp outline",24,28);
        g.setFont(new Font("Segoe UI",Font.PLAIN,14));g.setColor(new Color(155,169,188));
        g.drawString("Actual GPU shader, synthetic model. Original body is preserved. Not an in-game screenshot.",24,52);
        g.dispose();check(ImageIO.write(image,"png",output.resolve("chams-glow-gpu-preview.png").toFile()),"Preview saved");
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("base resources, Minecraft jar, staged resources, output");
        resources=Path.of(args[0]).toRealPath();minecraft=new ZipFile(args[1]);
        changedResources=Path.of(args[2]).toRealPath();output=Path.of(args[3]).toAbsolutePath();Files.createDirectories(output);
        Path glowPath=changedResources.resolve("assets/celeste/shaders/core/chams_glow.fsh");
        Path blurPath=changedResources.resolve("assets/celeste/shaders/core/chams_glow_blur.fsh");
        String glowSha=sha(glowPath),blurSha=sha(blurPath);
        GLFWErrorCallback callback=GLFWErrorCallback.createPrint(System.err).set();long window=0;
        try{
            check(glfwInit(),"GLFW available");
            glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
            window=glfwCreateWindow(W,H,"Chams Glow GPU fixture (hidden)",0,0);check(window!=0,"GL context");
            glfwMakeContextCurrent(window);GL.createCapabilities();
            System.out.println("GPU: "+glGetString(GL_RENDERER)+" / "+glGetString(GL_VERSION));
            fill=material("unified_chams_fill",null);
            blur=material("unified_chams_composite",Files.readString(blurPath));
            glow=material("unified_chams_composite",Files.readString(glowPath));
            glUseProgram(glow);glUniform1i(glGetUniformLocation(glow,"GlowSampler"),1);
            check(glGetUniformBlockIndex(blur,"Globals")==GL_INVALID_INDEX
                &&glGetUniformBlockIndex(glow,"Globals")==GL_INVALID_INDEX,"No unused Globals requirement");
            ByteBuffer dynamic=BufferUtils.createByteBuffer(160);matrix(dynamic,0);matrix(dynamic,96);
            for(int i=0;i<4;i++)dynamic.putFloat(64+i*4,1);buffer(0,dynamic);
            ByteBuffer projection=BufferUtils.createByteBuffer(64);matrix(projection,0);buffer(1,projection);
            glBindVertexArray(glGenVertexArrays());vertexBuffer=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);
            int[] sizes={3,2,4,3};int offset=0;
            for(int i=0;i<sizes.length;i++){glEnableVertexAttribArray(i);glVertexAttribPointer(i,sizes[i],GL_FLOAT,false,48,offset);offset+=sizes[i]*4;}
            maskColor=attachment(false,W,H);mask=fbo(maskColor,attachment(true,W,H));
            blurColor=attachment(false,(W+1)/2,(H+1)/2);blurFbo=fbo(blurColor,0);
            mainDepth=attachment(true,W,H);main=fbo(attachment(false,W,H),mainDepth);
            glDepthMask(true);glClearDepth(.63);glClear(GL_DEPTH_BUFFER_BIT);

            beginMask();glViewport(W/3,0,W/3,H);body(false);float[] silhouette=read(),first=output();
            int exterior=0,soft=0;
            for(int p=0;p<W*H;p++){
                int at=p*4;
                if(silhouette[at+3]>.001)for(int c=0;c<4;c++)check(first[at+c]==0,"Every occupied body pixel excluded");
                else if(first[at+3]>.001){exterior++;if(first[at+3]<.25)soft++;}
            }
            check(exterior>3000&&soft>1500,"Both visible exterior edge and soft halo exist");
            check(alpha(first,680,545)==0&&alpha(first,520,545)==0,"No hitbox border at empty head corners");
            beginMask();glViewport(W/3,0,W/3,H);body(true);
            check(Arrays.equals(first,output()),"Glow independent of overlapping body/clothing submission order");

            // Alpha blending must leave all already-covered body/terrain pixels unchanged.
            blur();glBindFramebuffer(GL_FRAMEBUFFER,main);glViewport(0,0,W,H);
            glClearColor(.13f,.21f,.34f,1);glClear(GL_COLOR_BUFFER_BIT);
            float[] background=read();composite(true);float[] blended=read();
            for(int p=0;p<W*H;p++)if(silhouette[p*4+3]>.001)
                for(int c=0;c<4;c++)check(blended[p*4+c]==background[p*4+c],"Original body survives actual alpha blending");
            depthUnchanged();

            beginMask();rect(-1f/3,-.5f,1f/3,.5f,-.2f,TINT);
            float[] rectangle=output();
            check(alpha(rectangle,599,300)==0,"No rectangle body fill");
            float near=alpha(rectangle,399,300),halo=alpha(rectangle,396,300);
            check(near>.20&&halo>.002&&halo<near,"Bright tight edge plus softer external bloom");
            float broadSix=alpha(rectangle,394,300),broadTwelve=alpha(rectangle,388,300);
            check(broadSix>.16&&broadTwelve>.02,"Clearly visible broad halo, not only a thin outline");
            System.out.printf(java.util.Locale.ROOT,"Broad glow alpha: 6px=%.4f, 12px=%.4f%n",broadSix,broadTwelve);
            float previous=near;
            for(int x=398;x>=370;x--){float next=alpha(rectangle,x,300);check(next<=previous+1e-5,"Halo smoothly fades away");previous=next;}
            check(alpha(rectangle,370,300)==0,"Bloom has bounded spatial support");
            String previousPath=System.getProperty("celeste.test.previousChamsGlow");
            if(previousPath!=null&&!previousPath.isBlank()) {
                int currentGlow=glow,currentBlur=blur;
                Path previousShaders=Path.of(previousPath);
                glow=material("unified_chams_composite",Files.readString(previousShaders.resolve("chams_glow.fsh")));
                blur=material("unified_chams_composite",Files.readString(previousShaders.resolve("chams_glow_blur.fsh")));
                glUseProgram(glow);glUniform1i(glGetUniformLocation(glow,"GlowSampler"),1);
                float[] thin=output();
                check(!(alpha(thin,394,300)>.16&&alpha(thin,388,300)>.02),"Previous thin glow fails broad-halo control");
                System.out.printf(java.util.Locale.ROOT,"Previous narrow glow rejected: 6px=%.4f, 12px=%.4f%n",alpha(thin,394,300),alpha(thin,388,300));
                glDeleteProgram(glow);glDeleteProgram(blur);glow=currentGlow;blur=currentBlur;
            }

            beginMask();for(float value:output())check(value==0,"Empty silhouette retires all previous glow");
            beginMask();rect(-1,-1,-.95f,1,-.2f,TINT);float[] edge=output();
            for(int y=0;y<H;y++)for(int x=W-24;x<W;x++)for(int c=0;c<4;c++)
                check(edge[(x+y*W)*4+c]==0,"Screen edge never wraps a ghost silhouette");
            beginMask();rect(-.8f,-.7f,0,.7f,-.2f,new float[]{.2f,.7f,1,.15f});
            rect(0,-.7f,.8f,.7f,-.2f,new float[]{.8f,.2f,.9f,.7f});float[] seam=output();
            for(int y=150;y<450;y++)for(int x=W/2-10;x<W/2+10;x++)for(int c=0;c<4;c++)
                check(seam[(x+y*W)*4+c]==0,"No internal joint, color or opacity seams");
            preview();depthUnchanged();check(glGetError()==GL_NO_ERROR,"No OpenGL errors");
            check(glowSha.equals(sha(glowPath))&&blurSha.equals(sha(blurPath)),"Exact tested shaders unchanged");
            Files.writeString(output.resolve("result.json"),"{\n  \"status\": \"passed\",\n  \"kind\": \"gpu-preview\",\n  \"in_game\": false,\n  \"glow_sha256\": \""+glowSha+"\",\n  \"blur_sha256\": \""+blurSha+"\",\n  \"checks\": "+checks+",\n  \"exterior_pixels\": "+exterior+",\n  \"soft_halo_pixels\": "+soft+"\n}\n");
            System.out.println("PASS Glow: "+checks+" checks; exterior="+exterior+"; soft halo="+soft+"; body preserved, empty/seam/edge isolated, depth unchanged.");
            System.out.println("GPU PREVIEW ONLY: "+output.resolve("chams-glow-gpu-preview.png"));
        }finally{if(window!=0)glfwDestroyWindow(window);glfwTerminate();callback.free();minecraft.close();}
    }
}
