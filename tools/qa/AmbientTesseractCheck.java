import dev.c4lam.celeste.module.impl.visual.expansion.AmbientField;
import dev.c4lam.celeste.module.impl.visual.expansion.AmbientTesseractField;
import dev.c4lam.celeste.module.impl.visual.expansion.AmbientTesseractGeometry;
import java.util.*;

public final class AmbientTesseractCheck {
    record Life(int x,int y,int z,double radius) { }
    static int checks;
    static void require(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static double distance(AmbientField.Mote a,AmbientField.Mote b){return Math.sqrt(Math.pow(a.x()-b.x(),2)+Math.pow(a.y()-b.y(),2)+Math.pow(a.z()-b.z(),2));}
    public static void main(String[] args){
        Set<Double> sizes=new HashSet<>();Set<Long> spins=new HashSet<>(),speeds=new HashSet<>();Set<Integer> spinDirections=new HashSet<>(),driftDirections=new HashSet<>();int births=0;double minimumVisible=100,maximumVisible=0;
        for(int cell=-20;cell<=20;cell++){
            double span=0;var previous=AmbientTesseractField.sample(cell,1,-3,0,71);
            for(int i=1;i<=8400;i++){
                double time=i/100.0;var m=AmbientTesseractField.sample(cell,1,-3,time,71);
                require(m.equals(AmbientTesseractField.sample(cell,1,-3,time,71)),"sample moved with caller/camera");
                double width=AmbientTesseractField.CELL,travel=AmbientTesseractField.MAX_SPEED*6.5;
                require(m.x()>cell*width-travel&&m.x()<(cell+1)*width+travel&&m.y()>width-travel&&m.y()<2*width+travel&&m.z()>-3*width-travel&&m.z()<-2*width+travel,"particle escaped bounded lifetime drift");
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
                    require(Math.abs(a.x()-2*b.x()+c.x())<1e-10&&Math.abs(a.y()-2*b.y()+c.y())<1e-10&&Math.abs(a.z()-2*b.z()+c.z())<1e-10,"position is circling/accelerating rather than straight drift");
                    double speed=distance(a,b)/.01;
                    require(speed>=.139999&&speed<=.380001,"drift is imperceptible or too fast");
                    speeds.add(Math.round(speed*100000));driftDirections.add((b.x()>a.x()?1:0)|(b.y()>a.y()?2:0)|(b.z()>a.z()?4:0));
                    double yaw=(b.yaw()-a.yaw())/.01,pitch=(b.pitch()-a.pitch())/.01;
                    require(Math.abs(yaw)>=.239999&&Math.abs(yaw)<=.480001,"yaw is imperceptible or too fast");
                    require(Math.abs(pitch)>=.139999&&Math.abs(pitch)<=.300001,"pitch is imperceptible or too fast");
                    require(Math.abs(a.yaw()-2*b.yaw()+c.yaw())<1e-10&&Math.abs(a.pitch()-2*b.pitch()+c.pitch())<1e-10,"rotation jitters within one life");
                    spins.add(Math.round(yaw*100000));spinDirections.add((yaw>0?1:0)|(pitch>0?2:0));
                }
            }
        }
        require(births>150&&sizes.size()>150,"positions/sizes do not reseed each life");
        require(spins.size()>100&&spinDirections.size()==4,"cubes rotate in sync instead of independent directions/speeds");
        require(speeds.size()>100&&driftDirections.size()==8,"drift lacks random speeds/up/down/sideways directions");
        int rotatingMeshes=0;
        for(int cell=-20;cell<=20;cell++)for(int t=1;t<84;t++){
            var a=AmbientTesseractField.sample(cell,1,-3,t,71);var b=AmbientTesseractField.sample(cell,1,-3,t+.4,71);
            if(a.opacity()<.8||b.opacity()<.8||distance(a,b)>.153)continue;
            double[] first=new double[48],later=new double[48];
            // Remove center drift: this checks movement of the actual corners from rotation alone.
            AmbientTesseractGeometry.corners(first,0,0,0,a.size(),a.yaw(),a.pitch());
            AmbientTesseractGeometry.corners(later,0,0,0,b.size(),b.yaw(),b.pitch());
            double motion=0;for(int i=0;i<24;i++)motion=Math.max(motion,Math.abs(first[i]-later[i]));
            require(motion>a.size()*.04,"tesseract mesh rotation is not visible");rotatingMeshes++;
        }
        require(rotatingMeshes>1000,"not enough visible rotation samples");
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
        List<double[]> emitted=new ArrayList<>();
        AmbientTesseractGeometry.beams(corners,0,0,0,.12,(x,y,z,u,v,nx,ny,nz,a)->{beams[0]++;emitted.add(new double[]{x,y,z});require(Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z)&&u>=2&&u<=3&&a>0,"bad beam geometry");});
        require(fill[0]==36&&beams[0]==128,"geometry cap changed");
        for(int beam=0;beam<32;beam++){
            double[] a=new double[3],b=new double[3];for(int axis=0;axis<3;axis++){a[axis]=(emitted.get(beam*4)[axis]+emitted.get(beam*4+1)[axis])*.5;b[axis]=(emitted.get(beam*4+2)[axis]+emitted.get(beam*4+3)[axis])*.5;}
            if(beam>=24)for(int axis=0;axis<3;axis++){
                require(Math.abs(a[axis]-corners[(beam-24)*3+axis])<1e-12,"corner link not anchored to outer cube");
                require(Math.abs(b[axis]-corners[(beam-24+8)*3+axis])<1e-12,"corner link not anchored to matching inner corner");
            }
            double dx=b[0]-a[0],dy=b[1]-a[1],dz=b[2]-a[2];double t=Math.max(0,Math.min(1,-((a[0]-2)*dx+(a[1]-3)*dy+(a[2]-4)*dz)/(dx*dx+dy*dy+dz*dz)));
            require(Math.sqrt(Math.pow(a[0]+dx*t-2,2)+Math.pow(a[1]+dy*t-3,2)+Math.pow(a[2]+dz*t-4,2))>.12*.4,"beam crosses inner center");
        }
        spaceAndBounds();
        capAndCamera();
        System.out.println("PASS Ambient tesseracts: "+checks+" checks; "+births+" invisible respawns; random lives/3D drift, rotation, reset, air clearance, 22-block sphere, 192 cap, faces and 32 corner-linked beams");
    }
    static void spaceAndBounds(){
        double radius=.14,bound=AmbientTesseractField.bounds(radius);
        int[] reads={0};double air=AmbientTesseractField.airVisibility(.5,.5,.5,radius,(x,y,z)->{reads[0]++;return true;});
        require(air==1&&reads[0]<=27,"air query bound");
        require(AmbientTesseractField.airVisibility(.5,-.5,.5,radius,(x,y,z)->y>=0)==0,"spawn inside ground");
        require(AmbientTesseractField.airVisibility(.5,bound-.001,.5,radius,(x,y,z)->y>=0)==0,"rotated/glow bounds enter ground");
        double previous=0;for(int i=0;i<=350;i++){
            double alpha=AmbientTesseractField.airVisibility(.5,bound+i*.001,.5,radius,(x,y,z)->y>=0);
            require(alpha+1e-12>=previous&&alpha-previous<.005,"air fade flashes");previous=alpha;
        }
        require(previous>.9999,"air fades never reach full opacity");
        require(AmbientTesseractField.airVisibility(.5,.5,.5,radius,(x,y,z)->y<2)==1,"air under overhang rejected");
        require(AmbientTesseractField.airVisibility(.5,1.9,.5,radius,(x,y,z)->y<2)==0,"bounds penetrate overhead block");
        require(AmbientTesseractField.airVisibility(15.9,.5,.5,radius,(x,y,z)->x<16)==0,"particle enters unloaded neighboring chunk");
        require(AmbientTesseractField.airVisibility(.5,.5,.5,radius,(x,y,z)->false)==0,"blocked/fluid cell accepted");
        for(int i=0;i<100;i++){
            double[] p=new double[48];double r=.085+i/99.0*.195;AmbientTesseractGeometry.corners(p,0,0,0,r,i*.37,i*.53);
            AmbientTesseractGeometry.beams(p,2,-3,4,r,(x,y,z,u,v,nx,ny,nz,a)->require(Math.max(Math.abs(x),Math.max(Math.abs(y),Math.abs(z)))<=AmbientTesseractField.bounds(r),"glow exceeds air envelope"));
        }
        double ex=2.5,ey=3,ez=2.5;var candidates=AmbientTesseractField.candidates(ex,ey,ez,12,.6,71);List<AmbientField.Mote> field=new ArrayList<>();
        for(int rank=0;rank<candidates.size();rank++)if(opacity(candidates.get(rank),rank,ex,ey,ez)>=.01)field.add(candidates.get(rank).mote());
        require(field.size()>64,"default population not increased");
        require(candidates.size()<=192,"potential population exceeds cap");long above=field.stream().filter(m->m.y()-ey>10).count(),below=field.stream().filter(m->m.y()-ey< -10).count();
        require(above>8&&below>8,"cap/visibility loses above or below population");
        for(double axis:new double[]{-20,-10,10,20})require(AmbientTesseractField.visibility(0,axis,0)==AmbientTesseractField.visibility(axis,0,0)&&AmbientTesseractField.visibility(0,axis,0)>0,"vertical/horizontal range differs");
        require(AmbientTesseractField.visibility(0,22,0)==0&&AmbientTesseractField.visibility(Double.NaN,0,0)==0,"far/nonfinite visibility");
        // The next cell shell cannot drift into range, even from its closest spawn bound.
        require((AmbientTesseractField.CELL_RADIUS+.18)*AmbientTesseractField.CELL-AmbientTesseractField.MAX_SPEED*6.5>AmbientTesseractField.RANGE,"candidate radius misses source cells");
        System.out.println("Default open-air field: "+field.size()+" particles; above10="+above+", below10="+below);
    }
    static double opacity(AmbientTesseractField.Candidate c,int rank,double ex,double ey,double ez){var m=c.mote();return m.opacity()*AmbientTesseractField.capFade(rank)*AmbientTesseractField.visibility(m.x()-ex,m.y()-ey,m.z()-ez);}
    static Life life(AmbientTesseractField.Candidate c){return new Life(c.cellX(),c.cellY(),c.cellZ(),c.mote().size());}
    static Map<Life,Double> selected(double seconds){var list=AmbientTesseractField.candidates(2.5,3,2.5,seconds,1,71);Map<Life,Double> result=new HashMap<>();require(list.size()<=192,"potential cap overflow");for(int rank=0;rank<list.size();rank++)result.put(life(list.get(rank)),opacity(list.get(rank),rank,2.5,3,2.5));return result;}
    static void capAndCamera(){
        var potential=AmbientTesseractField.candidates(2.5,3,2.5,12,1,71);
        require(potential.size()==192,"cap fixture not saturated");
        require(potential.stream().anyMatch(c->c.mote().opacity()==0),"invisible births do not reserve their slots");
        Map<Life,Double> previousView=new HashMap<>();int intersections=0;
        for(int angle=0;angle<=360;angle+=5){
            double a=Math.toRadians(angle);Map<Life,Double> view=new HashMap<>();
            var same=AmbientTesseractField.candidates(2.5,3,2.5,12,1,71);require(same.equals(potential),"selection follows camera direction");
            for(int rank=0;rank<same.size();rank++){var c=same.get(rank);var m=c.mote();if((m.x()-2.5)*Math.cos(a)+(m.z()-2.5)*Math.sin(a)>0)view.put(life(c),opacity(c,rank,2.5,3,2.5));}
            for(var entry:view.entrySet())if(previousView.containsKey(entry.getKey())){require(entry.getValue().equals(previousView.get(entry.getKey())),"camera turn replaced/dimmed an unrelated visible particle");intersections++;}
            previousView=view;
        }
        require(intersections>1000,"camera rotation fixture coverage");
        Map<Life,Double> previous=selected(12);int admitted=0,retired=0;
        for(int frame=1;frame<=600;frame++){
            Map<Life,Double> current=selected(12+frame/60.0);
            for(var entry:previous.entrySet())if(!current.containsKey(entry.getKey())){require(entry.getValue()<.08,"new birth evicted a full-opacity capped particle");retired++;}
            for(var entry:current.entrySet())if(!previous.containsKey(entry.getKey())){require(entry.getValue()<.08,"new capped particle appeared at full opacity");admitted++;}
            previous=current;
        }
        require(admitted>5&&retired>5,"birth/range cap transitions not exercised");
        require(AmbientTesseractField.capFade(191)==0&&AmbientTesseractField.capFade(159)==1,"cap edge lacks smooth fade");
        System.out.println("Cap/camera: "+intersections+" unchanged overlapping view samples; "+admitted+" admitted / "+retired+" retired without full-opacity replacement");
    }
}
