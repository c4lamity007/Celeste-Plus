package dev.c4lam.celeste.module.impl.visual.expansion;

/** Flat grid ribbon: exact miter joints and soft caps contained inside exposed endpoints. */
public final class AmbientTronGeometry {
    public static final int MAX_VERTICES = 24;
    @FunctionalInterface public interface Vertex { void emit(double x,double y,double z,float u,float v,float opacity); }
    private AmbientTronGeometry() { }
    public static void beam(AmbientTronField.Segment s,double eyeX,double eyeY,double eyeZ,Vertex sink) {
        double dx=s.bx()-s.ax(),dy=s.by()-s.ay(),dz=s.bz()-s.az();
        double length=Math.sqrt(dx*dx+dy*dy+dz*dz);
        if(!Double.isFinite(length)||length<1E-7||!Double.isFinite(s.width())||s.width()<=0||s.normalAxis()<0||s.normalAxis()>2)return;
        dx/=length;dy/=length;dz/=length;
        boolean start=s.previousDirection()<0,end=s.nextDirection()<0;
        // A newly born fragment grows in width too, avoiding a full-width flash across a tiny line.
        double width=start&&end?Math.min(s.width(),length*.45):s.width();
        double sx=miter(dx,dy,dz,s.normalAxis(),s.previousDirection(),width,0);
        double sy=miter(dx,dy,dz,s.normalAxis(),s.previousDirection(),width,1);
        double sz=miter(dx,dy,dz,s.normalAxis(),s.previousDirection(),width,2);
        double ex=miter(dx,dy,dz,s.normalAxis(),s.nextDirection(),width,0);
        double ey=miter(dx,dy,dz,s.normalAxis(),s.nextDirection(),width,1);
        double ez=miter(dx,dy,dz,s.normalAxis(),s.nextDirection(),width,2);
        double cap=Math.min(length*.45,width*2.4);
        double from=start?cap:0,to=end?length-cap:length;
        double ax=s.ax()+dx*from,ay=s.ay()+dy*from,az=s.az()+dz*from;
        double bx=s.ax()+dx*to,by=s.ay()+dy*to,bz=s.az()+dz*to;
        float a=alpha(s,from/length),b=alpha(s,to/length);
        if(start)quad(s.ax(),s.ay(),s.az(),0,0,0,0,ax,ay,az,sx,sy,sz,a,sink);
        quad(ax,ay,az,sx,sy,sz,a,bx,by,bz,ex,ey,ez,b,sink);
        if(end)quad(bx,by,bz,ex,ey,ez,b,s.bx(),s.by(),s.bz(),0,0,0,0,sink);
    }
    private static float alpha(AmbientTronField.Segment s,double t) {
        return (float)(s.startOpacity()+(s.endOpacity()-s.startOpacity())*t);
    }
    private static void quad(double ax,double ay,double az,double sx,double sy,double sz,float a,
                             double bx,double by,double bz,double ex,double ey,double ez,float b,Vertex sink) {
        sink.emit(ax-sx,ay-sy,az-sz,2,0,a);
        sink.emit(ax,ay,az,2.5f,0,a);
        sink.emit(bx,by,bz,2.5f,1,b);
        sink.emit(bx-ex,by-ey,bz-ez,2,1,b);
        // Keep UV=.5 exactly on the centerline. One tapered quad bends its bright core diagonally.
        sink.emit(ax,ay,az,2.5f,0,a);
        sink.emit(ax+sx,ay+sy,az+sz,3,0,a);
        sink.emit(bx+ex,by+ey,bz+ez,3,1,b);
        sink.emit(bx,by,bz,2.5f,1,b);
    }
    private static double miter(double dx,double dy,double dz,int normalAxis,int adjacent,double width,int component) {
        double side=cross(normalAxis,component,dx,dy,dz);
        if(adjacent<0||adjacent>5||adjacent/2==normalAxis)return side*width;
        double ox=AmbientTronField.component(adjacent,0),oy=AmbientTronField.component(adjacent,1),oz=AmbientTronField.component(adjacent,2);
        double dot=dx*ox+dy*oy+dz*oz;
        return dot<-.5?side*width:(side+cross(normalAxis,component,ox,oy,oz))*width/(1+dot);
    }
    private static double cross(int normalAxis,int component,double dx,double dy,double dz) {
        return switch(normalAxis) {
            case 0 -> component==0?0:component==1?-dz:dy;
            case 1 -> component==0?dz:component==1?0:-dx;
            default -> component==0?-dy:component==1?dx:0;
        };
    }
}
