import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/** Offscreen GPU regression with a synthetic held-item mask, not Minecraft evidence. */
public final class HandFlameCheck {
    static final int W=432,H=540;
    static int checks,ubo,raw,glow,target,frame;
    static String vertex;
    static float[] capture;
    record Surface(int texture,int frame,int width,int height) { }
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static int shader(int type,String text){int s=glCreateShader(type);glShaderSource(s,text);glCompileShader(s);
        check(glGetShaderi(s,GL_COMPILE_STATUS)!=0,glGetShaderInfoLog(s));return s;}
    static int program(String fragment,String block){int p=glCreateProgram(),v=shader(GL_VERTEX_SHADER,vertex),f=shader(GL_FRAGMENT_SHADER,fragment);
        glAttachShader(p,v);glAttachShader(p,f);glLinkProgram(p);check(glGetProgrami(p,GL_LINK_STATUS)!=0,glGetProgramInfoLog(p));
        glDeleteShader(v);glDeleteShader(f);glUseProgram(p);
        glUniformBlockBinding(p,glGetUniformBlockIndex(p,block),0);
        glUniform1i(glGetUniformLocation(p,"InSampler"),0);
        int second=glGetUniformLocation(p,"GlowSampler");if(second>=0)glUniform1i(second,1);
        return p;}
    static int texture(int w,int h,float[] data){int t=glGenTextures();glBindTexture(GL_TEXTURE_2D,t);
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32F,w,h,0,GL_RGBA,GL_FLOAT,data);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);return t;}
    static Surface surface(int w,int h){int t=texture(w,h,null),f=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,f);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,t,0);
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"framebuffer");return new Surface(t,f,w,h);}
    static void uniforms(float... values){FloatBuffer b=BufferUtils.createFloatBuffer(8);b.put(values);while(b.position()<8)b.put(0);b.flip();
        glBindBuffer(GL_UNIFORM_BUFFER,ubo);glBufferSubData(GL_UNIFORM_BUFFER,0,b);}
    static void pass(int p,int input,Surface out,float... parameters){glBindFramebuffer(GL_FRAMEBUFFER,out.frame());glViewport(0,0,out.width(),out.height());
        glUseProgram(p);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,input);uniforms(parameters);glDrawArrays(GL_TRIANGLES,0,3);}
    static float[] read(Surface surface){glBindFramebuffer(GL_FRAMEBUFFER,surface.frame());float[] pixels=new float[surface.width()*surface.height()*4];
        glReadPixels(0,0,surface.width(),surface.height(),GL_RGBA,GL_FLOAT,pixels);return pixels;}
    static float[] render(int p,int mode,float time,float[] color){glBindFramebuffer(GL_FRAMEBUFFER,frame);glViewport(0,0,W,H);glUseProgram(p);
        glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,glow);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,raw);
        uniforms(color[0],color[1],color[2],.85f,mode,1.25f,1,time);glDrawArrays(GL_TRIANGLES,0,3);
        float[] pixels=new float[W*H*4];glReadPixels(0,0,W,H,GL_RGBA,GL_FLOAT,pixels);
        for(float v:pixels)check(Float.isFinite(v)&&v>=0&&v<=1.00001f,"invalid output");return pixels;}
    static void connected(float[] pixels){boolean[] seen=new boolean[W*H];int total=0,start=-1;
        for(int i=0;i<seen.length;i++)if(pixels[i*4+3]>.10f){total++;if(capture[i*4+3]>.9f)start=i;}
        check(start>=0,"item disappeared");ArrayDeque<Integer> todo=new ArrayDeque<>();todo.add(start);seen[start]=true;int count=0;
        while(!todo.isEmpty()){int i=todo.remove();count++;int x=i%W,y=i/W;
            for(int n:new int[]{x>0?i-1:-1,x+1<W?i+1:-1,y>0?i-W:-1,y+1<H?i+W:-1})
                if(n>=0&&!seen[n]&&pixels[n*4+3]>.10f){seen[n]=true;todo.add(n);}}
        check(count==total,"detached visible flame: "+(total-count)+" pixels");}
    static void image(Graphics2D graphics,float[] pixels,int column,int row,String label){BufferedImage tile=new BufferedImage(W,H,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<H;y++)for(int x=0;x<W;x++){int i=(x+y*W)*4;float a=pixels[i+3];int[] rgb=new int[3];
            for(int c=0;c<3;c++)rgb[c]=Math.round(255*(pixels[i+c]*a+.025f*(1-a)));tile.setRGB(x,H-y-1,(rgb[0]<<16)|(rgb[1]<<8)|rgb[2]);}
        graphics.drawImage(tile,column*W,row*(H+28)+28,null);graphics.setColor(Color.WHITE);graphics.drawString(label,column*W+12,row*(H+28)+20);}
    public static void main(String[] args)throws Exception{Path resources=Path.of(args[0]),before=Path.of(args[1]),output=Path.of(args[2]);Files.createDirectories(output);
        vertex=Files.readString(resources.resolve("hand_shaders.vsh"));GLFWErrorCallback callback=GLFWErrorCallback.createPrint(System.err);callback.set();
        check(glfwInit(),"GLFW");glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);
        glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);long window=glfwCreateWindow(W,H,"Celeste Flame GPU fixture",0,0);check(window!=0,"GL window");
        glfwMakeContextCurrent(window);GL.createCapabilities();glBindVertexArray(glGenVertexArrays());glDisable(GL_BLEND);glDisable(GL_DEPTH_TEST);glDepthMask(false);
        ubo=glGenBuffers();glBindBuffer(GL_UNIFORM_BUFFER,ubo);glBufferData(GL_UNIFORM_BUFFER,32,GL_DYNAMIC_DRAW);glBindBufferBase(GL_UNIFORM_BUFFER,0,ubo);
        int old=program(Files.readString(before),"HandShaderData"),current=program(Files.readString(resources.resolve("hand_shaders_composite.fsh")),"HandShaderData");
        int blur=program(Files.readString(resources.resolve("hand_shaders_blur.fsh")),"BlurData");
        capture=new float[W*H*4];for(int y=35;y<460;y++)for(int x=0;x<W;x++){
            int center=120+y/5;boolean blade=y>=145&&Math.abs(x-center)<22,handle=y<145&&Math.abs(x-center)<11,guard=y>=135&&y<158&&Math.abs(x-center)<48;
            if(!(blade||handle||guard))continue;int at=(x+y*W)*4;float shade=.13f+((x/8+y/8)%3)*.027f;
            capture[at]=shade*.55f;capture[at+1]=shade*.9f;capture[at+2]=shade;capture[at+3]=1;}
        raw=texture(W,H,capture);Surface h=surface(W/2,H/2),v=surface(W/2,H/2),fh=surface(W/4,H/4),fv=surface(W/4,H/4),out=surface(W,H);
        frame=out.frame();target=out.texture();pass(blur,raw,h,1f/W,0,1,0);pass(blur,h.texture(),v,0,1f/H,0,0);
        pass(blur,v.texture(),fh,4f/W,0,0,3);pass(blur,fh.texture(),fv,0,4f/H,0,3);
        float[] density=read(fv);for(int p=0;p<density.length;p+=4){int code=Math.round(density[p+3]*65535);density[p]=(code/256)/255f;density[p+1]=(code%256)/255f;}
        glow=texture(W/4,H/4,density);
        BufferedImage sheet=new BufferedImage(W*3,(H+28)*2,BufferedImage.TYPE_INT_RGB);Graphics2D g=sheet.createGraphics();g.setFont(new Font("SansSerif",Font.PLAIN,15));
        float[] blue={.03f,.66f,1},gold={1,.74f,.02f},first=null;int moved=0,halo=0;
        for(int t=0;t<3;t++){float seconds=new float[]{3,3+1f/60,3.7f}[t];float[] smokeBefore=render(old,2,seconds,blue),smokeAfter=render(current,2,seconds,blue);
            for(int i=0;i<smokeBefore.length;i++)check(Math.abs(smokeBefore[i]-smokeAfter[i])<1e-6f,"Smoke output changed");
            float[] result=render(current,3,seconds,blue);connected(result);
            for(int p=0;p<W*H;p++){int at=p*4;if(capture[at+3]==1)for(int c=0;c<4;c++)check(result[at+c]==capture[at+c],"item texture changed");
                if(capture[at+3]==0&&result[at+3]>.1f){halo++;if(first!=null&&Math.abs(result[at+3]-first[at+3])>.001)moved++;}}
            if(first==null)first=result;image(g,result,t,0,"Flame / blue / "+seconds+"s");image(g,render(current,3,seconds,gold),t,1,"Flame / gold / "+seconds+"s");}
        check(halo>10000,"missing exterior glow");check(moved>500,"missing smooth animation");
        for(int p:new int[]{0,W-1,W*(H-1),W*H-1})check(first[p*4+3]==0,"effect leaking to screen border");
        int empty=texture(W,H,new float[W*H*4]);raw=empty;glow=empty;for(float value:render(current,3,3,blue))check(value==0,"empty capture leaves flame");
        g.dispose();ImageIO.write(sheet,"png",output.resolve("flame-gpu-preview.png").toFile());
        check(glGetError()==GL_NO_ERROR,"GL error");System.out.println("PASS Hand Flame: "+checks+" checks; Smoke identical; connected halo; animated; item preserved; empty reset. Synthetic GPU preview only.");
        glfwDestroyWindow(window);glfwTerminate();callback.free();}
}
