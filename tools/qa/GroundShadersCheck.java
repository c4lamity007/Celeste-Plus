import dev.c4lam.celeste.module.impl.visual.expansion.VisualAtmosphere;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/** Actual production GLSL with geometric terrain-depth fixtures; not game evidence. */
public final class GroundShadersCheck {
    static int w=640,h=400,checks,program,entity,depthTexture,colorTexture,depthAttachment,frame,dynamic,projection,fog;
    static float[] depths,terrain,pixels,worldY,worldX,worldZ; static int[] faces;
    static boolean fractionalStair;

    static final int DEFAULT_COLOR=0x2EAFD6;
    record V(double x,double y,double z) {
        V add(V b){return new V(x+b.x,y+b.y,z+b.z);} V sub(V b){return new V(x-b.x,y-b.y,z-b.z);}
        V mul(double s){return new V(x*s,y*s,z*s);} double dot(V b){return x*b.x+y*b.y+z*b.z;}
        V cross(V b){return new V(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x);} V unit(){return mul(1/Math.sqrt(dot(this)));}
        double at(int i){return i==0?x:i==1?y:z;}
    }
    record Camera(V position,V right,V up,V back,V bob) {
        V world(V p){return right.mul(p.x).add(up.mul(p.y)).add(back.mul(p.z));}
        V view(V p){return new V(p.dot(right),p.dot(up),p.dot(back));}
        V origin(){return position.add(bob);}
    }
    record Hit(double distance,V normal,int kind) { }
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static Camera camera(V position,V target,double roll,V bob){V back=position.sub(target).unit();V right=new V(0,1,0).cross(back).unit();V up=back.cross(right);
        return new Camera(position,right.mul(Math.cos(roll)).add(up.mul(Math.sin(roll))),up.mul(Math.cos(roll)).sub(right.mul(Math.sin(roll))),back,bob);}
    static float[] perspective(Camera camera,V center){float f=(float)(1/Math.tan(Math.toRadians(66)*.5));float[] p=new float[16];
        p[0]=f*h/w;p[5]=f;p[10]=-(256f+.1f)/(256f-.1f);p[11]=-1;p[14]=-2*256f*.1f/(256f-.1f);
        if(center!=null){V v=camera.view(center.sub(camera.origin()));p[8]=(float)(p[0]*v.x/-v.z-1.0/w);p[9]=(float)(p[5]*v.y/-v.z-1.0/h);}return p;}
    static int shader(int kind,String source){int s=glCreateShader(kind);glShaderSource(s,source);glCompileShader(s);check(glGetShaderi(s,GL_COMPILE_STATUS)!=0,glGetShaderInfoLog(s));return s;}
    static int program(String vs,String fs){int p=glCreateProgram(),v=shader(GL_VERTEX_SHADER,vs),f=shader(GL_FRAGMENT_SHADER,fs);glAttachShader(p,v);glAttachShader(p,f);glLinkProgram(p);
        check(glGetProgrami(p,GL_LINK_STATUS)!=0,glGetProgramInfoLog(p));glDeleteShader(v);glDeleteShader(f);return p;}
    static String imports(String s,ZipFile game)throws Exception {for(String name:new String[]{"dynamictransforms","projection","fog"}){
        String text=new String(game.getInputStream(game.getEntry("assets/minecraft/shaders/include/"+name+".glsl")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        s=s.replace("#moj_import <minecraft:"+name+".glsl>",text.replaceAll("(?m)^#version[^\\n]*", ""));}return s;}
    static void bindBlock(String name,int point){int index=glGetUniformBlockIndex(program,name);check(index>=0,"missing block "+name);glUniformBlockBinding(program,index,point);}
    static int buffer(int bytes,int binding){int b=glGenBuffers();glBindBuffer(GL_UNIFORM_BUFFER,b);glBufferData(GL_UNIFORM_BUFFER,bytes,GL_DYNAMIC_DRAW);glBindBufferBase(GL_UNIFORM_BUFFER,binding,b);return b;}
    static int texture(int format,int component){int t=glGenTextures();glBindTexture(GL_TEXTURE_2D,t);glTexImage2D(GL_TEXTURE_2D,0,format,w,h,0,component,GL_FLOAT,(ByteBuffer)null);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);return t;}
    static void allocate(int width,int height){if(frame!=0){glDeleteFramebuffers(frame);glDeleteTextures(colorTexture);glDeleteTextures(depthTexture);glDeleteTextures(depthAttachment);}w=width;h=height;
        colorTexture=texture(GL_RGBA32F,GL_RGBA);depthTexture=texture(GL_DEPTH_COMPONENT32F,GL_DEPTH_COMPONENT);depthAttachment=texture(GL_DEPTH_COMPONENT32F,GL_DEPTH_COMPONENT);
        frame=glGenFramebuffers();glBindFramebuffer(GL_FRAMEBUFFER,frame);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,colorTexture,0);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,depthAttachment,0);check(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"FBO");}
    static void matrix(ByteBuffer b,int offset,Camera c){float[] m={(float)c.right.x,(float)c.right.y,(float)c.right.z,0,(float)c.up.x,(float)c.up.y,(float)c.up.z,0,
        (float)c.back.x,(float)c.back.y,(float)c.back.z,0,(float)c.bob.x,(float)c.bob.y,(float)c.bob.z,1};for(int i=0;i<16;i++)b.putFloat(offset+i*4,m[i]);}
    static void uniforms(Camera c,float[] p,float intensity,int color,float fogStart,float fogEnd){ByteBuffer b=BufferUtils.createByteBuffer(160);
        for(int i=0;i<4;i++)b.putFloat(i*20,1); // Unused model transform stays neutral.
        b.putFloat(64,(color>>16&255)/255f).putFloat(68,(color>>8&255)/255f).putFloat(72,(color&255)/255f).putFloat(76,intensity);
        matrix(b,96,c);glBindBuffer(GL_UNIFORM_BUFFER,dynamic);glBufferSubData(GL_UNIFORM_BUFFER,0,b);b=BufferUtils.createByteBuffer(64);
        for(int i=0;i<16;i++)b.putFloat(i*4,p[i]);glBindBuffer(GL_UNIFORM_BUFFER,projection);glBufferSubData(GL_UNIFORM_BUFFER,0,b);
        fogRanges(fogStart,fogEnd,1000,2000);}
    static void fogRanges(float environmentalStart,float environmentalEnd,float renderStart,float renderEnd){ByteBuffer b=BufferUtils.createByteBuffer(48);
        b.putFloat(16,environmentalStart).putFloat(20,environmentalEnd).putFloat(24,renderStart).putFloat(28,renderEnd);
        glBindBuffer(GL_UNIFORM_BUFFER,fog);glBufferSubData(GL_UNIFORM_BUFFER,0,b);}
    static Hit box(V origin,V dir,V min,V max,Hit previous){double near=-1e20,far=1e20;V n=new V(0,0,0);for(int axis=0;axis<3;axis++){
        double d=dir.at(axis),o=origin.at(axis);if(Math.abs(d)<1e-10){if(o<min.at(axis)||o>max.at(axis))return previous;continue;}
        double a=(min.at(axis)-o)/d,b=(max.at(axis)-o)/d,sign=-1;if(a>b){double swap=a;a=b;b=swap;sign=1;}
        if(a>near){near=a;n=new V(axis==0?sign:0,axis==1?sign:0,axis==2?sign:0);}far=Math.min(far,b);if(near>far)return previous;
    }return near>.05&&near<previous.distance?new Hit(near,n,n.y>.9?1:n.y<-.9?3:2):previous;}
    static void scene(Camera c,float[] p,boolean geometry,double sceneX,double sceneZ){depths=new float[w*h];terrain=new float[w*h*4];faces=new int[w*h];worldY=new float[w*h];worldX=new float[w*h];worldZ=new float[w*h];V origin=c.origin();
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int i=x+y*w;V view=new V(((x+.5)*2/w-1+p[8])/p[0],((y+.5)*2/h-1+p[9])/p[5],-1);V ray=c.world(view);Hit hit=new Hit(256,new V(0,0,0),0);
            if(ray.y<-.000001){double d=-origin.y/ray.y;if(d>.05&&d<hit.distance)hit=new Hit(d,new V(0,1,0),1);}
            if(geometry){V local=origin.sub(new V(sceneX,0,sceneZ));
                hit=box(local,ray,new V(-5,0,-1),new V(-2,0.5,1),hit);hit=box(local,ray,new V(-5,0,-3),new V(-2,1,-1),hit);hit=box(local,ray,new V(-5,0,-5),new V(-2,1.5,-3),hit);
                hit=box(local,ray,new V(2,0,-4),new V(4,3,-1),hit);hit=box(local,ray,new V(-1.3,4,-8),new V(1.3,4.3,-4),hit);
            }
            if(fractionalStair){V local=origin.sub(new V(sceneX,0,sceneZ));hit=box(local,ray,new V(-1,0,2),new V(0,.5,3),hit);hit=box(local,ray,new V(-.5,.5,2),new V(0,1,3),hit);}
            faces[i]=hit.kind;V point=origin.add(ray.mul(hit.distance));worldY[i]=(float)point.y;worldX[i]=(float)point.x;worldZ[i]=(float)point.z;
            depths[i]=hit.kind==0?1:(float)((-p[10]+p[14]/hit.distance)*.5+.5);
            if(hit.kind==0){terrain[i*4]=.055f;terrain[i*4+1]=.077f;terrain[i*4+2]=.115f;}
            else{double grain=((Math.floor(point.x*8)+Math.floor(point.z*8)+Math.floor(point.y*8))%3+3)%3;
                double mortar=(Math.abs(point.x-Math.rint(point.x))<.018||Math.abs(point.z-Math.rint(point.z))<.018)?.67:1;
                float shade=(float)((.19+grain*.021)*(hit.kind==1?1:hit.kind==2?.64:.46)*mortar);terrain[i*4]=shade*.93f;terrain[i*4+1]=shade;terrain[i*4+2]=shade*.94f;}terrain[i*4+3]=.3f+(i%7)*.1f;
        }
        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,depthTexture);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,w,h,GL_DEPTH_COMPONENT,GL_FLOAT,depths);
        glBindTexture(GL_TEXTURE_2D,depthAttachment);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,w,h,GL_DEPTH_COMPONENT,GL_FLOAT,depths);
    }
    static void draw(boolean blend){glBindFramebuffer(GL_FRAMEBUFFER,frame);glViewport(0,0,w,h);glDepthMask(false);glDisable(GL_DEPTH_TEST);glDisable(GL_CULL_FACE);
        glColorMask(true,true,true,!blend);
        if(blend){glEnable(GL_BLEND);glBlendFuncSeparate(GL_DST_COLOR,GL_ZERO,GL_ZERO,GL_ONE);}else glDisable(GL_BLEND);
        glUseProgram(program);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,depthTexture);glDrawArrays(GL_TRIANGLES,0,3);}
    static float[] read(){float[] out=new float[w*h*4];glReadPixels(0,0,w,h,GL_RGBA,GL_FLOAT,out);return out;}
    static float[] render(Camera c,float[] p,float intensity,int color,float fogStart,float fogEnd,boolean blend){uniforms(c,p,intensity,color,fogStart,fogEnd);
        glBindTexture(GL_TEXTURE_2D,colorTexture);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,w,h,GL_RGBA,GL_FLOAT,blend?terrain:new float[w*h*4]);draw(blend);return read();}
    static void depthUnchanged(){float[] got=new float[w*h];glReadPixels(0,0,w,h,GL_DEPTH_COMPONENT,GL_FLOAT,got);check(Arrays.equals(depths,got),"overlay changed terrain depth");}
    static void save(float[] before,float[] after,Path path,String label)throws Exception {BufferedImage image=new BufferedImage(w*2,h+30,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();
        g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.PLAIN,14));g.drawString("Textured terrain depth fixture",12,20);g.drawString(label+" | synthetic GPU",w+12,20);
        for(int side=0;side<2;side++){float[] data=side==0?before:after;for(int y=0;y<h;y++)for(int x=0;x<w;x++){int i=(x+y*w)*4;
            int r=Math.round(Math.clamp(data[i],0,1)*255),green=Math.round(Math.clamp(data[i+1],0,1)*255),b=Math.round(Math.clamp(data[i+2],0,1)*255);image.setRGB(side*w+x,h-y+29,(r<<16)|(green<<8)|b);}}
        g.dispose();ImageIO.write(image,"png",path.toFile());}
    static double meanDifference(float[] a,float[] b){double sum=0;for(int i=0;i<a.length;i++)sum+=Math.abs(a[i]-b[i]);return sum/a.length;}
    static void finite(float[] a){for(float v:a)check(Float.isFinite(v)&&v>=0&&v<=1.00001,"nonfinite/unbounded shader output");}
    static double[] gains(int rgb,double intensity){double r=rgb>>16&255,g=rgb>>8&255,b=rgb&255,max=Math.max(1e-6,Math.max(r,Math.max(g,b)));
        return new double[]{1+(r/max-1)*intensity,1+(g/max-1)*intensity,1+(b/max-1)*intensity};}
    static void coverage(float[] gain,int color,float intensity){int[] seen=new int[4];int stairs=0;double[] expected=gains(color,intensity);
        for(int i=0;i<w*h;i++){seen[faces[i]]++;if(faces[i]==1&&worldY[i]>.1)stairs++;
            for(int k=0;k<3;k++)check(Math.abs(gain[i*4+k]-(faces[i]==0?0:expected[k]))<.00001,"missing face, border gap or spatial pattern at face "+faces[i]);}
        check(seen[1]>20000&&stairs>1000&&seen[2]>1000&&seen[3]>20&&seen[0]>100,"fixture missing terrain orientations/sky");
        System.out.printf("Uniform tint coverage: tops=%d (stairs/slabs=%d), walls=%d, undersides=%d; unchanged sky=%d%n",seen[1],stairs,seen[2],seen[3],seen[0]);}
    static void texturePreserved(float[] shaded,int color,float intensity){double[] expected=gains(color,intensity);int lit=0,dark=0;
        for(int i=0;i<w*h;i++){
            check(shaded[i*4+3]==terrain[i*4+3],"destination alpha changed");
            for(int k=0;k<3;k++)check(Math.abs(shaded[i*4+k]-terrain[i*4+k]*(faces[i]==0?1:expected[k]))<.00001,"texture/light contrast not preserved by per-channel multiplication");
            if(faces[i]>0){if(terrain[i*4+1]>.2)lit++;if(terrain[i*4+1]<.13)dark++;}}
        check(lit>1000&&dark>1000,"fixture lacks lit and shadowed texture contrast");}
    static void entityOcclusion(float[] shaded,float[] p){glUseProgram(entity);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDepthMask(true);glDisable(GL_BLEND);
        float depth=(-p[10]+p[14]/6f)*.5f+.5f;glUniform1f(glGetUniformLocation(entity,"Depth"),depth);glUniform2f(glGetUniformLocation(entity,"Size"),w,h);glDrawArrays(GL_TRIANGLES,0,3);float[] result=read();int front=0,occluded=0;
        for(int y=h/5+1;y<h*4/5-1;y++)for(int x=w/2-70;x<w/2+70;x++){int i=x+y*w;if(depth<=depths[i]){check(Math.abs(result[i*4]-.73)<1e-5&&Math.abs(result[i*4+1]-.16)<1e-5,"foreground entity recolored by ground");front++;}
            else{check(result[i*4]==shaded[i*4]&&result[i*4+1]==shaded[i*4+1],"entity rendered through closer terrain");occluded++;}}
        check(front>1000&&occluded>50,"entity occlusion fixture insufficient: "+front+"/"+occluded);}
    static void exposedElevations(Path out)throws Exception {
        allocate(640,400);Camera c=camera(new V(0,7.2,7),new V(0,1,-4),0,new V(0,0,0));
        float[] p=perspective(c,null);scene(c,p,true,0,0);
        float[] shown=render(c,p,.35f,DEFAULT_COLOR,1000,2000,true);
        texturePreserved(shown,DEFAULT_COLOR,.35f);depthUnchanged();
        int lowSteps=0,tallTop=0,floatingTop=0,sides=0;
        for(int i=0;i<w*h;i++){
            if(faces[i]==1){if(worldY[i]>.1&&worldY[i]<2)lowSteps++;if(worldY[i]==3)tallTop++;if(worldY[i]>4)floatingTop++;}
            if(faces[i]==2)sides++;
        }
        check(lowSteps>1000&&tallTop>500&&floatingTop>500&&sides>1000,"exposed higher surfaces fixture insufficient");
        save(terrain,shown,out.resolve("exposed-terrain.png"),"Ambient tint | steps, tower and floating tops included");
        System.out.printf("Exposed elevated surfaces: steps=%d, isolated tall top=%d, floating top=%d, sides=%d%n",lowSteps,tallTop,floatingTop,sides);
    }
    static void fractionalTops(){allocate(640,400);fractionalStair=true;
        Camera c=camera(new V(-2,4,7),new V(-.5,.5,2.5),0,new V(0,0,0));float[] p=perspective(c,null);scene(c,p,false,0,0);
        float[] shaded=render(c,p,.35f,DEFAULT_COLOR,1000,2000,true);double[] gain=gains(DEFAULT_COLOR,.35f);int lower=0,upper=0;
        for(int i=0;i<w*h;i++)if(faces[i]==1&&worldX[i]>=-1&&worldX[i]<0&&worldZ[i]>=2&&worldZ[i]<3&&(worldY[i]==.5f||worldY[i]==1f)){
            if(worldY[i]==.5f)lower++;else upper++;
            for(int k=0;k<3;k++)check(Math.abs(shaded[i*4+k]-terrain[i*4+k]*gain[k])<.00001,"exposed fractional top missed: height="+worldY[i]);
            check(shaded[i*4+3]==terrain[i*4+3],"fractional top changed alpha");
        }
        check(lower>300&&upper>300,"fractional stair shape fixture insufficient");depthUnchanged();
        System.out.printf("Both exposed stair heights tinted immediately: lowerPixels=%d upperPixels=%d%n",lower,upper);fractionalStair=false;
    }
    static void invariance(){allocate(256,192);V target=new V(1024,0,17.4);double[] expected=gains(DEFAULT_COLOR,.35f);
        V[] positions={new V(1023.98,2.8,24),new V(1024.02,2.8,24),new V(1020,3.7,22),new V(1027,2.3,25)};
        for(int n=0;n<positions.length;n++){Camera c=camera(positions[n],target,n==3?.09:0,n==3?new V(.08,-.045,.025):new V(0,0,0));float[] p=perspective(c,target);scene(c,p,false,0,0);
            float[] gain=render(c,p,.35f,DEFAULT_COLOR,1000,2000,false);int center=(w/2+h/2*w)*4;
            for(int k=0;k<3;k++)check(Math.abs(gain[center+k]-expected[k])<.00001,"camera motion/bob/roll changed ambient tint");
            for(int i=0;i<w*h;i++)if(faces[i]>0)for(int k=0;k<3;k++)check(Math.abs(gain[i*4+k]-expected[k])<.00001,"position, distance or screen coordinates introduced a pattern");
            check(Arrays.equals(gain,render(c,p,.35f,DEFAULT_COLOR,1000,2000,false)),"identical frames changed tint");}
        System.out.println("Constant tint: camera movement, world wrap boundary, pitch/roll/bob and repeated frames do not add pattern variation");}
    static void distanceCoverage(){allocate(640,400);Camera c=camera(new V(0,2.8,7),new V(0,0,-40),0,new V(0,0,0));float[] p=perspective(c,null);scene(c,p,false,0,0);
        float[] gain=render(c,p,.35f,DEFAULT_COLOR,1000,2000,false);double expected=gains(DEFAULT_COLOR,.35f)[0];int near=0,far=0;
        for(int i=0;i<w*h;i++)if(faces[i]>0){double distance=p[14]/(depths[i]*2-1+p[10]);if(distance<10)near++;if(distance>96&&distance<230)far++;check(Math.abs(gain[i*4]-expected)<.00001,"artificial distance limit on terrain");}
        check(near>1000&&far>100,"near/far scene insufficient: "+near+"/"+far);fogRanges(1000,2000,64,96);
        glColorMask(true,true,true,true);glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT);draw(false);float[] fogged=read();int opaque=0,transition=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int i=x+y*w;if(faces[i]==0)continue;double distance=p[14]/(depths[i]*2-1+p[10]);V view=new V((((x+.5)*2/w-1+p[8])/p[0])*distance,(((y+.5)*2/h-1+p[9])/p[5])*distance,-distance);
            V relative=c.world(view).add(c.bob);double cylinder=Math.max(Math.hypot(relative.x,relative.z),Math.abs(relative.y));
            if(cylinder>96.01){check(fogged[i*4]==0,"fully fogged far terrain tinted");opaque++;}else if(cylinder>65&&cylinder<95){double visibility=1-(cylinder-64)/32;
                check(Math.abs(fogged[i*4]-(1+(expected-1)*visibility))<.0001,"render-distance fog transition wrong");transition++;}}
        check(opaque>100&&transition>100,"distance fog fixture insufficient");System.out.printf("Terrain distance: near=%d, beyond96blocks=%d, fog transition=%d, fully fogged=%d%n",near,far,transition,opaque);}
    static void timing(){allocate(1920,1080);Camera c=camera(new V(0,2.8,7),new V(0,0,-4),0,new V(0,0,0));float[] p=perspective(c,null);scene(c,p,false,0,0);
        uniforms(c,p,.35f,DEFAULT_COLOR,1000,2000);for(int i=0;i<8;i++)draw(true);glFinish();long[] times=new long[21];int q=glGenQueries();
        for(int i=0;i<times.length;i++){glBeginQuery(GL_TIME_ELAPSED,q);draw(true);glEndQuery(GL_TIME_ELAPSED);times[i]=glGetQueryObjectui64(q,GL_QUERY_RESULT);}Arrays.sort(times);
        System.out.printf(Locale.ROOT,"1080p actual GPU fullscreen pass, %s: warmed median %.3f ms, p95 %.3f ms (21 samples; excludes depth copy)%n",glGetString(GL_RENDERER),times[10]/1e6,times[19]/1e6);glDeleteQueries(q);}
    static void fogAnchoring(){allocate(256,192);V origin=new V(2.4,3.1,8.5),target=new V(1.7,0,-6);V relative=target.sub(origin);
        double spherical=Math.sqrt(relative.dot(relative)),cylindrical=Math.max(Math.hypot(relative.x,relative.z),Math.abs(relative.y));
        Camera[] cameras={camera(origin,target,0,new V(0,0,0)),camera(origin,target.add(new V(0,7,0)),.12,new V(.2,-.1,.15)),camera(origin,target.add(new V(3,-3,0)),-.16,new V(-.16,.12,-.08))};
        for(Camera c:cameras){float[] p=perspective(c,target);scene(c,p,false,0,0);int center=(w/2+h/2*w)*4;
            float base=render(c,p,.35f,DEFAULT_COLOR,1000,2000,false)[center];check(base>.1&&base<.9,"fog anchor terrain missing");
            for(int kind=0;kind<2;kind++){fogRanges(kind==0?1000:10,kind==0?2000:20,kind==0?10:1000,kind==0?20:2000);
                glColorMask(true,true,true,true);glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT);draw(false);float ratio=(1-read()[center])/(1-base);
                double expected=1-((kind==0?cylindrical:spherical)-10)/10;
                check(Math.abs(ratio-expected)<.001,"camera tilt/bob changed terrain "+(kind==0?"cylindrical":"spherical")+" fog: "+ratio+" != "+expected);
            }
        }System.out.println("Native fog: camera pitch/roll/bob invariant for terrain cylindrical and spherical distances");}
    public static void main(String[] args)throws Exception {Path shaderRoot=Path.of(args[0]),gamePath=Path.of(args[1]),out=Path.of(args[2]);Files.createDirectories(out);
        check(glfwInit(),"GLFW");glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(w,h,"Ground shader GPU fixture",0,0);check(window!=0,"GL window");glfwMakeContextCurrent(window);GL.createCapabilities();glBindVertexArray(glGenVertexArrays());
        try(ZipFile game=new ZipFile(gamePath.toFile())){program=program(imports(Files.readString(shaderRoot.resolve("ground_shaders.vsh")),game),imports(Files.readString(shaderRoot.resolve("ground_shaders.fsh")),game));}
        String vs="#version 330\nvoid main(){vec2 c=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(c*2-1,0,1);}";
        entity=program(vs,"#version 330\nuniform float Depth;uniform vec2 Size;out vec4 fragColor;void main(){if(abs(gl_FragCoord.x-Size.x*.5)>71||gl_FragCoord.y<Size.y*.2||gl_FragCoord.y>Size.y*.8)discard;gl_FragDepth=Depth;fragColor=vec4(.73,.16,.085,1);}");
        bindBlock("DynamicTransforms",0);bindBlock("Projection",1);bindBlock("Fog",2);dynamic=buffer(160,0);projection=buffer(64,1);fog=buffer(48,2);glUseProgram(program);glUniform1i(glGetUniformLocation(program,"SceneDepth"),0);
        allocate(640,400);Camera c=camera(new V(0,2.8,7),new V(0,1,-4),0,new V(0,0,0));float[] p=perspective(c,null);scene(c,p,true,0,0);
        float[] gain=render(c,p,.35f,DEFAULT_COLOR,1000,2000,false);finite(gain);coverage(gain,DEFAULT_COLOR,.35f);depthUnchanged();
        float[] shaded=render(c,p,.35f,DEFAULT_COLOR,1000,2000,true);finite(shaded);texturePreserved(shaded,DEFAULT_COLOR,.35f);depthUnchanged();
        save(terrain,shaded,out.resolve("ambient-terrain.png"),"Ambient tint | all terrain faces | 35%");entityOcclusion(shaded,p);scene(c,p,true,0,0);
        for(float strength:new float[]{0,.1f,.35f,1})for(int color:new int[]{DEFAULT_COLOR,0xFFFFFF,0x000000,0xEC7733}){
            float[] output=render(c,p,strength,color,1000,2000,true);finite(output);texturePreserved(output,color,strength);depthUnchanged();
            if(strength==0||color==0xFFFFFF)check(Arrays.equals(output,terrain),"zero strength / white tint not exact identity");}
        float[] fullyFogged=render(c,p,.35f,DEFAULT_COLOR,0,.001f,true);check(Arrays.equals(fullyFogged,terrain),"fully fogged scene changed");
        for(VisualAtmosphere.Preset preset:VisualAtmosphere.Preset.values()){
            int color=VisualAtmosphere.sample(preset,0,0);float[] output=render(c,p,.35f,color,1000,2000,true);texturePreserved(output,color,.35f);}
        exposedElevations(out);fractionalTops();invariance();distanceCoverage();fogAnchoring();timing();check(glGetError()==GL_NO_ERROR,"GL error");
        System.out.println("PASS GroundShaders ambient: "+checks+" checks; every terrain face, texture/lighting contrast, alpha/depth, sky/entities, near/far fog, palette and camera invariance. Synthetic GPU fixture, not game evidence.");glfwDestroyWindow(window);glfwTerminate();
    }
}
