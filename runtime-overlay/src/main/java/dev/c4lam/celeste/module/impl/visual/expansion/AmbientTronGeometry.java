package dev.c4lam.celeste.module.impl.visual.expansion;

/** Camera-facing width around a genuinely planar grid line; uses the existing ambient beam shader. */
public final class AmbientTronGeometry {
    @FunctionalInterface public interface Vertex { void emit(double x, double y, double z, float u, float v, float opacity); }
    private AmbientTronGeometry() { }
    public static void beam(AmbientTronField.Segment s, double eyeX, double eyeY, double eyeZ, Vertex sink) {
        double dx = s.bx()-s.ax(), dz = s.bz()-s.az(), length = Math.sqrt(dx*dx+dz*dz);
        if (!Double.isFinite(length) || length < 1.0E-7 || !Double.isFinite(s.width()) || s.width() <= 0) return;
        double vx = (s.ax()+s.bx())*.5-eyeX, vy = s.ay()-eyeY, vz = (s.az()+s.bz())*.5-eyeZ;
        double nx = -dz*vy, ny = dz*vx-dx*vz, nz = dx*vy;
        double normal = Math.sqrt(nx*nx+ny*ny+nz*nz);
        if (normal < 1.0E-9) { nx = -dz; ny = 0; nz = dx; normal = length; }
        double scale = s.width()/normal; nx*=scale; ny*=scale; nz*=scale;
        // Tiny overlap keeps the luminous center joined at right-angle corners.
        double ex = dx/length*.012, ez = dz/length*.012;
        sink.emit(s.ax()-ex-nx,s.ay()-ny,s.az()-ez-nz,2,0,s.startOpacity());
        sink.emit(s.ax()-ex+nx,s.ay()+ny,s.az()-ez+nz,3,0,s.startOpacity());
        sink.emit(s.bx()+ex+nx,s.by()+ny,s.bz()+ez+nz,3,1,s.endOpacity());
        sink.emit(s.bx()+ex-nx,s.by()-ny,s.bz()+ez-nz,2,1,s.endOpacity());
    }
}
