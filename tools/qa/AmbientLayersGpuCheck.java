import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import dev.c4lam.celeste.module.impl.visual.expansion.*;

/** Mixed production geometry and GLSL, rendered offscreen. This is not Minecraft footage. */
public final class AmbientLayersGpuCheck extends ImpactCheck {
    static int mesh,glow,indexBuffer,checks;
    static final int W=AtmosphereCheck.W,H=AtmosphereCheck.H;
    record P(AmbientField.Style style,double x,double y,double z,double radius,double yaw,double pitch,float opacity,int color)
        implements AmbientEffectsGeometry.ParticleView { }
    static void check(boolean condition,String message) {checks++;if(!condition)throw new AssertionError(message);}
    static List<P> particles() {
        var styles=AmbientField.Style.values();List<P> result=new ArrayList<>();
        for(int i=0;i<5;i++)result.add(new P(styles[i],(i-2)*.58,.18,-3,.105,.57,.34,.86F,
            new int[]{0xA6FFE1,0x8EC8FF,0xE3F5FF,0xDCAD5E,0x48ACFF}[i]));
        return result;
    }
    static List<AmbientTronField.Segment> trails() {
        return List.of(new AmbientTronField.Segment(1,-1,-1,-3,0,-1,-3,.11,.6F,.6F,.5,2,-1,2),
            new AmbientTronField.Segment(1,0,-1,-3,0,0,-3,.11,.6F,.6F,.5,2,0,0),
            new AmbientTronField.Segment(1,0,0,-3,1,0,-3,.11,.6F,.6F,.5,2,2,-1));
    }
    static void quadBatch(FloatBuffer vertices) {
        vertices.flip();int count=vertices.remaining()/12;
        check(count%4==0,"Partial glow quad");
        IntBuffer indices=BufferUtils.createIntBuffer(count/4*6);
        for(int base=0;base<count;base+=4)for(int index:new int[]{0,1,2,0,2,3})indices.put(base+index);
        indices.flip();glUseProgram(glow);glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);glBufferData(GL_ARRAY_BUFFER,vertices,GL_STREAM_DRAW);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,indexBuffer);glBufferData(GL_ELEMENT_ARRAY_BUFFER,indices,GL_STREAM_DRAW);
        glDrawElements(GL_TRIANGLES,indices.remaining(),GL_UNSIGNED_INT,0);
    }
    static float[] draw(List<P> particles,List<AmbientTronField.Segment> trails,float scale,float depth,boolean background) {
        AtmosphereCheck.clear(depth);if(background){glClearColor(.035F,.05F,.075F,1);glClear(GL_COLOR_BUFFER_BIT);}
        AtmosphereCheck.transforms(AtmosphereCheck.identity(),AtmosphereCheck.perspective(),new float[]{1,1,1,1},0);AtmosphereCheck.fog(18,40);
        glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDepthMask(false);glEnable(GL_CULL_FACE);glCullFace(GL_BACK);
        glEnable(GL_BLEND);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
        FloatBuffer faces=BufferUtils.createFloatBuffer(Math.max(1,particles.size())*1024*12);
        FloatBuffer beams=BufferUtils.createFloatBuffer(Math.max(1,particles.size()+trails.size())*1024*12);
        double[] scratch=new double[AmbientEffectsGeometry.SCRATCH_SIZE];
        AmbientEffectsGeometry.Sink meshSink=(x,y,z,u,v,nx,ny,nz,alpha,color)->AtmosphereCheck.vertex(faces,x,y,z,u,v,
            AtmosphereCheck.color(color,Math.round(alpha*scale*255)/255.0),nx,ny,nz);
        AmbientEffectsGeometry.Sink glowSink=(x,y,z,u,v,nx,ny,nz,alpha,color)->AtmosphereCheck.vertex(beams,x,y,z,u,v,
            AtmosphereCheck.color(color,Math.round(alpha*scale*255)/255.0),nx,ny,nz);
        for(P p:particles)AmbientEffectsGeometry.mesh(p,p.style(),scratch,meshSink);
        AtmosphereCheck.drawVertices(mesh,faces);
        for(P p:particles)AmbientEffectsGeometry.glow(p,p.style(),scratch,0,0,0,1,0,0,0,1,0,glowSink);
        for(var trail:trails)AmbientTronGeometry.beam(trail,0,0,0,(x,y,z,u,v,alpha)->glowSink.vertex(x,y,z,u,v,0,0,0,alpha,0x57DDEB));
        glDisable(GL_CULL_FACE);glBlendFunc(GL_SRC_ALPHA,GL_ONE);quadBatch(beams);
        glDisable(GL_BLEND);AtmosphereCheck.depth(depth);check(glGetError()==GL_NO_ERROR,"Mixed effect GPU error");
        float[] image=AtmosphereCheck.pixels();for(float value:image)check(Float.isFinite(value)&&value>=0&&value<64,"Unbounded mixed output");
        return image;
    }
    static int visible(float[] image) {int count=0;for(int i=0;i<image.length;i+=4)if(image[i]+image[i+1]+image[i+2]>.003)count++;return count;}
    static void sheet(float[] all,float[] removed)throws Exception {
        int header=68,footer=34;BufferedImage image=new BufferedImage(W*2,H+header+footer,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();g.setColor(new Color(17,22,30));g.fillRect(0,0,image.getWidth(),image.getHeight());
        g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,18));g.drawString("AMBIENT EFFECTS / MIXED LAYERS",14,25);
        g.setFont(new Font("SansSerif",Font.PLAIN,13));g.drawString("Actual production geometry + shaders. Offscreen test preview, not Minecraft.",14,49);
        g.drawImage(AtmosphereCheck.image(all),0,header,null);g.drawImage(AtmosphereCheck.image(removed),W,header,null);
        g.drawString("All six styles share the same frame",14,H+header+21);g.drawString("Leaves and Tron removed; other layers remain",W+14,H+header+21);
        g.dispose();ImageIO.write(image,"PNG",output.resolve("ambient-layers-gpu-preview.png").toFile());
    }
    public static void main(String[] args)throws Exception {
        resources=Path.of(args[0]);minecraft=new ZipFile(args[1]);output=Path.of(args[2]);Files.createDirectories(output);
        check(glfwInit(),"GLFW init");glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);
        glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);long window=glfwCreateWindow(W,H,"Ambient layers hidden GPU QA",0,0);check(window!=0,"GPU context");
        glfwMakeContextCurrent(window);GL.createCapabilities();
        try {
            glBindVertexArray(glGenVertexArrays());vertexBuffer=glGenBuffers();indexBuffer=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);
            int[] sizes={3,2,4,3},offsets={0,3,5,9};for(int i=0;i<4;i++){glEnableVertexAttribArray(i);glVertexAttribPointer(i,sizes[i],GL_FLOAT,false,48,offsets[i]*4L);}
            AtmosphereCheck.dynamic=buffer(0,BufferUtils.createByteBuffer(160));AtmosphereCheck.projection=buffer(1,BufferUtils.createByteBuffer(64));AtmosphereCheck.fog=buffer(2,BufferUtils.createByteBuffer(48));frame=framebuffer(W,H,true);
            mesh=AtmosphereCheck.link("ambient_mesh.vsh","ambient_mesh.fsh");glow=AtmosphereCheck.link("ambient_mesh.vsh","ambient_glow.fsh");
            var particles=particles();var trails=trails();float[] combined=draw(particles,trails,1,1,false);
            check(visible(combined)>150,"Mixed frame invisible");
            for(P p:particles) {
                check(visible(draw(List.of(p),List.of(),1,1,false))>3,"Single style invisible: "+p.style());
                var without=particles.stream().filter(other->other!=p).toList();
                check(AtmosphereCheck.delta(combined,draw(without,trails,1,1,false)).changed()>3,"Style absent from mixed frame: "+p.style());
            }
            check(AtmosphereCheck.delta(combined,draw(particles,List.of(),1,1,false)).changed()>3,"Tron absent from mixed frame");
            for(float value:draw(particles,trails,0,1,false))check(value==0,"Zero alpha left effect pixels");
            for(float value:draw(particles,trails,1,.5F,false))check(value==0,"Terrain depth did not hide mixed layers");
            for(float value:draw(List.of(),List.of(),1,1,false))check(value==0,"Empty frame left stale geometry");
            for(P p:particles)if(p.style()==AmbientField.Style.SNOW||p.style()==AmbientField.Style.LEAVES) {
                int[] vertices={0};AmbientEffectsGeometry.glow(p,p.style(),new double[AmbientEffectsGeometry.SCRATCH_SIZE],0,0,0,1,0,0,0,1,0,(x,y,z,u,v,nx,ny,nz,a,c)->vertices[0]++);
                check(vertices[0]==0,"Non-glowing style acquired bloom");
            }
            sheet(draw(particles,trails,1,1,true),draw(particles.stream().filter(p->p.style()!=AmbientField.Style.LEAVES).toList(),List.of(),1,1,true));
            System.out.println("PASS Ambient layers GPU: "+checks+" checks; all six styles visible in one frame, independent removal, empty/zero clear, terrain occlusion, unchanged depth and shared production geometry. GPU="+glGetString(GL_RENDERER));
        } finally {minecraft.close();glfwDestroyWindow(window);glfwTerminate();}
    }
}
