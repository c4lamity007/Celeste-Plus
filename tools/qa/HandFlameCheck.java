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
    static int checks,ubo,raw,glow,currentGlow,target,frame;
    static Surface horizontal,vertical,fogHorizontal,fogVertical,historyRead,historyWrite;
    static int blurProgram,historyProgram;
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
        int third=glGetUniformLocation(p,"CurrentGlowSampler");if(third>=0)glUniform1i(third,2);
        int history=glGetUniformLocation(p,"HistorySampler");if(history>=0)glUniform1i(history,1);return p;}
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
        glActiveTexture(GL_TEXTURE2);glBindTexture(GL_TEXTURE_2D,currentGlow);
        glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,glow);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,raw);
        uniforms(color[0],color[1],color[2],.85f,mode,.75f,1,time);glDrawArrays(GL_TRIANGLES,0,3);
        float[] pixels=new float[W*H*4];glReadPixels(0,0,W,H,GL_RGBA,GL_FLOAT,pixels);
        boolean finite=true;for(float v:pixels)finite &= Float.isFinite(v)&&v>=0&&v<=1.00001f;check(finite,"invalid output");return pixels;}
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
    static float[] mask(int shift) {
        float[] result=new float[W*H*4];for(int y=35;y<460;y++)for(int x=0;x<W;x++){
            int center=120+y/5+shift;boolean blade=y>=145&&Math.abs(x-center)<22,handle=y<145&&Math.abs(x-center)<11,guard=y>=135&&y<158&&Math.abs(x-center)<48;
            if(!(blade||handle||guard))continue;int at=(x+y*W)*4;float shade=.13f+((x/8+y/8)%3)*.027f;
            if(y<125){result[at]=.30f+shade*.55f;result[at+1]=.13f+shade*.5f;result[at+2]=.07f+shade*.25f;}
            else{result[at]=shade*.55f;result[at+1]=shade*.9f;result[at+2]=shade;}result[at+3]=1;
        }return result;
    }
    static float[] swingingMask(double angle) {
        float[] source=mask(0),result=new float[source.length];double c=Math.cos(angle),s=Math.sin(angle);
        for(int y=0;y<H;y++)for(int x=0;x<W;x++){
            double px=x-148,py=y-145;int sx=(int)Math.round(c*px+s*py+148),sy=(int)Math.round(-s*px+c*py+145);
            if(sx>=0&&sx<W&&sy>=0&&sy<H)System.arraycopy(source,(sx+sy*W)*4,result,(x+y*W)*4,4);
        }return result;
    }
    static double luma(float[] pixels,int i){return pixels[i]*.2126+pixels[i+1]*.7152+pixels[i+2]*.0722;}
    static void coating(float[] pixels) {
        int opaque=0,changed=0,edges=0,readable=0;double contrast=0;
        for(int y=45;y<450;y++)for(int x=2;x<W-2;x++){
            int i=(x+y*W)*4;if(capture[i+3]<.99f)continue;opaque++;
            check(pixels[i+3]==1,"coating changed opaque silhouette alpha");
            if(Math.abs(luma(pixels,i)-luma(capture,i))>.003)changed++;
            boolean interior=true;for(int dy:new int[]{-4,0,4})for(int dx:new int[]{-4,0,4})if(x+dx<0||x+dx>=W||capture[(x+dx+(y+dy)*W)*4+3]<.99f)interior=false;if(!interior)continue;
            double original=luma(capture,i)-luma(capture,i-4);
            if(Math.abs(original)>.02){edges++;double localGradient=.5*((luma(pixels,i+4)-luma(pixels,i))+(luma(pixels,i-4)-luma(pixels,i-8)));double detail=luma(pixels,i)-luma(pixels,i-4)-localGradient;if(detail*original>0)readable++;contrast+=Math.abs(detail);}
        }
        check(changed>opaque*.80,"missing shader coat on item/hand body");
        check(edges>100&&readable>edges*.94&&contrast/edges>.005,"captured pixel detail was flattened by coat: readable="+readable+"/"+edges+", contrast="+(contrast/Math.max(1,edges)));
    }
    static void comparison(float[] before,float[] after,Path path,String label)throws Exception {
        BufferedImage image=new BufferedImage(W*2,H+28,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();
        g.setFont(new Font("SansSerif",Font.PLAIN,13));image(g,before,0,0,"Previous Flame | synthetic GPU");
        image(g,after,1,0,label+" | synthetic GPU");g.dispose();ImageIO.write(image,"png",path.toFile());
    }
    static Surface historySurface() {
        Surface result=surface(W/4,H/4);glBindTexture(GL_TEXTURE_2D,result.texture());
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,W/4,H/4,0,GL_RGBA,GL_UNSIGNED_BYTE,(ByteBuffer)null);return result;
    }
    static void upload(float[] mask) {
        capture=mask;glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,raw);
        glTexSubImage2D(GL_TEXTURE_2D,0,0,0,W,H,GL_RGBA,GL_FLOAT,mask);
        pass(blurProgram,raw,horizontal,1f/W,0,1,0);pass(blurProgram,horizontal.texture(),vertical,0,1f/H,0,0);
        pass(blurProgram,vertical.texture(),fogHorizontal,4f/W,0,0,3);pass(blurProgram,fogHorizontal.texture(),fogVertical,0,4f/H,0,3);
        currentGlow=fogVertical.texture();
    }
    static void step(double dt,double time,boolean reset) {
        float retention=reset?0:dev.c4lam.celeste.module.impl.visual.expansion.HandFogTiming.retention(dt,true);
        glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,historyRead.texture());
        pass(historyProgram,currentGlow,historyWrite,retention,
            retention==0?0:dev.c4lam.celeste.module.impl.visual.expansion.HandFogTiming.flameRise(dt,H),
            retention==0?0:dev.c4lam.celeste.module.impl.visual.expansion.HandFogTiming.flameSway(dt,W,H,time),
            retention==0?0:dev.c4lam.celeste.module.impl.visual.expansion.HandFogTiming.flameSpread(dt,H));
        Surface swap=historyRead;historyRead=historyWrite;historyWrite=swap;glow=historyRead.texture();
    }
    static double[] centroid(float[] pixels,int w,int h,boolean encoded,int minY) {
        double mass=0,x=0,y=0;for(int j=minY;j<h;j++)for(int i=0;i<w;i++){
            int k=(i+j*w)*4;double a=encoded?(pixels[k]*256+pixels[k+1])/257:pixels[k+3];
            mass+=a;x+=i*a;y+=j*a;
        }return new double[]{x/Math.max(mass,1e-12),y/Math.max(mass,1e-12),mass};
    }
    static void save(float[] pixels,Path path)throws Exception {
        BufferedImage image=new BufferedImage(W,H+28,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();
        g.setFont(new Font("SansSerif",Font.PLAIN,13));image(g,pixels,0,0,"Flame motion | synthetic GPU fixture");g.dispose();ImageIO.write(image,"png",path.toFile());
    }
    static double maxDifference(float[] a,float[] b){double result=0;for(int i=0;i<a.length;i++)result=Math.max(result,Math.abs(a[i]-b[i]));return result;}
    public static void main(String[] args)throws Exception {
        Path resources=Path.of(args[0]),baseline=Path.of(args[1]),output=Path.of(args[2]);Files.createDirectories(output.resolve("frames"));
        vertex=Files.readString(resources.resolve("hand_shaders.vsh"));GLFWErrorCallback callback=GLFWErrorCallback.createPrint(System.err);callback.set();
        check(glfwInit(),"GLFW");glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);
        glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);long window=glfwCreateWindow(W,H,"Celeste Flame GPU fixture",0,0);check(window!=0,"GL window");
        glfwMakeContextCurrent(window);GL.createCapabilities();glBindVertexArray(glGenVertexArrays());glDisable(GL_BLEND);glDisable(GL_DEPTH_TEST);glDepthMask(false);
        ubo=glGenBuffers();glBindBuffer(GL_UNIFORM_BUFFER,ubo);glBufferData(GL_UNIFORM_BUFFER,32,GL_DYNAMIC_DRAW);glBindBufferBase(GL_UNIFORM_BUFFER,0,ubo);
        int old=program(Files.readString(baseline.resolve("hand_shaders_composite.fsh")),"HandShaderData");
        int current=program(Files.readString(resources.resolve("hand_shaders_composite.fsh")),"HandShaderData");
        blurProgram=program(Files.readString(resources.resolve("hand_shaders_blur.fsh")),"BlurData");
        historyProgram=program(Files.readString(resources.resolve("hand_shaders_history.fsh")),"HistoryData");
        int oldHistory=program(Files.readString(baseline.resolve("hand_shaders_history.fsh")),"HistoryData");
        raw=texture(W,H,mask(0));horizontal=surface(W/2,H/2);vertical=surface(W/2,H/2);fogHorizontal=surface(W/4,H/4);fogVertical=surface(W/4,H/4);
        historyRead=historySurface();historyWrite=historySurface();Surface out=surface(W,H);frame=out.frame();target=out.texture();
        float[] blue={.03f,.66f,1};double minX=1e9,maxX=-1e9,minY=1e9,maxY=-1e9;int top=0;
        upload(mask(0));for(int n=0;n<90;n++){
            double seconds=3+n/30.0;step(1.0/30,seconds,n==0);float[] pixels=render(current,3,(float)seconds,blue);connected(pixels);
            coating(pixels);double[] center=centroid(pixels,W,H,false,468);
            minX=Math.min(minX,center[0]);maxX=Math.max(maxX,center[0]);minY=Math.min(minY,center[1]);maxY=Math.max(maxY,center[1]);
            for(int y=460;y<H;y++)for(int x=0;x<W;x++)if(pixels[(x+y*W)*4+3]>.1)top=Math.max(top,y);
            comparison(render(old,3,(float)seconds,blue),pixels,output.resolve("frames/frame-%03d.png".formatted(n)),"Refined Flame");
        }
        System.out.printf("Stationary plume: sideways centroid range %.3f px, vertical range %.3f px, extension %d px at540p%n",maxX-minX,maxY-minY,top-459);
        check(maxX-minX>5,"sideways motion is too small to see");check(top-459>=30,"missing upward plume");
        double coatMotion=0;float[] firstCoat=null;
        for(int n=0;n<60;n++){
            double seconds=7+n/30.0;upload(swingingMask(Math.sin(n*.22)*.45));step(1.0/30,seconds,n==0);
            float[] pixels=render(current,3,(float)seconds,blue);connected(pixels);
            comparison(render(old,3,(float)seconds,blue),pixels,output.resolve("frames/frame-%03d.png".formatted(90+n)),"Refined Flame: fast swing");
        }
        upload(mask(0));step(1.0/30,10,true);
        for(int n=0;n<30;n++){
            float[] pixels=render(current,3,10+n/30f,blue);if(n==0)firstCoat=pixels;
            for(int i=0;i<W*H;i++)if(capture[i*4+3]==1)coatMotion=Math.max(coatMotion,Math.abs(luma(pixels,i*4)-luma(firstCoat,i*4)));
        }
        check(coatMotion>.015,"surface highlights do not move while the hand is stationary");
        System.out.printf("Animated surface luminance range %.4f; fast swinging silhouette connected at every frame%n",coatMotion);
        // Move the actual captured silhouette, accumulate history and compare centers at matched times.
        StringBuilder measures=new StringBuilder();for(int rate:new int[]{30,60,144}){
            upload(mask(0));step(1.0/rate,0,true);
            for(int n=1;n<=rate;n++){double time=n/(double)rate;upload(mask((int)Math.round(90*time)));step(1.0/rate,time,false);}
            double[] now=centroid(read(fogVertical),W/4,H/4,false,0),delayed=centroid(read(historyRead),W/4,H/4,true,0);
            double lag=(now[0]-delayed[0])*4,rise=(delayed[1]-now[1])*4;
            System.out.printf("%d fps: moving hand lag %.3fpx; upward persistence %.3fpx%n",rate,lag,rise);
            check(lag>2&&lag<12,"hand aura does not lag or lags too far at "+rate);check(rise>1&&rise<10,"upward flow at "+rate);
            measures.append(rate).append(" fps: lag=").append(lag).append(" rise=").append(rise).append("\n");
        }
        upload(mask(0));step(1.0/60,2,true);
        for(int colorIndex=0;colorIndex<3;colorIndex++){
            float[] tint=switch(colorIndex){case 0->new float[]{46/255f,174/255f,214/255f};case 1->new float[]{116/255f,223/255f,1};default->new float[]{.9f,.25f,.1f};};
            float[] pixels=render(current,3,2.4f,tint);coating(pixels);connected(pixels);
            comparison(render(old,3,2.4f,tint),pixels,output.resolve("palette-%d.png".formatted(colorIndex)),"Refined: preset/saved tint");
        }
        // Smoke uses exactly the previous history formula and composite pixels with live feedback.
        Surface smokeRead=historySurface(),smokeWrite=historySurface(),oldRead=historySurface(),oldWrite=historySurface();
        for(int n=0;n<24;n++){
            upload(mask(n*2));float retention=n==0?0:dev.c4lam.celeste.module.impl.visual.expansion.HandFogTiming.retention(1.0/60);
            glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,oldRead.texture());pass(oldHistory,currentGlow,oldWrite,retention,0,0,0);
            glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,smokeRead.texture());pass(historyProgram,currentGlow,smokeWrite,retention,0,0,0);
            check(maxDifference(read(oldWrite),read(smokeWrite))==0,"Smoke history changed");
            glow=oldWrite.texture();float[] before=render(old,2,n/60f,blue);glow=smokeWrite.texture();float[] after=render(current,2,n/60f,blue);
            check(maxDifference(before,after)==0,"Smoke composite changed");
            Surface swap=smokeRead;smokeRead=smokeWrite;smokeWrite=swap;swap=oldRead;oldRead=oldWrite;oldWrite=swap;
        }
        upload(new float[W*H*4]);step(1.0/60,0,true);float[] empty=render(current,3,0,blue);boolean cleared=true;for(float value:empty)cleared&=value==0;check(cleared,"reset leaves a ghost");
        check(glGetError()==GL_NO_ERROR,"GL error");
        Files.writeString(output.resolve("result.txt"),"PASS: real blur + history + composite pipeline. Synthetic GPU fixture, not Minecraft.\nSideways="+(maxX-minX)+" px; top extension="+(top-459)+" px at540p\n"+measures+"Smoke feedback and composite unchanged. Surface coated with readable captured texture; opaque alpha exact. Fast swing connected. Reset clear.\n");
        System.out.println("PASS Hand Flame temporal GPU: "+checks+" checks; moving silhouette + connected plume + unchanged Smoke. Synthetic preview only.");
        glfwDestroyWindow(window);glfwTerminate();callback.free();
    }
}
