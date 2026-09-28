import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/** Exact sky shader GPU checks; isolated sky fixture, never an in-game claim. */
public final class SkyCubesCheck extends ImpactCheck {
    static int current, baseline;
    static int W=AtmosphereCheck.W, H=AtmosphereCheck.H;
    static double TAU=Math.PI*2;
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    static float[] draw(int shader,int mode,double seconds,boolean cubes,float[] view,float[] projection,float rain){
        AtmosphereCheck.clear(.63f);
        AtmosphereCheck.transforms(view,projection,new float[]{mode,.85f,(float)(seconds*TAU/240),.7f},rain);
        FloatBuffer flag=BufferUtils.createFloatBuffer(1);flag.put(cubes?1f:0f).flip();
        glBindBuffer(GL_UNIFORM_BUFFER,AtmosphereCheck.dynamic);glBufferSubData(GL_UNIFORM_BUFFER,84,flag);
        AtmosphereCheck.fog(1000,1024);glUseProgram(shader);glDrawArrays(GL_TRIANGLES,0,3);
        float[] pixels=AtmosphereCheck.pixels();AtmosphereCheck.bounded(pixels,true,1.00001f);AtmosphereCheck.depth(.63f);return pixels;
    }
    static float[] at(int mode,double seconds,boolean cubes){return draw(current,mode,seconds,cubes,AtmosphereCheck.rotation(.1,-.52),AtmosphereCheck.perspective(),0);}
    static void sheet(float[] off,float[] on,float[] later)throws Exception{
        int header=54,label=26;BufferedImage image=new BufferedImage(W*3,H+header+label,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();
        g.setColor(new Color(17,20,28));g.fillRect(0,0,image.getWidth(),image.getHeight());g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,17));g.drawString("SKY CUBES - translucent faces, glow and center diagonals",14,23);
        g.setFont(new Font("SansSerif",Font.PLAIN,12));g.drawString("Synthetic sky view, not Minecraft. Fixed camera; default brightness .85. Cubes draw before terrain in the real sky pass.",14,43);
        float[][] frames={off,on,later};String[] labels={"Off | original sky preserved","On | 12 seconds","On | 16 seconds"};
        for(int i=0;i<3;i++){g.drawString(labels[i],i*W+12,header+18);g.drawImage(AtmosphereCheck.image(frames[i]),i*W,header+label,null);}g.dispose();
        ImageIO.write(image,"PNG",output.resolve("sky-cubes-gpu-preview.png").toFile());
    }
    static double gpuTime(int program,boolean cubes){
        at(3,12,cubes);glUseProgram(program);int query=glGenQueries();glBeginQuery(GL_TIME_ELAPSED,query);for(int i=0;i<48;i++)glDrawArrays(GL_TRIANGLES,0,3);glEndQuery(GL_TIME_ELAPSED);
        long elapsed=glGetQueryObjectui64(query,GL_QUERY_RESULT);glDeleteQueries(query);return elapsed/48.0/1_000_000;
    }
    static float[] materialProbe(int shader,float x,float y,float z){
        AtmosphereCheck.clear(.63f);glUseProgram(shader);
        glUniform3f(glGetUniformLocation(shader,"ProbeRay"),x,y,z);glDrawArrays(GL_TRIANGLES,0,3);
        float[] values=AtmosphereCheck.pixels();AtmosphereCheck.bounded(values,false,4);AtmosphereCheck.depth(.63f);
        return Arrays.copyOf(values,4);
    }
    static void materialChecks(String vertex,String production)throws Exception{
        // Same compiled production helper, with a uniform ray so exact face/line
        // locations can be tested independently of the animated camera overview.
        String diagnostic=production.replace("void main()", "void productionSkyMain()")
            + "\nuniform vec3 ProbeRay; void main(){fragColor=skyCubeMaterial(normalize(ProbeRay),vec3(0,0,-1),.22,.001);}";
        int program=AtmosphereCheck.linkSource(vertex,diagnostic,"actual cube material probes");
        float[] face=materialProbe(program,.11f,.04f,-1),center=materialProbe(program,0,0,-1);
        float[] edge=materialProbe(program,.22f,0,-.78f),halo=materialProbe(program,.23f,0,-.78f),empty=materialProbe(program,.30f,0,-.78f);
        require(face[3]>.10&&face[3]<.25&&face[2]>.05,"Cube faces lack translucent fill");
        require(center[2]>face[2]*4,"Corner diagonals do not cross the center");
        require(edge[2]>center[2]&&edge[1]>.8,"Outer outline lacks a luminous core");
        require(halo[3]==0&&halo[2]>.002&&halo[2]<edge[2]*.5,"No distinct soft glow outside the outline");
        for(float value:empty)require(value==0,"Cube glow is not spatially bounded");
        System.out.printf(Locale.ROOT,"PASS actual material probes: face alpha %.3f, center blue %.3f, edge blue %.3f, outside glow blue %.4f, distant zero%n",face[3],center[2],edge[2],halo[2]);
        float[] inner=materialProbe(program,.0924f,0,-.9076f);
        require(inner[2]>face[2]*4,"Nested cube edges are absent");
        glDeleteProgram(program);
        String closeup=production.replace("void main()", "void productionSkyMain()")
            + "\nvoid main(){vec3 d=normalize(worldDirection);float cy=cos(.60),sy=sin(.60),cx=cos(.36),sx=sin(.36);"
            + "mat3 turn=mat3(cy,0,-sy,sy*sx,cx,cy*sx,sy*cx,-sx,cy*cx);"
            + "float aa=length(abs(dFdx(d))+abs(dFdy(d)))*.55;"
            + "vec4 m=skyCubeMaterial(transpose(turn)*d,transpose(turn)*vec3(0,0,-1),.22,aa);"
            + "vec3 c=mix(vec3(.014,.03,.08),vec3(.025,.12,.22),clamp(d.y+.5,0,1))*(1-m.a)+m.rgb;"
            + "c*=.85;c=c/(vec3(1)+c*.22);fragColor=vec4(clamp(c,0,1),1);}";
        int close=AtmosphereCheck.linkSource(vertex,closeup,"actual cube material closeup");
        float[] projection=AtmosphereCheck.perspective();projection[0]*=2.0;projection[5]*=2.0;
        float[] pixels=draw(close,3,0,true,AtmosphereCheck.identity(),projection,0);
        BufferedImage image=new BufferedImage(W,H+50,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();
        g.setColor(new Color(17,20,28));g.fillRect(0,0,W,50);g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,16));g.drawString("Sky cube material - GPU closeup",12,21);
        g.setFont(new Font("SansSerif",Font.PLAIN,12));g.drawString("Exact production material, enlarged isolated fixture; not an in-game screenshot.",12,41);
        g.drawImage(AtmosphereCheck.image(pixels),0,50,null);g.dispose();ImageIO.write(image,"PNG",output.resolve("sky-cube-material-closeup.png").toFile());glDeleteProgram(close);
    }
    public static void main(String[] args)throws Exception{
        Path resourceRoot=Path.of(args[0]),fragment=Path.of(args[1]);minecraft=new ZipFile(args[2]);output=Path.of(args[3]);Files.createDirectories(output);
        require(glfwInit(),"GLFW init");glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(W,H,"Sky cubes hidden GPU QA",0,0);require(window!=0,"GPU context");glfwMakeContextCurrent(window);GL.createCapabilities();
        try{
            glBindVertexArray(glGenVertexArrays());AtmosphereCheck.dynamic=buffer(0,BufferUtils.createByteBuffer(160));AtmosphereCheck.projection=buffer(1,BufferUtils.createByteBuffer(64));AtmosphereCheck.fog=buffer(2,BufferUtils.createByteBuffer(48));frame=framebuffer(W,H,true);
            String vertex=preprocess(Files.readString(resourceRoot.resolve("assets/celeste/shaders/core/skybox.vsh")));
            String production=preprocess(Files.readString(fragment));
            current=AtmosphereCheck.linkSource(vertex,production,"new exact sky shader");
            materialChecks(vertex,production);
            baseline=AtmosphereCheck.linkSource(vertex,preprocess(Files.readString(resourceRoot.resolve("assets/celeste/shaders/core/skybox.fsh"))),"original sky shader");
            AtmosphereCheck.layout(current,"ModelOffset",80);
            float[] view=AtmosphereCheck.rotation(.1,-.52),projection=AtmosphereCheck.perspective();
            for(int mode=0;mode<5;mode++){
                float[] off=at(mode,12,false),on=at(mode,12,true);
                AtmosphereCheck.close(off,draw(baseline,mode,12,false,view,projection,0),0,0,"Off preserves original mode "+mode);
                var effect=AtmosphereCheck.delta(off,on);require(effect.changed()>100&&effect.peak()>.05,"Cubes absent in mode "+mode);
                require(effect.changed()/(double)on.length<.13,"Cubes cover too much of the sky");
                float[] translated=view.clone();translated[12]=25000;translated[13]=-914;translated[14]=-.1f;
                AtmosphereCheck.close(on,draw(current,mode,12,true,translated,projection,0),0,0,"Translation invariant "+mode);
                float[] r=AtmosphereCheck.rotation(.22,.07);
                AtmosphereCheck.close(on,draw(current,mode,12,true,AtmosphereCheck.multiply(r,view),AtmosphereCheck.multiply(projection,AtmosphereCheck.transpose(r)),0),.00002,.002,"World direction decomposition "+mode);
                require(AtmosphereCheck.delta(on,draw(current,mode,12,true,AtmosphereCheck.multiply(r,view),projection,0)).mean()>.002,"Sky locked to screen");
                AtmosphereCheck.close(at(mode,0,true),at(mode,240,true),.00001,.003,"Periodic fade/rotation "+mode);
                var small=AtmosphereCheck.delta(on,at(mode,12+1.0/60,true));var large=AtmosphereCheck.delta(on,at(mode,12.5,true));
                require(small.mean()>0&&large.mean()>small.mean()*5,"Animation lacks continuous time");
                require(small.mean()<.001,"Abrupt frame fade/motion");
                float[] horizon=draw(current,mode,12,true,AtmosphereCheck.identity(),projection,0);
                for(int y=0;y<H/2;y++)for(int x=0;x<W;x++)for(int c=0;c<3;c++)require(horizon[(y*W+x)*4+c]==AtmosphereCheck.FOG[c],"Cubes leak below horizon");
                for(double pitch:new double[]{-Math.PI/2,Math.PI/2})draw(current,mode,12,true,AtmosphereCheck.rotation(0,pitch),projection,1);
                System.out.printf(Locale.ROOT,"PASS mode %d: coverage %.3f%%; smooth frame delta %.8f; translation, camera orientation, loop, finite colors, horizon, depth unchanged%n",mode,effect.changed()*100.0/on.length,small.mean());
            }
            // In the actual integration vanilla terrain draws after this sky pass.
            // Simulate a closer opaque surface drawn afterward and confirm it covers it.
            at(3,12,true);glEnable(GL_SCISSOR_TEST);glScissor(W/3,H/3,W/3,H/3);glClearColor(.2f,.3f,.4f,1);glClear(GL_COLOR_BUFFER_BIT);glDisable(GL_SCISSOR_TEST);
            float[] covered=AtmosphereCheck.pixels();for(int y=H/3;y<H/3+H/3;y++)for(int x=W/3;x<W/3+W/3;x++){int i=(y*W+x)*4;require(covered[i]==.2f&&covered[i+1]==.3f&&covered[i+2]==.4f,"Later terrain draw did not occlude sky");}
            sheet(at(3,12,false),at(3,12,true),at(3,16,true));
            System.out.printf(Locale.ROOT,"GPU timing at %dx%d: original %.3f ms/draw; cubes %.3f ms/draw%n",W,H,gpuTime(baseline,false),gpuTime(current,true));
            System.out.println("PASS sky cubes GPU; driver="+glGetString(GL_RENDERER)+"; actual shader, not in-game evidence.");
        }finally{minecraft.close();glfwDestroyWindow(window);glfwTerminate();}
    }
}
