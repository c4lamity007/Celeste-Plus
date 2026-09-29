import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;
import dev.c4lam.celeste.module.impl.visual.expansion.AmbientField;
import dev.c4lam.celeste.module.impl.visual.expansion.AmbientTesseractField;
import dev.c4lam.celeste.module.impl.visual.expansion.AmbientTesseractGeometry;

/** Exact nearby particle mesh/material GPU fixture; not an in-game screenshot. */
public final class AmbientTesseractGpuCheck extends ImpactCheck {
    static int mesh,glow,indexBuffer;
    static final int W=AtmosphereCheck.W,H=AtmosphereCheck.H;
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    static List<AmbientField.Mote> samples(double time){
        double ex=2.5,ey=3,ez=2.5;var candidates=AmbientTesseractField.candidates(ex,ey,ez,time,.6,71);
        List<AmbientField.Mote> list=new ArrayList<>();
        for(int rank=0;rank<candidates.size();rank++){
            var m=candidates.get(rank).mote();double alpha=m.opacity()*AmbientTesseractField.capFade(rank)*AmbientTesseractField.visibility(m.x()-ex,m.y()-ey,m.z()-ez);
            if(alpha>=.01)list.add(new AmbientField.Mote(m.x()-ex,m.y()-ey,m.z()-ez,m.size(),m.yaw(),m.pitch(),alpha,m.color()));
        }
        list.sort(Comparator.comparingDouble(m->m.x()*m.x()+m.y()*m.y()+m.z()*m.z()));
        if(list.size()>AmbientTesseractField.MAX_PARTICLES)list.subList(AmbientTesseractField.MAX_PARTICLES,list.size()).clear();Collections.reverse(list);return list;
    }
    static void emit(FloatBuffer buffer,AmbientField.Mote m,float scale,double x,double y,double z,float u,float v,double nx,double ny,double nz,float a){
        // Match production's ARGB8 opacity quantization.
        AtmosphereCheck.vertex(buffer,x,y,z,u,v,AtmosphereCheck.color(m.color(),Math.round(m.opacity()*a*scale*255)/255.0),nx,ny,nz);
    }
    static float[] draw(List<AmbientField.Mote> particles,float scale,float depth,boolean background){
        AtmosphereCheck.clear(depth);if(background){glClearColor(.035f,.05f,.075f,1);glClear(GL_COLOR_BUFFER_BIT);}
        AtmosphereCheck.transforms(AtmosphereCheck.identity(),AtmosphereCheck.perspective(),new float[]{1,1,1,1},0);AtmosphereCheck.fog(18,40);
        glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);glDepthMask(false);glEnable(GL_CULL_FACE);glCullFace(GL_BACK);glEnable(GL_BLEND);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
        FloatBuffer faces=BufferUtils.createFloatBuffer(Math.max(1,particles.size())*AmbientTesseractGeometry.FILL_VERTICES*12),beams=BufferUtils.createFloatBuffer(Math.max(1,particles.size())*AmbientTesseractGeometry.BEAM_VERTICES*12);
        double[] scratch=new double[AmbientTesseractGeometry.SCRATCH_SIZE];
        for(var m:particles){AmbientTesseractGeometry.corners(scratch,m.x(),m.y(),m.z(),m.size(),m.yaw(),m.pitch());
            AmbientTesseractGeometry.fill(scratch,(x,y,z,u,v,nx,ny,nz,a)->emit(faces,m,scale,x,y,z,u,v,nx,ny,nz,a));
            AmbientTesseractGeometry.beams(scratch,0,0,0,m.size(),(x,y,z,u,v,nx,ny,nz,a)->emit(beams,m,scale,x,y,z,u,v,nx,ny,nz,a));}
        check(faces.position()/12<=AmbientTesseractField.MAX_PARTICLES*AmbientTesseractGeometry.FILL_VERTICES&&beams.position()/12<=AmbientTesseractField.MAX_PARTICLES*AmbientTesseractGeometry.BEAM_VERTICES,"bounded batched geometry");
        AtmosphereCheck.drawVertices(mesh,faces);glDisable(GL_CULL_FACE);glBlendFunc(GL_SRC_ALPHA,GL_ONE);
        beams.flip();int count=beams.remaining()/12;IntBuffer indices=BufferUtils.createIntBuffer(count/4*6);
        for(int base=0;base<count;base+=4)for(int i:new int[]{0,1,2,0,2,3})indices.put(base+i);indices.flip();
        glUseProgram(glow);glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);glBufferData(GL_ARRAY_BUFFER,beams,GL_STREAM_DRAW);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,indexBuffer);glBufferData(GL_ELEMENT_ARRAY_BUFFER,indices,GL_STREAM_DRAW);glDrawElements(GL_TRIANGLES,indices.remaining(),GL_UNSIGNED_INT,0);
        glDisable(GL_BLEND);AtmosphereCheck.depth(depth);check(glGetError()==GL_NO_ERROR,"particle GPU draw");float[] image=AtmosphereCheck.pixels();
        for(float value:image)check(Float.isFinite(value)&&value>=0&&value<64,"finite bounded particle shader: "+value);return image;
    }
    static void sheet(float[] detail,float[] field)throws Exception{
        int header=65,footer=30;BufferedImage image=new BufferedImage(W*2,H+header+footer,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();
        g.setColor(new Color(17,22,30));g.fillRect(0,0,image.getWidth(),image.getHeight());g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,18));g.drawString("AMBIENT TESSERACTS - actual particle geometry and shaders",14,25);
        g.setFont(new Font("SansSerif",Font.PLAIN,13));g.drawString("Nearby world particles; random lifetimes, straight drift, translucent faces and glowing edges. GPU fixture, not Minecraft.",14,48);
        g.drawImage(AtmosphereCheck.image(detail),0,header,null);g.drawImage(AtmosphereCheck.image(field),W,header,null);
        g.drawString("Matching corner links; normal size, close camera",14,H+header+20);g.drawString("Open air: density .6, 22-block sphere, cap192",W+14,H+header+20);g.dispose();ImageIO.write(image,"PNG",output.resolve("ambient-tesseracts-gpu-preview.png").toFile());
    }
    public static void main(String[] args)throws Exception{
        resources=Path.of(args[0]);Path staged=Path.of(args[1]);minecraft=new ZipFile(args[2]);output=Path.of(args[3]);Files.createDirectories(output);
        check(glfwInit(),"GLFW init");glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);long window=glfwCreateWindow(W,H,"Ambient tesseract hidden GPU QA",0,0);check(window!=0,"GPU context");glfwMakeContextCurrent(window);GL.createCapabilities();
        try{
            glBindVertexArray(glGenVertexArrays());vertexBuffer=glGenBuffers();indexBuffer=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,vertexBuffer);int[] sizes={3,2,4,3},offsets={0,3,5,9};for(int i=0;i<4;i++){glEnableVertexAttribArray(i);glVertexAttribPointer(i,sizes[i],GL_FLOAT,false,48,offsets[i]*4L);}
            AtmosphereCheck.dynamic=buffer(0,BufferUtils.createByteBuffer(160));AtmosphereCheck.projection=buffer(1,BufferUtils.createByteBuffer(64));AtmosphereCheck.fog=buffer(2,BufferUtils.createByteBuffer(48));frame=framebuffer(W,H,true);
            String vs=preprocess(Files.readString(resources.resolve("assets/celeste/shaders/core/ambient_mesh.vsh")));
            mesh=AtmosphereCheck.linkSource(vs,preprocess(Files.readString(staged.resolve("assets/celeste/shaders/core/ambient_mesh.fsh"))),"actual ambient mesh");glow=AtmosphereCheck.linkSource(vs,preprocess(Files.readString(staged.resolve("assets/celeste/shaders/core/ambient_glow.fsh"))),"actual ambient glow");
            int oldMesh=AtmosphereCheck.link("ambient_mesh.vsh","ambient_mesh.fsh"),oldGlow=AtmosphereCheck.link("ambient_mesh.vsh","ambient_glow.fsh");
            AtmosphereCheck.close(AtmosphereCheck.ambientQuad(oldMesh,.6f,-.3f,.63f,false),AtmosphereCheck.ambientQuad(mesh,.6f,-.3f,.63f,false),0,0,"Other ambient mesh unchanged");
            AtmosphereCheck.close(AtmosphereCheck.ambientQuad(oldGlow,.6f,-.3f,.63f,false),AtmosphereCheck.ambientQuad(glow,.6f,-.3f,.63f,false),0,0,"Other ambient glow unchanged");
            var field=samples(12);float[] visible=draw(field,1,1,false);int coverage=0;for(int i=0;i<visible.length;i+=4)if(visible[i]+visible[i+1]+visible[i+2]>.003)coverage++;check(coverage>40,"Default nearby field invisible");
            var shortStep=AtmosphereCheck.delta(visible,draw(samples(12+1.0/60),1,1,false));var longStep=AtmosphereCheck.delta(visible,draw(samples(12.5),1,1,false));check(shortStep.changed()>20&&longStep.mean()>shortStep.mean()*2,"Smooth frame motion/fade missing");
            for(float v:draw(field,0,1,false))check(v==0,"Zero opacity retains pixels");for(float v:draw(field,1,.5f,false))check(v==0,"Terrain does not block particles/glow");for(float v:draw(List.of(),1,1,false))check(v==0,"Empty batch stale geometry");
            var detail=List.of(new AmbientField.Mote(-.32,.05,-1.45,.12,.55,.3,1,0x48ACFF),new AmbientField.Mote(.27,-.05,-1.6,.12,-.35,.7,.8,0x48ACFF));sheet(draw(detail,1,1,true),draw(field,1,1,true));
            System.out.println("PASS Ambient tesseracts GPU: "+field.size()+" local field particles, "+coverage+" visible pixels; actual faces/32 corner-linked beams, sub-frame drift, disabled/empty clear, terrain occlusion, depth unchanged. GPU="+glGetString(GL_RENDERER));
        }finally{minecraft.close();glfwDestroyWindow(window);glfwTerminate();}
    }
}
