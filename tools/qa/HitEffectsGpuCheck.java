import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/** Real hit sprite GLSL and Minecraft includes; the picture is a synthetic GPU fixture. */
public final class HitEffectsGpuCheck {
    static final int W=192,H=192;
    static int checks,program,vbo,fog;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static String imports(String source,ZipFile game)throws Exception {
        for(String name:new String[]{"dynamictransforms","projection","fog"}) {
            String include=new String(game.getInputStream(game.getEntry("assets/minecraft/shaders/include/"+name+".glsl")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            source=source.replace("#moj_import <minecraft:"+name+".glsl>",include.replaceAll("(?m)^#version[^\\n]*",""));
        }return source;
    }
    static int shader(int type,String source){int s=glCreateShader(type);glShaderSource(s,source);glCompileShader(s);check(glGetShaderi(s,GL_COMPILE_STATUS)!=0,glGetShaderInfoLog(s));return s;}
    static int block(String name,int binding,int size){int index=glGetUniformBlockIndex(program,name);check(index>=0,"block "+name);glUniformBlockBinding(program,index,binding);
        int buffer=glGenBuffers();glBindBuffer(GL_UNIFORM_BUFFER,buffer);glBufferData(GL_UNIFORM_BUFFER,size,GL_DYNAMIC_DRAW);glBindBufferBase(GL_UNIFORM_BUFFER,binding,buffer);return buffer;}
    static void fog(float start,float end){ByteBuffer b=BufferUtils.createByteBuffer(48);b.putFloat(16,start).putFloat(20,end).putFloat(24,1000).putFloat(28,2000);glBindBuffer(GL_UNIFORM_BUFFER,fog);glBufferSubData(GL_UNIFORM_BUFFER,0,b);}
    static void attributes(){int[] sizes={3,2,4},offsets={0,12,20};String[] names={"Position","UV0","Color"};
        for(int i=0;i<3;i++){int a=glGetAttribLocation(program,names[i]);check(a>=0,"attribute "+names[i]);glEnableVertexAttribArray(a);glVertexAttribPointer(a,sizes[i],GL_FLOAT,false,36,offsets[i]);}}
    static float[] render(int mode,float opacity,float depth,boolean rotated){
        glColorMask(true,true,true,true);glDepthMask(true);glClearColor(.025f,.035f,.05f,.375f);glClearDepth(depth);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
        float[] vertices=new float[6*9];int[] corners={0,1,2,0,2,3};float[] xs={-.9f,.9f,.9f,-.9f},ys={-.9f,-.9f,.9f,.9f};
        float[] us={0,1,1,0},vs={0,0,1,1};
        for(int i=0;i<6;i++){int k=corners[i],o=i*9;float x=xs[k],y=ys[k];
            vertices[o]=rotated?(x-y)*.70710678f:x;vertices[o+1]=rotated?(x+y)*.70710678f:y;vertices[o+2]=-.4f;
            vertices[o+3]=mode*2+us[k];vertices[o+4]=vs[k];vertices[o+5]=.17f;vertices[o+6]=.72f;vertices[o+7]=.95f;vertices[o+8]=opacity;}
        glBindBuffer(GL_ARRAY_BUFFER,vbo);glBufferData(GL_ARRAY_BUFFER,vertices,GL_DYNAMIC_DRAW);
        glEnable(GL_BLEND);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE,GL_ZERO,GL_ONE);glColorMask(true,true,true,false);
        glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDepthMask(false);glDrawArrays(GL_TRIANGLES,0,6);
        float[] result=new float[W*H*4];glReadPixels(0,0,W,H,GL_RGBA,GL_FLOAT,result);
        float[] depths=new float[W*H];glReadPixels(0,0,W,H,GL_DEPTH_COMPONENT,GL_FLOAT,depths);
        for(int i=0;i<W*H;i++){check(result[i*4+3]==.375f,"destination alpha changed");check(Math.abs(depths[i]-depth)<1e-6,"depth changed");for(int c=0;c<3;c++)check(Float.isFinite(result[i*4+c]),"nonfinite color");}
        return result;
    }
    static double energy(float[] pixels){double sum=0;for(int i=0;i<W*H;i++)sum+=pixels[i*4+2]-.05;return sum;}
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]),gamePath=Path.of(args[1]),out=Path.of(args[2]);Files.createDirectories(out);
        check(glfwInit(),"GLFW");glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(W,H,"Hit effect GPU fixture",0,0);check(window!=0,"GL window");glfwMakeContextCurrent(window);GL.createCapabilities();glBindVertexArray(glGenVertexArrays());
        try(ZipFile game=new ZipFile(gamePath.toFile())){int vertex=shader(GL_VERTEX_SHADER,imports(Files.readString(root.resolve("hit_effects.vsh")),game));int fragment=shader(GL_FRAGMENT_SHADER,imports(Files.readString(root.resolve("hit_effects.fsh")),game));
            program=glCreateProgram();glAttachShader(program,vertex);glAttachShader(program,fragment);glLinkProgram(program);check(glGetProgrami(program,GL_LINK_STATUS)!=0,glGetProgramInfoLog(program));glDeleteShader(vertex);glDeleteShader(fragment);}
        glUseProgram(program);int dynamic=block("DynamicTransforms",0,160),projection=block("Projection",1,64);fog=block("Fog",2,48);
        ByteBuffer data=BufferUtils.createByteBuffer(160);for(int i=0;i<4;i++){data.putFloat(i*20,1);data.putFloat(64+i*4,1);}glBindBuffer(GL_UNIFORM_BUFFER,dynamic);glBufferSubData(GL_UNIFORM_BUFFER,0,data);
        data=BufferUtils.createByteBuffer(64);for(int i=0;i<4;i++)data.putFloat(i*20,1);glBindBuffer(GL_UNIFORM_BUFFER,projection);glBufferSubData(GL_UNIFORM_BUFFER,0,data);
        int fbo=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,fbo);int color=glGenTextures();glBindTexture(GL_TEXTURE_2D,color);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32F,W,H,0,GL_RGBA,GL_FLOAT,(ByteBuffer)null);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,color,0);
        int depth=glGenTextures();glBindTexture(GL_TEXTURE_2D,depth);glTexImage2D(GL_TEXTURE_2D,0,GL_DEPTH_COMPONENT32F,W,H,0,GL_DEPTH_COMPONENT,GL_FLOAT,(ByteBuffer)null);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,depth,0);check(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"FBO");
        vbo=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,vbo);attributes();glViewport(0,0,W,H);glDisable(GL_CULL_FACE);
        BufferedImage preview=new BufferedImage(W*4,H+42,BufferedImage.TYPE_INT_RGB);Graphics2D g=preview.createGraphics();g.setColor(new Color(6,9,13));g.fillRect(0,0,preview.getWidth(),preview.getHeight());g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.PLAIN,12));
        String[] names={"Dots","Stars","Sparks","Tron beam"};float[][] results=new float[4][];
        for(int mode=0;mode<4;mode++) {
            fog(1000,2000);results[mode]=render(mode,1,1,false);double full=energy(results[mode]);check(full>100,"visible "+names[mode]);
            check(Math.abs(energy(render(mode,.5f,1,false))/full-.5)<.001,"vertex alpha fades "+names[mode]);
            check(Math.abs(energy(render(mode,0,1,false)))<.001,"transparent hidden");
            check(Math.abs(energy(render(mode,1,.1f,false)))<.001,"behind terrain hidden");
            fog(0,.001f);check(Math.abs(energy(render(mode,1,1,false)))<.001,"full fog hidden");fog(0,2);
            double partial=energy(render(mode,1,1,false));check(partial>0 && partial<full,"partial fog");
            fog(1000,2000);if(mode==1||mode==2)check(!Arrays.equals(results[mode],render(mode,1,1,true)),"sprite rotates");
            g.drawString(names[mode]+" | synthetic GPU",mode*W+6,17);
            for(int y=0;y<H;y++)for(int x=0;x<W;x++){int i=(x+y*W)*4;float[] p=results[mode];int r=Math.round(Math.clamp(p[i],0,1)*255),green=Math.round(Math.clamp(p[i+1],0,1)*255),b=Math.round(Math.clamp(p[i+2],0,1)*255);preview.setRGB(mode*W+x,H-y+24,(r<<16)|(green<<8)|b);}
        }
        check(!Arrays.equals(results[0],results[1])&&!Arrays.equals(results[1],results[2]),"distinct shapes");g.dispose();ImageIO.write(preview,"png",out.resolve("hit-effects-gpu.png").toFile());
        check(glGetError()==GL_NO_ERROR,"GL error");System.out.println("PASS hit-effects-gpu: "+checks+" checks; actual GLSL sprites, beams, rotation, alpha, fog and terrain depth. Synthetic fixture, not game evidence.");glfwDestroyWindow(window);glfwTerminate();
    }
}
