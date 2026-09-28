import dev.c4lam.celeste.module.impl.visual.expansion.AmbientField;
import dev.c4lam.celeste.module.impl.visual.expansion.AmbientTesseractField;
import dev.c4lam.celeste.module.impl.visual.expansion.AmbientTesseractGeometry;
import java.util.*;

public final class AmbientTesseractCheck {
    static int checks;
    static void require(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static double distance(AmbientField.Mote a,AmbientField.Mote b){return Math.sqrt(Math.pow(a.x()-b.x(),2)+Math.pow(a.y()-b.y(),2)+Math.pow(a.z()-b.z(),2));}
    public static void main(String[] args){
        Set<Double> sizes=new HashSet<>();int births=0;double minimumVisible=100,maximumVisible=0;
        for(int cell=-20;cell<=20;cell++){
            double span=0;var previous=AmbientTesseractField.sample(cell,1,-3,0,71);
            for(int i=1;i<=8400;i++){
                double time=i/100.0;var m=AmbientTesseractField.sample(cell,1,-3,time,71);
                require(m.equals(AmbientTesseractField.sample(cell,1,-3,time,71)),"sample moved with caller/camera");
                require(m.x()>cell*5&&m.x()<(cell+1)*5&&m.y()>5&&m.y()<10&&m.z()>-15&&m.z()<-10,"particle escaped bounded cell");
                require(m.size()>=.085&&m.size()<=.14&&m.opacity()>=0&&m.opacity()<=1,"particle size/life bound");
                require(Double.isFinite(m.yaw())&&Double.isFinite(m.pitch()),"nonfinite rotation");
                sizes.add(m.size());
                if(m.opacity()>.001)span+=.01;
                if(previous.opacity()>.001&&m.opacity()<=.001){minimumVisible=Math.min(minimumVisible,span);maximumVisible=Math.max(maximumVisible,span);span=0;}
                if(distance(m,previous)>.01){require(m.opacity()<.001&&previous.opacity()<.001,"respawn jumped while visible");births++;}
                else {require(Math.abs(m.opacity()-previous.opacity())<.015,"fade flashed");}
                previous=m;
            }
            for(int i=0;i<80;i++){
                double t=i+.123;var a=AmbientTesseractField.sample(cell,1,-3,t,71);var b=AmbientTesseractField.sample(cell,1,-3,t+.01,71);var c=AmbientTesseractField.sample(cell,1,-3,t+.02,71);
                if(a.opacity()>.1&&b.opacity()>.1&&c.opacity()>.1){
                    require(Math.abs(a.x()-2*b.x()+c.x())<1e-10&&Math.abs(a.y()-2*b.y()+c.y())<1e-10&&Math.abs(a.z()-2*b.z()+c.z())<1e-10,"position is circling/accelerating rather than subtle free drift");
                    require(distance(a,b)<=.0008,"drift too fast");
                }
            }
        }
        require(births>150&&sizes.size()>150,"positions/sizes do not reseed each life");
        require(maximumVisible-minimumVisible>3,"lifetimes not varied");
        var field=new AmbientTesseractField(42);var before=field.sample(1,2,3,3);field.reset();require(!before.equals(field.sample(1,2,3,3)),"reset retains old particles");
        require(AmbientTesseractField.sample(1,2,3,0,42).equals(AmbientTesseractField.sample(1,2,3,Double.NaN,42)),"invalid time fallback");
        double[] corners=new double[AmbientTesseractGeometry.SCRATCH_SIZE];AmbientTesseractGeometry.corners(corners,2,3,4,.12,.7,.4);
        for(int i=0;i<8;i++){
            double x=corners[i*3]-2,y=corners[i*3+1]-3,z=corners[i*3+2]-4;
            require(Math.abs(Math.sqrt(x*x+y*y+z*z)-.12*Math.sqrt(3))<1e-12,"outer cube rotation changes size");
            require(Math.abs((corners[(i+8)*3]-2)-x*.42)<1e-12,"nested cube corner link mismatch");
        }
        int[] fill={0},beams={0};
        AmbientTesseractGeometry.fill(corners,(x,y,z,u,v,nx,ny,nz,a)->{fill[0]++;require(a>0&&a<.25&&u==4,"face lacks translucent material");require((x-2)*nx+(y-3)*ny+(z-4)*nz>0,"cube face winding inward");});
        AmbientTesseractGeometry.beams(corners,0,0,0,.12,(x,y,z,u,v,nx,ny,nz,a)->{beams[0]++;require(Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z)&&u>=2&&u<=3&&a>0,"bad beam geometry");});
        require(fill[0]==36&&beams[0]==112,"geometry cap changed");
        System.out.println("PASS Ambient tesseracts: "+checks+" checks; "+births+" invisible respawns; random lives/positions, straight drift, reset, bounded cube faces and 28 beams");
    }
}
