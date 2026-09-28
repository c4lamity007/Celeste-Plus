package dev.c4lam.celeste.module.impl.visual.expansion;

/** Actual closed faces and world-space beams, shared by production and GPU fixtures. */
public final class AmbientTesseractGeometry {
    public static final int SCRATCH_SIZE = 48;
    public static final int FILL_VERTICES = 36;
    public static final int BEAM_VERTICES = 112;
    private static final int[] TRIANGLES = {0,1,2,0,2,3};
    private static final int[][] FACES = {{0,4,6,2},{1,3,7,5},{0,1,5,4},{2,6,7,3},{0,2,3,1},{4,5,7,6}};
    @FunctionalInterface public interface Vertex {
        void emit(double x,double y,double z,float u,float v,double nx,double ny,double nz,float opacity);
    }
    private AmbientTesseractGeometry() { }
    public static void corners(double[] out,double px,double py,double pz,double radius,double yaw,double pitch) {
        double cy=Math.cos(yaw),sy=Math.sin(yaw),cp=Math.cos(pitch),sp=Math.sin(pitch);
        for(int i=0;i<16;i++) {
            double r=radius*(i<8?1:.42),x=(i&1)==0?-r:r,y=(i&2)==0?-r:r,z=(i&4)==0?-r:r;
            double ry=y*cp-z*sp,rz=y*sp+z*cp;
            out[i*3]=px+x*cy-rz*sy;out[i*3+1]=py+ry;out[i*3+2]=pz+x*sy+rz*cy;
        }
    }
    public static void fill(double[] p,Vertex sink) {
        for(int[] face:FACES) {
            int a=face[0]*3,b=face[1]*3,c=face[2]*3;
            double ux=p[b]-p[a],uy=p[b+1]-p[a+1],uz=p[b+2]-p[a+2],vx=p[c]-p[a],vy=p[c+1]-p[a+1],vz=p[c+2]-p[a+2];
            double nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx,scale=1/Math.sqrt(nx*nx+ny*ny+nz*nz);
            for(int index:TRIANGLES) {
                int at=face[index]*3;sink.emit(p[at],p[at+1],p[at+2],4,0,nx*scale,ny*scale,nz*scale,.17f);
            }
        }
    }
    public static void beams(double[] p,double eyeX,double eyeY,double eyeZ,double radius,Vertex sink) {
        for(int layer=0;layer<2;layer++) for(int corner=0;corner<8;corner++) for(int bit=1;bit<=4;bit<<=1) {
            if((corner&bit)!=0)continue;
            beam(p,(layer*8+corner)*3,(layer*8+(corner|bit))*3,eyeX,eyeY,eyeZ,radius*(layer==0?.15:.10),layer==0?1:.68f,sink);
        }
        // Four full body diagonals contain the eight outer-to-inner corner links.
        for(int corner=0;corner<4;corner++)beam(p,corner*3,(corner^7)*3,eyeX,eyeY,eyeZ,radius*.085,.42f,sink);
    }
    private static void beam(double[] p,int a,int b,double ex,double ey,double ez,double width,float opacity,Vertex sink) {
        double dx=p[b]-p[a],dy=p[b+1]-p[a+1],dz=p[b+2]-p[a+2];
        double vx=(p[a]+p[b])*.5-ex,vy=(p[a+1]+p[b+1])*.5-ey,vz=(p[a+2]+p[b+2])*.5-ez;
        double nx=dy*vz-dz*vy,ny=dz*vx-dx*vz,nz=dx*vy-dy*vx;
        double length=Math.sqrt(nx*nx+ny*ny+nz*nz);
        if(length<1.0E-9){nx=-dy;ny=dx;nz=0;length=Math.sqrt(nx*nx+ny*ny);}
        if(length<1.0E-9){nx=dz;ny=0;nz=-dx;length=Math.sqrt(nx*nx+nz*nz);}
        if(length<1.0E-9)return;
        nx*=width/length;ny*=width/length;nz*=width/length;
        sink.emit(p[a]-nx,p[a+1]-ny,p[a+2]-nz,2,0,0,0,0,opacity);
        sink.emit(p[a]+nx,p[a+1]+ny,p[a+2]+nz,3,0,0,0,0,opacity);
        sink.emit(p[b]+nx,p[b+1]+ny,p[b+2]+nz,3,1,0,0,0,opacity);
        sink.emit(p[b]-nx,p[b+1]-ny,p[b+2]-nz,2,1,0,0,0,opacity);
    }
}
