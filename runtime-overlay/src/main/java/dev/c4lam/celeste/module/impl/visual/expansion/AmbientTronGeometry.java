package dev.c4lam.celeste.module.impl.visual.expansion;

/** World-fixed flat ribbon. Exact miter joints join orthogonal edges without tubes or billboarding. */
public final class AmbientTronGeometry {
    @FunctionalInterface public interface Vertex { void emit(double x, double y, double z, float u, float v, float opacity); }
    private AmbientTronGeometry() { }
    public static void beam(AmbientTronField.Segment s, double eyeX, double eyeY, double eyeZ, Vertex sink) {
        double dx=s.bx()-s.ax(),dy=s.by()-s.ay(),dz=s.bz()-s.az();
        double length=Math.sqrt(dx*dx+dy*dy+dz*dz);
        if(!Double.isFinite(length) || length<1E-7 || !Double.isFinite(s.width()) || s.width()<=0 || s.normalAxis()<0 || s.normalAxis()>2)return;
        dx/=length;dy/=length;dz/=length;
        double sx=miter(dx,dy,dz,s.normalAxis(),s.previousDirection(),s.width(),0);
        double sy=miter(dx,dy,dz,s.normalAxis(),s.previousDirection(),s.width(),1);
        double sz=miter(dx,dy,dz,s.normalAxis(),s.previousDirection(),s.width(),2);
        double ex=miter(dx,dy,dz,s.normalAxis(),s.nextDirection(),s.width(),0);
        double ey=miter(dx,dy,dz,s.normalAxis(),s.nextDirection(),s.width(),1);
        double ez=miter(dx,dy,dz,s.normalAxis(),s.nextDirection(),s.width(),2);
        sink.emit(s.ax()-sx,s.ay()-sy,s.az()-sz,2,0,s.startOpacity());
        sink.emit(s.ax()+sx,s.ay()+sy,s.az()+sz,3,0,s.startOpacity());
        sink.emit(s.bx()+ex,s.by()+ey,s.bz()+ez,3,1,s.endOpacity());
        sink.emit(s.bx()-ex,s.by()-ey,s.bz()-ez,2,1,s.endOpacity());
    }
    private static double miter(double dx,double dy,double dz,int normalAxis,int adjacent,double width,int component) {
        double side=cross(normalAxis,component,dx,dy,dz);
        if(adjacent<0 || adjacent>5 || adjacent/2==normalAxis)return side*width;
        double ox=AmbientTronField.component(adjacent,0),oy=AmbientTronField.component(adjacent,1),oz=AmbientTronField.component(adjacent,2);
        double dot=dx*ox+dy*oy+dz*oz;
        return dot<-.5 ? side*width : (side+cross(normalAxis,component,ox,oy,oz))*width/(1+dot);
    }
    private static double cross(int normalAxis,int component,double dx,double dy,double dz) {
        return switch(normalAxis) {
            case 0 -> component==0 ? 0 : component==1 ? -dz : dy;
            case 1 -> component==0 ? dz : component==1 ? 0 : -dx;
            default -> component==0 ? -dy : component==1 ? dx : 0;
        };
    }
}
