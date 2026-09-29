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
    static float[] depths,terrain,pixels,worldY; static int[] faces;
    static final double TAU=Math.PI*2;
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
    static double wrap(double value){return value-Math.floor(value/1024)*1024;}
    static void uniforms(Camera c,float[] p,int mode,float intensity,float phase,float scale,float range,float fogStart,float fogEnd){ByteBuffer b=BufferUtils.createByteBuffer(160);
        b.putFloat(0,phase).putFloat(4,range).putFloat(8,scale).putFloat(12,mode);
        b.putFloat(64,46/255f).putFloat(68,175/255f).putFloat(72,214/255f).putFloat(76,intensity);
        b.putFloat(80,(float)wrap(c.position.x*scale)).putFloat(84,0).putFloat(88,(float)wrap(c.position.z*scale));matrix(b,96,c);
        glBindBuffer(GL_UNIFORM_BUFFER,dynamic);glBufferSubData(GL_UNIFORM_BUFFER,0,b);b=BufferUtils.createByteBuffer(64);
        for(int i=0;i<16;i++)b.putFloat(i*4,p[i]);glBindBuffer(GL_UNIFORM_BUFFER,projection);glBufferSubData(GL_UNIFORM_BUFFER,0,b);
        fogRanges(fogStart,fogEnd,64,96);}
    static void fogRanges(float environmentalStart,float environmentalEnd,float renderStart,float renderEnd){ByteBuffer b=BufferUtils.createByteBuffer(48);
        b.putFloat(16,environmentalStart).putFloat(20,environmentalEnd).putFloat(24,renderStart).putFloat(28,renderEnd);
        glBindBuffer(GL_UNIFORM_BUFFER,fog);glBufferSubData(GL_UNIFORM_BUFFER,0,b);}
    static Hit box(V origin,V dir,V min,V max,Hit previous){double near=-1e20,far=1e20;V n=new V(0,0,0);for(int axis=0;axis<3;axis++){
        double d=dir.at(axis),o=origin.at(axis);if(Math.abs(d)<1e-10){if(o<min.at(axis)||o>max.at(axis))return previous;continue;}
        double a=(min.at(axis)-o)/d,b=(max.at(axis)-o)/d,sign=-1;if(a>b){double swap=a;a=b;b=swap;sign=1;}
        if(a>near){near=a;n=new V(axis==0?sign:0,axis==1?sign:0,axis==2?sign:0);}far=Math.min(far,b);if(near>far)return previous;
    }return near>.05&&near<previous.distance?new Hit(near,n,n.y>.9?1:n.y<-.9?3:2):previous;}
    static void scene(Camera c,float[] p,boolean geometry,double sceneX,double sceneZ){depths=new float[w*h];terrain=new float[w*h*4];faces=new int[w*h];worldY=new float[w*h];V origin=c.origin();
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int i=x+y*w;V view=new V(((x+.5)*2/w-1+p[8])/p[0],((y+.5)*2/h-1+p[9])/p[5],-1);V ray=c.world(view);Hit hit=new Hit(256,new V(0,0,0),0);
            if(ray.y<-.000001){double d=-origin.y/ray.y;if(d>.05&&d<hit.distance)hit=new Hit(d,new V(0,1,0),1);}
            if(geometry){V local=origin.sub(new V(sceneX,0,sceneZ));
                hit=box(local,ray,new V(-5,0,-1),new V(-2,0.5,1),hit);hit=box(local,ray,new V(-5,0,-3),new V(-2,1,-1),hit);hit=box(local,ray,new V(-5,0,-5),new V(-2,1.5,-3),hit);
                hit=box(local,ray,new V(2,0,-4),new V(4,3,-1),hit);hit=box(local,ray,new V(-1.3,4,-8),new V(1.3,4.3,-4),hit);
            }
            faces[i]=hit.kind;V point=origin.add(ray.mul(hit.distance));worldY[i]=(float)point.y;
            depths[i]=hit.kind==0?1:(float)((-p[10]+p[14]/hit.distance)*.5+.5);
            if(hit.kind==0){terrain[i*4]=.055f;terrain[i*4+1]=.077f;terrain[i*4+2]=.115f;}
            else{double grain=((Math.floor(point.x*8)+Math.floor(point.z*8)+Math.floor(point.y*8))%3+3)%3;
                double mortar=(Math.abs(point.x-Math.rint(point.x))<.018||Math.abs(point.z-Math.rint(point.z))<.018)?.67:1;
                float shade=(float)((.19+grain*.021)*(hit.kind==1?1:hit.kind==2?.64:.46)*mortar);terrain[i*4]=shade*.93f;terrain[i*4+1]=shade;terrain[i*4+2]=shade*.94f;}terrain[i*4+3]=1;
        }
        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,depthTexture);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,w,h,GL_DEPTH_COMPONENT,GL_FLOAT,depths);
        glBindTexture(GL_TEXTURE_2D,depthAttachment);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,w,h,GL_DEPTH_COMPONENT,GL_FLOAT,depths);
    }
    static void draw(boolean blend){glBindFramebuffer(GL_FRAMEBUFFER,frame);glViewport(0,0,w,h);glDepthMask(false);glDisable(GL_DEPTH_TEST);glDisable(GL_CULL_FACE);
        if(blend){glEnable(GL_BLEND);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);}else glDisable(GL_BLEND);
        glUseProgram(program);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,depthTexture);glDrawArrays(GL_TRIANGLES,0,3);}
    static float[] read(){float[] out=new float[w*h*4];glReadPixels(0,0,w,h,GL_RGBA,GL_FLOAT,out);return out;}
    static float[] render(Camera c,float[] p,int mode,float intensity,float phase,float scale,float range,float fogStart,float fogEnd,boolean blend){uniforms(c,p,mode,intensity,phase,scale,range,fogStart,fogEnd);
        glBindTexture(GL_TEXTURE_2D,colorTexture);glTexSubImage2D(GL_TEXTURE_2D,0,0,0,w,h,GL_RGBA,GL_FLOAT,blend?terrain:new float[w*h*4]);draw(blend);return read();}
    static void depthUnchanged(){float[] got=new float[w*h];glReadPixels(0,0,w,h,GL_DEPTH_COMPONENT,GL_FLOAT,got);check(Arrays.equals(depths,got),"overlay changed terrain depth");}
    static void save(float[] before,float[] after,Path path,String label)throws Exception {BufferedImage image=new BufferedImage(w*2,h+30,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();
        g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.PLAIN,14));g.drawString("Textured terrain depth fixture",12,20);g.drawString(label+" | synthetic GPU",w+12,20);
        for(int side=0;side<2;side++){float[] data=side==0?before:after;for(int y=0;y<h;y++)for(int x=0;x<w;x++){int i=(x+y*w)*4;
            int r=Math.round(Math.clamp(data[i],0,1)*255),green=Math.round(Math.clamp(data[i+1],0,1)*255),b=Math.round(Math.clamp(data[i+2],0,1)*255);image.setRGB(side*w+x,h-y+29,(r<<16)|(green<<8)|b);}}
        g.dispose();ImageIO.write(image,"png",path.toFile());}
    static double meanDifference(float[] a,float[] b){double sum=0;for(int i=0;i<a.length;i++)sum+=Math.abs(a[i]-b[i]);return sum/a.length;}
    static void finite(float[] a){for(float v:a)check(Float.isFinite(v)&&v>=0&&v<=1.00001,"nonfinite/unbounded shader output");}
    static void coverage(float[] overlay){int floor=0,visible=0,walls=0,underside=0,sky=0,wrong=0,steps=0,stepVisible=0;
        for(int y=3;y<h-3;y++)for(int x=3;x<w-3;x++){int i=x+y*w;if(faces[i]==1){floor++;if(overlay[i*4+3]>.003)visible++;if(worldY[i]>.1){steps++;if(overlay[i*4+3]>.003)stepVisible++;}}
            else{if(faces[i]==2)walls++;else if(faces[i]==3)underside++;else sky++;if(overlay[i*4+3]>.0001)wrong++;}}
        check(floor>20000&&visible>floor*.55,"floor not visibly shaded: "+visible+"/"+floor);check(steps>1000&&stepVisible>steps*.9,"stairs/slabs missing: "+stepVisible+"/"+steps);
        check(walls>1000&&underside>20&&sky>100,"fixture missing wall/underside/sky cases");check(wrong==0,"non-floor/discontinuity halo pixels: "+wrong);
        System.out.printf("Coverage floor=%d/%d, stair/slab tops=%d/%d; walls=%d underside=%d sky=%d unshaded%n",visible,floor,stepVisible,steps,walls,underside,sky);}
    static void entityOcclusion(float[] shaded,float[] p){glUseProgram(entity);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDepthMask(true);glDisable(GL_BLEND);
        float depth=(-p[10]+p[14]/6f)*.5f+.5f;glUniform1f(glGetUniformLocation(entity,"Depth"),depth);glUniform2f(glGetUniformLocation(entity,"Size"),w,h);glDrawArrays(GL_TRIANGLES,0,3);float[] result=read();int front=0,occluded=0;
        for(int y=h/5+1;y<h*4/5-1;y++)for(int x=w/2-70;x<w/2+70;x++){int i=x+y*w;if(depth<=depths[i]){check(Math.abs(result[i*4]-.73)<1e-5&&Math.abs(result[i*4+1]-.16)<1e-5,"foreground entity recolored by ground");front++;}
            else{check(result[i*4]==shaded[i*4]&&result[i*4+1]==shaded[i*4+1],"entity rendered through closer terrain");occluded++;}}
        check(front>1000&&occluded>50,"entity occlusion fixture insufficient: "+front+"/"+occluded);}
    static void anchoring(){allocate(256,192);float scale=1.3f;double boundary=1024.0/scale;V target=new V(boundary,0,17.4);float[] reference=null;
        V[] positions={new V(boundary-.02,2.8,24),new V(boundary+.02,2.8,24),new V(boundary-4,3.7,22),new V(boundary+3,2.3,25)};
        for(int k=0;k<positions.length;k++){Camera c=camera(positions[k],target,k==3?.09:0,k==3?new V(.08,-.045,.025):new V(0,0,0));float[] p=perspective(c,target);scene(c,p,false,0,0);
            float[] out=render(c,p,0,.35f,.9f,scale,64,1000,2000,false);int center=(w/2+h/2*w)*4;float[] probe=Arrays.copyOfRange(out,center,center+4);
            check(probe[3]>.005,"world-lock probe missing");if(reference==null)reference=probe;else for(int i=0;i<4;i++)check(Math.abs(probe[i]-reference[i])<.0015,"camera/wrap/bob moved world pattern: "+Arrays.toString(probe)+" vs "+Arrays.toString(reference));}
        for(double offset:new double[]{-2048.0/scale,1024.0/scale}){V translated=target.add(new V(offset,0,0));Camera c=camera(positions[0].add(new V(offset,0,0)),translated,0,new V(0,0,0));float[] p=perspective(c,translated);scene(c,p,false,0,0);
            float[] out=render(c,p,0,.35f,.9f,scale,64,1000,2000,false);int center=(w/2+h/2*w)*4;for(int i=0;i<4;i++)check(Math.abs(out[center+i]-reference[i])<.0015,"negative-coordinate periodic wrap drift");}
    }
    static void timing(){allocate(1920,1080);Camera c=camera(new V(0,2.8,7),new V(0,0,-4),0,new V(0,0,0));float[] p=perspective(c,null);scene(c,p,false,0,0);
        uniforms(c,p,0,.35f,1,1,32,1000,2000);for(int i=0;i<8;i++)draw(true);glFinish();long[] times=new long[21];int q=glGenQueries();
        for(int i=0;i<times.length;i++){glBeginQuery(GL_TIME_ELAPSED,q);draw(true);glEndQuery(GL_TIME_ELAPSED);times[i]=glGetQueryObjectui64(q,GL_QUERY_RESULT);}Arrays.sort(times);
        System.out.printf(Locale.ROOT,"1080p actual GPU fullscreen pass, %s: warmed median %.3f ms, p95 %.3f ms (21 samples; excludes depth copy)%n",glGetString(GL_RENDERER),times[10]/1e6,times[19]/1e6);glDeleteQueries(q);}
    static void fogAnchoring(){allocate(256,192);V origin=new V(2.4,3.1,8.5),target=new V(1.7,0,-6);V relative=target.sub(origin);
        double spherical=Math.sqrt(relative.dot(relative)),cylindrical=Math.max(Math.hypot(relative.x,relative.z),Math.abs(relative.y));
        Camera[] cameras={camera(origin,target,0,new V(0,0,0)),camera(origin,target.add(new V(0,7,0)),.12,new V(.2,-.1,.15)),camera(origin,target.add(new V(3,-3,0)),-.16,new V(-.16,.12,-.08))};
        for(Camera c:cameras){float[] p=perspective(c,target);scene(c,p,false,0,0);int center=(w/2+h/2*w)*4;
            float base=render(c,p,0,.35f,.9f,1,64,1000,2000,false)[center+3];check(base>.005,"fog anchor floor missing");
            for(int kind=0;kind<2;kind++){fogRanges(kind==0?1000:10,kind==0?2000:20,kind==0?10:1000,kind==0?20:2000);
                glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT);draw(false);float ratio=read()[center+3]/base;
                double expected=1-((kind==0?cylindrical:spherical)-10)/10;
                check(Math.abs(ratio-expected)<.001,"camera tilt/bob changed terrain "+(kind==0?"cylindrical":"spherical")+" fog: "+ratio+" != "+expected);
            }
        }System.out.println("Native fog: camera pitch/roll/bob invariant for terrain cylindrical and spherical distances");}
    public static void main(String[] args)throws Exception {Path shaderRoot=Path.of(args[0]),gamePath=Path.of(args[1]),out=Path.of(args[2]);Files.createDirectories(out.resolve("frames"));
        check(glfwInit(),"GLFW");glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(w,h,"Ground shader GPU fixture",0,0);check(window!=0,"GL window");glfwMakeContextCurrent(window);GL.createCapabilities();glBindVertexArray(glGenVertexArrays());
        try(ZipFile game=new ZipFile(gamePath.toFile())){program=program(imports(Files.readString(shaderRoot.resolve("ground_shaders.vsh")),game),imports(Files.readString(shaderRoot.resolve("ground_shaders.fsh")),game));}
        String vs="#version 330\nvoid main(){vec2 c=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(c*2-1,0,1);}";
        entity=program(vs,"#version 330\nuniform float Depth;uniform vec2 Size;out vec4 fragColor;void main(){if(abs(gl_FragCoord.x-Size.x*.5)>71||gl_FragCoord.y<Size.y*.2||gl_FragCoord.y>Size.y*.8)discard;gl_FragDepth=Depth;fragColor=vec4(.73,.16,.085,1);}");
        bindBlock("DynamicTransforms",0);bindBlock("Projection",1);bindBlock("Fog",2);dynamic=buffer(160,0);projection=buffer(64,1);fog=buffer(48,2);glUseProgram(program);glUniform1i(glGetUniformLocation(program,"SceneDepth"),0);
        allocate(640,400);Camera c=camera(new V(0,2.8,7),new V(0,1,-4),0,new V(0,0,0));float[] p=perspective(c,null);scene(c,p,true,0,0);
        for(int mode=0;mode<2;mode++){float[] overlay=render(c,p,mode,.35f,.4f,1,32,1000,2000,false);finite(overlay);coverage(overlay);depthUnchanged();
            float[] zero=render(c,p,mode,0,.4f,1,32,1000,2000,false);check(Arrays.equals(zero,new float[zero.length]),"intensity zero not identity");
            float[] fogged=render(c,p,mode,.35f,.4f,1,32,0,.001f,false);check(Arrays.equals(fogged,new float[fogged.length]),"native fogged ground illuminated");
            float[] phase0=render(c,p,mode,.35f,0,1,32,1000,2000,false),phaseEnd=render(c,p,mode,.35f,(float)TAU,1,32,1000,2000,false);check(meanDifference(phase0,phaseEnd)<.00001,"animation loop pop");
            float[] later=render(c,p,mode,.35f,1.2f,1,32,1000,2000,false);check(meanDifference(phase0,later)>.002,"animation invisible");
            float[] subFrame=render(c,p,mode,.35f,.4f+.108f/144,1,32,1000,2000,false);double subtle=meanDifference(overlay,subFrame);check(subtle>1e-7&&subtle<.0002,"motion not smooth/frame based: "+subtle);
            float[] shaded=render(c,p,mode,.35f,.4f,1,32,1000,2000,true);finite(shaded);save(terrain,shaded,out.resolve(mode==0?"currents.png":"veins.png"),mode==0?"Currents":"Veins");
            for(int i=0;i<w*h;i++)if(overlay[i*4+3]>.001){float a=overlay[i*4+3];for(int channel=0;channel<3;channel++)check(Math.abs(shaded[i*4+channel]-(terrain[i*4+channel]*(1-a)+overlay[i*4+channel]*a))<.00001,"terrain grain not preserved by blend");}
            if(mode==0)entityOcclusion(shaded,p);scene(c,p,true,0,0);
        }
        for(int n=0;n<180;n++){float[] shaded=render(c,p,0,.35f,n/30f*.108f,1,32,1000,2000,true);save(terrain,shaded,out.resolve("frames/frame-%03d.png".formatted(n)),"Currents | default speed 0.6");}
        anchoring();fogAnchoring();timing();check(glGetError()==GL_NO_ERROR,"GL error");System.out.println("PASS GroundShaders: "+checks+" checks; shader motion, surface normals, texture blend, fog, depth, entity occlusion and camera/world anchoring");glfwDestroyWindow(window);glfwTerminate();
    }
}
