import dev.c4lam.celeste.module.impl.visual.expansion.HitDisplacementAnimation;
import dev.c4lam.celeste.module.impl.visual.expansion.HitDisplacementAnimation.Frame;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/** Production GLSL against a patterned scene/target; this is GPU evidence, not Minecraft. */
public final class HitDisplacementCheck {
    static int checks, w=480,h=320,program,ubo,fbo,color,source,depth;
    static float[] scene,depths;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static int compile(int kind,String source){int s=glCreateShader(kind);glShaderSource(s,source);glCompileShader(s);check(glGetShaderi(s,GL_COMPILE_STATUS)!=0,glGetShaderInfoLog(s));return s;}
    static String imports(String text,ZipFile game)throws Exception{
        var entry=game.getEntry("assets/minecraft/shaders/include/dynamictransforms.glsl");check(entry!=null,"Minecraft DynamicTransforms include missing");
        return text.replace("#moj_import <minecraft:dynamictransforms.glsl>",new String(game.getInputStream(entry).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).replaceAll("(?m)^#version[^\\n]*", ""));
    }
    static int texture(int internal,int channels){int t=glGenTextures();glBindTexture(GL_TEXTURE_2D,t);glTexImage2D(GL_TEXTURE_2D,0,internal,w,h,0,channels,GL_FLOAT,(ByteBuffer)null);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);return t;}
    static void allocate(int width,int height){if(fbo!=0){glDeleteFramebuffers(fbo);glDeleteTextures(color);glDeleteTextures(source);glDeleteTextures(depth);}w=width;h=height;
        color=texture(GL_RGBA32F,GL_RGBA);source=texture(GL_RGBA32F,GL_RGBA);depth=texture(GL_DEPTH_COMPONENT32F,GL_DEPTH_COMPONENT);
        fbo=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,fbo);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,color,0);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,depth,0);
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"fixture framebuffer incomplete");scene=new float[w*h*4];depths=new float[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int i=x+y*w;double u=(x+.5)/w,v=(y+.5)/h;boolean target=Math.abs(u-.5)<.045&&v>.28&&v<.71;boolean head=Math.abs(u-.5)<.033&&v>.71&&v<.81;
            float grid=(x%24<2||y%24<2)?.13f:0;float shade=target||head?(((x/7+y/9)%2==0)?.65f:.34f):.08f+(float)u*.22f+grid;
            scene[i*4]=shade*(target||head?.85f:.65f);scene[i*4+1]=shade;scene[i*4+2]=shade*(target||head?.9f:1.2f);scene[i*4+3]=.2f+(i%7)*.1f;depths[i]=(float)(.25+v*.5);}
        glBindTexture(GL_TEXTURE_2D,source);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,w,h,GL_RGBA,GL_FLOAT,scene);
        glBindTexture(GL_TEXTURE_2D,depth);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,w,h,GL_DEPTH_COMPONENT,GL_FLOAT,depths);
    }
    static float[] render(List<Frame> frames){ByteBuffer b=BufferUtils.createByteBuffer(160);for(int i=0;i<frames.size();i++){Frame f=frames.get(i);int p=i*16;
        b.putFloat(p,f.x()).putFloat(p+4,f.y()).putFloat(p+8,f.radius()).putFloat(p+12,f.strength());
        b.putFloat(96+p,(f.color()>>16&255)/255f).putFloat(100+p,(f.color()>>8&255)/255f).putFloat(104+p,(f.color()&255)/255f).putFloat(108+p,f.release());}
        glBindBuffer(GL_UNIFORM_BUFFER,ubo);glBufferSubData(GL_UNIFORM_BUFFER,0,b);glBindTexture(GL_TEXTURE_2D,color);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,w,h,GL_RGBA,GL_FLOAT,scene);
        glBindFramebuffer(GL_FRAMEBUFFER,fbo);glViewport(0,0,w,h);glUseProgram(program);glDisable(GL_DEPTH_TEST);glDepthMask(false);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);glColorMask(true,true,true,false);
        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,source);glDrawArrays(GL_TRIANGLES,0,3);float[] result=new float[w*h*4];glReadPixels(0,0,w,h,GL_RGBA,GL_FLOAT,result);
        float[] afterDepth=new float[w*h];glReadPixels(0,0,w,h,GL_DEPTH_COMPONENT,GL_FLOAT,afterDepth);check(Arrays.equals(depths,afterDepth),"displacement modified depth");
        float[] afterSource=new float[scene.length];glBindTexture(GL_TEXTURE_2D,source);glGetTexImage(GL_TEXTURE_2D,0,GL_RGBA,GL_FLOAT,afterSource);check(Arrays.equals(scene,afterSource),"private sample changed while composing");
        for(int i=0;i<w*h;i++){check(result[i*4+3]==scene[i*4+3],"displacement changed destination alpha");for(int k=0;k<3;k++)check(Float.isFinite(result[i*4+k])&&result[i*4+k]>=0&&result[i*4+k]<=1,"unbounded/nonfinite displacement color");}
        return result;
    }
    static void validateLocalized(List<Frame> frames,float[] result){int changed=0,inside=0;for(int y=0;y<h;y++)for(int x=0;x<w;x++){int i=(x+y*w)*4;boolean affected=false;
        for(Frame f:frames){double dx=((x+.5)/w-f.x())*w/h,dy=(y+.5)/h-f.y();affected|=Math.hypot(dx,dy)<f.radius()&&f.strength()>0;}
        if(!affected){for(int k=0;k<4;k++)check(result[i+k]==scene[i+k],"effect outside circle at "+x+","+y);}else{inside++;if(Math.abs(result[i]-scene[i])+Math.abs(result[i+1]-scene[i+1])>.004)changed++;}}
        check(inside>100&&changed>inside/8,"refraction too weak or absent: "+changed+"/"+inside);}
    static void save(float[] after,Path file)throws Exception{BufferedImage image=new BufferedImage(w*2,h+30,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.PLAIN,13));g.drawString("Scene + target fixture",12,20);g.drawString("Hit Displacement | synthetic GPU",w+12,20);
        for(int side=0;side<2;side++){float[] p=side==0?scene:after;for(int y=0;y<h;y++)for(int x=0;x<w;x++){int i=(x+y*w)*4;image.setRGB(side*w+x,h+29-y,Math.round(p[i]*255)<<16|Math.round(p[i+1]*255)<<8|Math.round(p[i+2]*255));}}
        g.dispose();ImageIO.write(image,"png",file.toFile());}
    static void animation(){var a=new HitDisplacementAnimation();long n=1_000_000_000;
        check(a.sample(n).isEmpty(),"new state active");a.pulse(n,.5,.5,0xAA22CCFF,1);check(a.sample(n).isEmpty(),"instant hard pop instead of fade-in");
        var early=a.sample(n+60_000_000).getFirst();var peak=a.sample(n+260_000_000).getFirst();var ending=a.sample(n+560_000_000).getFirst();
        check(early.radius()<peak.radius()&&ending.radius()<peak.radius(),"lens does not grow then contract");check(ending.strength()<peak.strength()&&ending.release()>peak.release(),"lens does not release/fade");check(peak.color()==0x22CCFF,"color not normalized");
        for(int i=0;i<20;i++)a.pulse(n+i,.5,.5,i,1);check(a.size()==4&&a.sample(n+100_000_000).size()==4,"unbounded pulses");check(a.sample(n+100_000_000).getFirst().color()==16,"oldest pulse not retired");
        a.reset();check(a.size()==0&&a.sample(n+100_000_000).isEmpty(),"reset keeps pulse state");a.pulse(n,Double.NaN,.5,0,1);check(a.size()==0,"invalid center accepted");
        a.pulse(n,-8,9,0,Double.NaN);Frame f=a.sample(n+200_000_000).getFirst();check(f.x()==0&&f.y()==1&&f.radius()>0,"center/invalid size normalization");
        check(a.sample(n-1).isEmpty(),"backward clock leaves stale hit");a.pulse(n,.5,.5,0,1);check(a.sample(n+680_000_000).isEmpty()&&a.size()==0,"expired pulse retained");
        for(double age=-.01;age<=.70;age+=.001){f=HitDisplacementAnimation.at(age,.5f,.5f,1,0);check(Float.isFinite(f.radius())&&f.radius()>=0&&f.radius()<=.126,"radius invalid");check(f.strength()>=0&&f.strength()<=1,"fade invalid");check(f.release()>=0&&f.release()<=1,"release invalid");}
        check(HitDisplacementAnimation.at(Double.NaN,.5f,.5f,1,0).strength()==0,"invalid age active");System.out.println("PASS bounded hit animation, fade/growth/release, expiry, reset and invalid input");
    }
    public static void main(String[] args)throws Exception{Path shaders=Path.of(args[0]),out=Path.of(args[2]);Files.createDirectories(out);animation();check(glfwInit(),"GLFW unavailable");glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);long window=glfwCreateWindow(64,64,"Hit Displacement GPU QA",0,0);check(window!=0,"GL context missing");glfwMakeContextCurrent(window);GL.createCapabilities();glBindVertexArray(glGenVertexArrays());
        try(ZipFile game=new ZipFile(args[1])){int v=compile(GL_VERTEX_SHADER,imports(Files.readString(shaders.resolve("hit_displacement.vsh")),game)),f=compile(GL_FRAGMENT_SHADER,imports(Files.readString(shaders.resolve("hit_displacement.fsh")),game));program=glCreateProgram();glAttachShader(program,v);glAttachShader(program,f);glLinkProgram(program);check(glGetProgrami(program,GL_LINK_STATUS)!=0,glGetProgramInfoLog(program));glDeleteShader(v);glDeleteShader(f);}
        glUseProgram(program);glUniform1i(glGetUniformLocation(program,"SceneColor"),0);int block=glGetUniformBlockIndex(program,"DynamicTransforms");check(block>=0,"DynamicTransforms missing");glUniformBlockBinding(program,block,0);ubo=glGenBuffers();glBindBuffer(GL_UNIFORM_BUFFER,ubo);glBufferData(GL_UNIFORM_BUFFER,160,GL_DYNAMIC_DRAW);glBindBufferBase(GL_UNIFORM_BUFFER,0,ubo);
        allocate(480,320);check(Arrays.equals(scene,render(List.of())),"empty frame not identity");check(Arrays.equals(scene,render(List.of(HitDisplacementAnimation.at(0,.5f,.5f,1,0x33BBFF)))),"zero-age pulse not identity");check(Arrays.equals(scene,render(List.of(HitDisplacementAnimation.at(.68,.5f,.5f,1,0x33BBFF)))),"expired pulse not identity");
        var peak=List.of(HitDisplacementAnimation.at(.24,.5f,.5f,1.4f,0x33BBFF));float[] shown=render(peak);validateLocalized(peak,shown);save(shown,out.resolve("hit-displacement.png"));
        for(int[] size:new int[][]{{640,180},{180,640},{384,256}}){allocate(size[0],size[1]);var active=List.of(HitDisplacementAnimation.at(.19,.5f,.5f,1.2f,0x33BBFF));validateLocalized(active,render(active));}
        for(double age:new double[]{.035,.14,.3,.45,.56,.65}){var active=List.of(HitDisplacementAnimation.at(age,.04f,.96f,2.5f,0xFE9900));float[] shownEdge=render(active);if(age>.1&&age<.56)validateLocalized(active,shownEdge);}
        var many=List.of(HitDisplacementAnimation.at(.13,.5f,.5f,2.5f,0xFF66EE),HitDisplacementAnimation.at(.27,.5f,.5f,2.5f,0x22CCFF),HitDisplacementAnimation.at(.41,.5f,.5f,2.5f,0xFFFFAA),HitDisplacementAnimation.at(.57,.5f,.5f,2.5f,0x99FF66));validateLocalized(many,render(many));
        check(glGetError()==GL_NO_ERROR,"OpenGL error");System.out.println("PASS actual production displacement shader: "+checks+" checks; outside identity, target distortion, all aspect ratios, edges, overlap, alpha/depth/source preservation. GPU="+glGetString(GL_RENDERER));glfwDestroyWindow(window);glfwTerminate();
    }
}
