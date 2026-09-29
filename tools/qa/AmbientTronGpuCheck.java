import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.*;
import java.nio.file.*;
import java.util.List;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import dev.c4lam.celeste.module.impl.visual.expansion.AmbientTronField;
import dev.c4lam.celeste.module.impl.visual.expansion.AmbientTronGeometry;

/** Production beam geometry, fog and glow material. This fixture is not a Minecraft screenshot. */
public final class AmbientTronGpuCheck extends ImpactCheck {
    static final int W=AtmosphereCheck.W,H=AtmosphereCheck.H;
    static int glow,indices,checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static List<AmbientTronField.Segment> field(double time){var field=new AmbientTronField(38);field.sample(0,0,0,0,1,1,(x,y,z)->true);return field.sample(0,0,0,.8+time,1,1,(x,y,z)->true);}
    static float[] draw(List<AmbientTronField.Segment> lines,float opacity,float depth,float fogEnd,boolean background){
        AtmosphereCheck.clear(depth);
        if(background){glClearColor(.018f,.026f,.044f,1);glClear(GL_COLOR_BUFFER_BIT);}
        AtmosphereCheck.transforms(AtmosphereCheck.identity(),AtmosphereCheck.perspective(),new float[]{1,1,1,1},0);
        AtmosphereCheck.fog(fogEnd*.5f,fogEnd);
        glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDepthMask(false);glDisable(GL_CULL_FACE);glEnable(GL_BLEND);glBlendFunc(GL_SRC_ALPHA,GL_ONE);
        FloatBuffer vertices=BufferUtils.createFloatBuffer(Math.max(1,lines.size())*AmbientTronGeometry.MAX_VERTICES*12);
        for(var line:lines)AmbientTronGeometry.beam(line,0,0,0,(x,y,z,u,v,a)->AtmosphereCheck.vertex(vertices,x,y,z,u,v,
            AtmosphereCheck.color(0x57DDEB,Math.round(a*opacity*255)/255.0),0,0,0));
        vertices.flip();int count=vertices.remaining()/12;
        check(count<=AmbientTronField.MAX_TRAILS*AmbientTronField.MAX_SEGMENTS_PER_TRAIL*AmbientTronGeometry.MAX_VERTICES,"bounded production vertices");
        IntBuffer elements=BufferUtils.createIntBuffer(Math.max(1,count/4*6));
        for(int base=0;base<count;base+=4)for(int i:new int[]{0,1,2,0,2,3})elements.put(base+i);elements.flip();
        glUseProgram(glow);glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);glBufferData(GL_ARRAY_BUFFER,vertices,GL_STREAM_DRAW);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,indices);glBufferData(GL_ELEMENT_ARRAY_BUFFER,elements,GL_STREAM_DRAW);
        glDrawElements(GL_TRIANGLES,elements.remaining(),GL_UNSIGNED_INT,0);glDisable(GL_BLEND);
        AtmosphereCheck.depth(depth);check(glGetError()==GL_NO_ERROR,"Tron GPU draw");
        float[] image=AtmosphereCheck.pixels();
        for(float value:image)check(Float.isFinite(value)&&value>=0&&value<64,"finite beam shader output");
        return image;
    }
    static void sheet(float[] first,float[] second)throws Exception{
        int header=62,footer=28;
        BufferedImage image=new BufferedImage(W*2,H+header+footer,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();
        g.setColor(new Color(17,22,30));g.fillRect(0,0,image.getWidth(),image.getHeight());g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,18));
        g.drawString("AMBIENT TRON TRAILS - production geometry and glow shader",14,25);g.setFont(new Font("SansSerif",Font.PLAIN,13));
        g.drawString("Integer XYZ edges, flat XY/XZ/YZ ribbons, 90-degree turns and fading tails. GPU fixture, not Minecraft.",14,47);
        g.drawImage(AtmosphereCheck.image(first),0,header,null);g.drawImage(AtmosphereCheck.image(second),W,header,null);
        g.drawString("Same world-anchored field: t = 0.0s",14,H+header+18);g.drawString("t = 0.5s",W+14,H+header+18);g.dispose();
        ImageIO.write(image,"PNG",output.resolve("ambient-tron-gpu-preview.png").toFile());
    }
    public static void main(String[] args)throws Exception{
        resources=Path.of(args[0]);minecraft=new ZipFile(args[1]);output=Path.of(args[2]);Files.createDirectories(output);
        check(glfwInit(),"GLFW init");glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        long window=glfwCreateWindow(W,H,"Ambient Tron hidden GPU check",0,0);check(window!=0,"GPU context");glfwMakeContextCurrent(window);GL.createCapabilities();
        try{
            glBindVertexArray(glGenVertexArrays());vertexBuffer=glGenBuffers();indices=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);
            int[] sizes={3,2,4,3},offsets={0,3,5,9};for(int i=0;i<4;i++){glEnableVertexAttribArray(i);glVertexAttribPointer(i,sizes[i],GL_FLOAT,false,48,offsets[i]*4L);}
            AtmosphereCheck.dynamic=buffer(0,BufferUtils.createByteBuffer(160));AtmosphereCheck.projection=buffer(1,BufferUtils.createByteBuffer(64));AtmosphereCheck.fog=buffer(2,BufferUtils.createByteBuffer(48));frame=framebuffer(W,H,true);
            glow=AtmosphereCheck.link("ambient_mesh.vsh","ambient_glow.fsh");
            var initial=field(0);float[] visible=draw(initial,1,1,40,false);int coverage=0;
            for(int i=0;i<visible.length;i+=4)if(visible[i]+visible[i+1]+visible[i+2]>.003)coverage++;
            check(coverage>100,"ambient trails visible");
            var small=AtmosphereCheck.delta(visible,draw(field(1.0/60),1,1,40,false));
            var large=AtmosphereCheck.delta(visible,draw(field(.5),1,1,40,false));
            check(small.changed()>20 && large.mean()>small.mean()*2,"smooth sub-frame head and tail movement");
            for(float v:draw(initial,0,1,40,false))check(v==0,"zero alpha leaves scene unchanged");
            for(float v:draw(initial,1,.5f,40,false))check(v==0,"terrain occludes both core and bloom");
            for(float v:draw(List.of(),1,1,40,false))check(v==0,"empty frame has no stale lines");
            // Explicit nearby beam: full fog removes it, wide glow remains distinct from its bright core.
            var detail=List.of(new AmbientTronField.Segment(1,-1,0,-3,1,0,-3,.18,.8f,.8f,.5,2,-1,-1));
            for(float v:draw(detail,1,1,1,false))check(v==0,"native fog hides distant trail");
            float[] line=draw(detail,1,1,40,false);float peak=0;int halo=0;
            for(int i=0;i<line.length;i+=4)peak=Math.max(peak,line[i+1]);
            for(int i=0;i<line.length;i+=4)if(line[i+1]>.008 && line[i+1]<peak*.5)halo++;
            check(peak>.2 && halo>20,"bright core and soft surrounding glow both visible");
            double[] columns=new double[W];double brightest=0;
            for(int y=0;y<H;y++)for(int x=0;x<W;x++)columns[x]+=line[(y*W+x)*4+1];
            for(double value:columns)brightest=Math.max(brightest,value);
            int leftSoft=0,rightSoft=0;
            for(int x=0;x<W;x++)if(columns[x]>brightest*.02&&columns[x]<brightest*.8){if(x<W/2)leftSoft++;else rightSoft++;}
            check(leftSoft>=4&&rightSoft>=4,"exposed endpoints need visible soft longitudinal falloff");
            double maxCenterError=0;int measured=0;
            for(int x=0;x<W;x++)if(columns[x]>brightest*.03){double weighted=0;
                for(int y=0;y<H;y++)weighted+=(y+.5)*line[(y*W+x)*4+1];
                maxCenterError=Math.max(maxCenterError,Math.abs(weighted/columns[x]-H*.5));measured++;
            }
            System.out.println("Tip center: columns="+measured+", maximum centroid error="+maxCenterError+" pixels");
            ImageIO.write(AtmosphereCheck.image(line),"PNG",output.resolve("ambient-tip-detail.png").toFile());
            // Derivative AA can shift a subpixel tip by roughly one pixel; the old single-quad
            // taper deviates by ~4 pixels at this fixed resolution and fails this bound.
            check(measured>30&&maxCenterError<1.25,"pointed moving tip must keep its light centered, without diagonal UV kink");
            var fadeField=new AmbientTronField(38);
            for(float v:draw(fadeField.sample(0,0,0,0,1,1,(x,y,z)->true),1,1,40,false))check(v==0,"enable fade starts without a brightness flash");
            double dim=0,bright=0;
            for(float v:draw(fadeField.sample(0,0,0,.1,1,1,(x,y,z)->true),1,1,40,false))dim+=v;
            for(float v:draw(fadeField.sample(0,0,0,.65,1,1,(x,y,z)->true),1,1,40,false))bright+=v;
            check(dim>0&&bright>dim*2,"production material shows the enable fade-in animation");
            sheet(draw(initial,1,1,40,true),draw(field(.5),1,1,40,true));
            System.out.println("PASS ambient-tron GPU: "+checks+" checks, "+coverage+" visible pixels; core/glow, sub-frame motion, fog, occlusion, depth preservation and clear. GPU="+glGetString(GL_RENDERER));
        }finally{minecraft.close();glfwDestroyWindow(window);glfwTerminate();}
    }
}
