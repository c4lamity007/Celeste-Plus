package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Flat seam ribbons shared by the world renderer and GPU checks. Caps never extend past the grid endpoint. */
public final class HitGroundGeometry {
    @FunctionalInterface public interface Sink {
        void vertex(double x,double y,double z,float u,float v,double opacity,int color);
    }
    private static final class Joint {
        HitEffectsState.Point first,second;
        int count;
        void add(HitEffectsState.Point neighbor) {
            if(count==0)first=neighbor;
            else if(count==1)second=neighbor;
            count++;
        }
    }
    private record Side(double x,double z) { }
    private HitGroundGeometry() { }

    public static void emit(List<HitEffectsState.Beam> beams,Sink out) {
        if(beams==null || beams.isEmpty() || out==null)return;
        Map<HitEffectsState.Point,Joint> joints=new HashMap<>();
        for(var beam:beams)if(valid(beam)) {
            joints.computeIfAbsent(beam.from(),p->new Joint()).add(beam.to());
            joints.computeIfAbsent(beam.to(),p->new Joint()).add(beam.from());
        }
        for(var beam:beams)if(valid(beam)) {
            Joint start=joints.get(beam.from()),end=joints.get(beam.to());
            double glow=Math.max(0,Math.min(1,beam.highlight()));
            double endGlow=Math.max(0,Math.min(1,beam.endHighlight()));
            double width=beam.width()*(1+glow*.65),endWidth=beam.width()*(1+endGlow*.65);
            Side a=side(beam.from(),beam.to(),start,width);
            Side back=side(beam.to(),beam.from(),end,endWidth);
            Side b=new Side(-back.x,-back.z);
            Side normal=new Side(-(beam.to().z()-beam.from().z())*width,(beam.to().x()-beam.from().x())*width);
            Side endNormal=new Side(-(beam.to().z()-beam.from().z())*endWidth,(beam.to().x()-beam.from().x())*endWidth);
            double cap=Math.min(.20,Math.max(width,endWidth)*2.5);
            int color=HitEffectsState.gradient(beam.color(),0xFFFFFF,glow*.66);
            int endColor=HitEffectsState.gradient(beam.endColor(),0xFFFFFF,endGlow*.66);
            double first=start.count==1?cap:0,last=end.count==1?1-cap:1;
            if(start.count==1) {
                section(beam,0,cap*.25,normal,mix(normal,endNormal,cap*.25),0,.57,0,.50,color,endColor,out);
                section(beam,cap*.25,cap*.65,mix(normal,endNormal,cap*.25),mix(normal,endNormal,cap*.65),.57,.94,.50,.92,color,endColor,out);
                section(beam,cap*.65,cap,mix(normal,endNormal,cap*.65),mix(normal,endNormal,cap),.94,1,.92,1,color,endColor,out);
                a=mix(normal,endNormal,cap);
            }
            if(end.count==1)b=mix(normal,endNormal,1-cap);
            section(beam,first,last,a,b,1,1,1,1,color,endColor,out);
            if(end.count==1) {
                section(beam,1-cap,1-cap*.65,mix(normal,endNormal,1-cap),mix(normal,endNormal,1-cap*.65),1,.94,1,.92,color,endColor,out);
                section(beam,1-cap*.65,1-cap*.25,mix(normal,endNormal,1-cap*.65),mix(normal,endNormal,1-cap*.25),.94,.57,.92,.50,color,endColor,out);
                section(beam,1-cap*.25,1,mix(normal,endNormal,1-cap*.25),endNormal,.57,0,.50,0,color,endColor,out);
            }
        }
    }

    private static boolean valid(HitEffectsState.Beam beam) {
        if(beam==null||beam.from()==null||beam.to()==null||!beam.from().finite()||!beam.to().finite()
            ||!Double.isFinite(beam.width())||beam.width()<=0||!Double.isFinite(beam.opacity())
            ||!Double.isFinite(beam.highlight())||!Double.isFinite(beam.endHighlight())||beam.opacity()<=0||beam.from().y()!=beam.to().y())return false;
        double dx=Math.abs(beam.to().x()-beam.from().x()),dz=Math.abs(beam.to().z()-beam.from().z());
        return dx==1 && dz==0 || dx==0 && dz==1;
    }
    private static Side mix(Side a,Side b,double t) {return new Side(a.x+(b.x-a.x)*t,a.z+(b.z-a.z)*t);}
    private static Side side(HitEffectsState.Point node,HitEffectsState.Point neighbor,Joint joint,double width) {
        double nx=-(neighbor.z()-node.z()),nz=neighbor.x()-node.x();
        if(joint.count!=2)return new Side(nx*width,nz*width);
        HitEffectsState.Point other=joint.first.equals(neighbor)?joint.second:joint.first;
        double ox=-(other.z()-node.z()),oz=other.x()-node.x();
        double divisor=1-nx*ox-nz*oz;
        if(divisor<.5)return new Side(nx*width,nz*width);
        return new Side((nx-ox)*width/divisor,(nz-oz)*width/divisor);
    }
    private static void section(HitEffectsState.Beam beam,double from,double to,Side a,Side b,
                                double widthA,double widthB,double alphaA,double alphaB,
                                int color,int endColor,Sink out) {
        var p=beam.from().lerp(beam.to(),from);var q=beam.from().lerp(beam.to(),to);
        int ca=HitEffectsState.gradient(color,endColor,from),cb=HitEffectsState.gradient(color,endColor,to);
        double aa=beam.opacity()*alphaA,ab=beam.opacity()*alphaB;
        // Split on the centerline: a varying-width quad otherwise bends interpolated UV=.5 into a zigzag.
        out.vertex(p.x()-a.x*widthA,p.y(),p.z()-a.z*widthA,6,(float)from,aa,ca);
        out.vertex(p.x(),p.y(),p.z(),6.5f,(float)from,aa,ca);
        out.vertex(q.x(),q.y(),q.z(),6.5f,(float)to,ab,cb);
        out.vertex(q.x()-b.x*widthB,q.y(),q.z()-b.z*widthB,6,(float)to,ab,cb);
        out.vertex(p.x(),p.y(),p.z(),6.5f,(float)from,aa,ca);
        out.vertex(p.x()+a.x*widthA,p.y(),p.z()+a.z*widthA,7,(float)from,aa,ca);
        out.vertex(q.x()+b.x*widthB,q.y(),q.z()+b.z*widthB,7,(float)to,ab,cb);
        out.vertex(q.x(),q.y(),q.z(),6.5f,(float)to,ab,cb);
    }
}
