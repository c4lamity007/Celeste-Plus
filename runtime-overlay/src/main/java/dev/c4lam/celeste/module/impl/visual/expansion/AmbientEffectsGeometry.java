package dev.c4lam.celeste.module.impl.visual.expansion;

/** Shared geometry emission for mixed-style world batches and the offscreen GPU fixture. */
public final class AmbientEffectsGeometry {
    public static final int SCRATCH_SIZE = Math.max(AmbientGeometry.SCRATCH_SIZE, AmbientTesseractGeometry.SCRATCH_SIZE);
    public interface ParticleView {
        double x(); double y(); double z(); double radius(); double yaw(); double pitch();
        float opacity(); int color();
    }
    @FunctionalInterface public interface Sink {
        void vertex(double x, double y, double z, float u, float v, double nx, double ny, double nz, float alpha, int color);
    }
    private static final int[][] FACES = {{0,3,2},{0,4,3},{0,5,4},{0,2,5},{1,2,3},{1,3,4},{1,4,5},{1,5,2}};
    private AmbientEffectsGeometry() { }

    public static void mesh(ParticleView p, AmbientField.Style style, double[] scratch, Sink out) {
        if (style == AmbientField.Style.TRON_TRAILS) return;
        if (style == AmbientField.Style.TESSERACTS) {
            AmbientTesseractGeometry.corners(scratch,p.x(),p.y(),p.z(),p.radius(),p.yaw(),p.pitch());
            AmbientTesseractGeometry.fill(scratch,(x,y,z,u,v,nx,ny,nz,a) -> out.vertex(x,y,z,u,v,nx,ny,nz,p.opacity()*a,p.color()));
            return;
        }
        if (style == AmbientField.Style.LEAVES) {
            AmbientGeometry.leaf(scratch,p.x(),p.y(),p.z(),p.radius(),p.yaw(),p.pitch());
            for (int edge=0;edge<AmbientGeometry.LEAF_EDGE_VERTICES;edge++) {
                int next=(edge+1)%AmbientGeometry.LEAF_EDGE_VERTICES;
                leafFace(p,scratch,8,next,edge,out);
                leafFace(p,scratch,9,edge,next,out);
            }
            return;
        }
        double stretch=style==AmbientField.Style.CRYSTALS?2.2:style==AmbientField.Style.SNOW?.35:1;
        AmbientGeometry.core(scratch,p.x(),p.y(),p.z(),p.radius(),stretch,p.yaw(),p.pitch());
        for(int[] face:FACES) {
            AmbientGeometry.normal(scratch,face[0],face[1],face[2]);
            coreVertex(p,scratch,face[0],0,0,out);
            coreVertex(p,scratch,face[1],1,0,out);
            coreVertex(p,scratch,face[2],.5F,1,out);
        }
    }

    public static void glow(ParticleView p, AmbientField.Style style, double[] scratch,
                            double cameraX,double cameraY,double cameraZ,
                            double rightX,double rightY,double rightZ,double upX,double upY,double upZ,Sink out) {
        if(style==AmbientField.Style.SNOW||style==AmbientField.Style.LEAVES||style==AmbientField.Style.TRON_TRAILS)return;
        if(style==AmbientField.Style.TESSERACTS) {
            AmbientTesseractGeometry.corners(scratch,p.x(),p.y(),p.z(),p.radius(),p.yaw(),p.pitch());
            AmbientTesseractGeometry.beams(scratch,cameraX,cameraY,cameraZ,p.radius(),(x,y,z,u,v,nx,ny,nz,a)->
                out.vertex(x,y,z,u,v,nx,ny,nz,p.opacity()*a,p.color()));
            return;
        }
        double radius=p.radius()*(style==AmbientField.Style.CRYSTALS?2.8:5.5);
        double ux=rightX*radius,uy=rightY*radius,uz=rightZ*radius,vx=upX*radius,vy=upY*radius,vz=upZ*radius;
        float alpha=p.opacity()*(style==AmbientField.Style.CRYSTALS?.15F:.48F);
        out.vertex(p.x()-ux-vx,p.y()-uy-vy,p.z()-uz-vz,0,0,0,0,0,alpha,p.color());
        out.vertex(p.x()+ux-vx,p.y()+uy-vy,p.z()+uz-vz,1,0,0,0,0,alpha,p.color());
        out.vertex(p.x()+ux+vx,p.y()+uy+vy,p.z()+uz+vz,1,1,0,0,0,alpha,p.color());
        out.vertex(p.x()-ux+vx,p.y()-uy+vy,p.z()-uz+vz,0,1,0,0,0,alpha,p.color());
    }

    private static void coreVertex(ParticleView p,double[] a,int index,float u,float v,Sink out) {
        int offset=index*3;
        out.vertex(a[offset],a[offset+1],a[offset+2],u,v,a[18],a[19],a[20],p.opacity()*.88F,p.color());
    }
    private static void leafFace(ParticleView p,double[] a,int first,int second,int third,Sink out) {
        AmbientGeometry.leafNormal(a,first,second,third);
        leafVertex(p,a,first,out);leafVertex(p,a,second,out);leafVertex(p,a,third,out);
    }
    private static void leafVertex(ParticleView p,double[] a,int index,Sink out) {
        int offset=index*3,normal=AmbientGeometry.LEAF_NORMAL_OFFSET;
        out.vertex(a[offset],a[offset+1],a[offset+2],AmbientGeometry.leafU(index),AmbientGeometry.leafV(index),
            a[normal],a[normal+1],a[normal+2],p.opacity()*.96F,p.color());
    }
}
