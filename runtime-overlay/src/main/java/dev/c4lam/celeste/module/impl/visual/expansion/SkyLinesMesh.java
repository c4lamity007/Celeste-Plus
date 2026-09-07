package dev.c4lam.celeste.module.impl.visual.expansion;

/** Shared production geometry emitter; one callback per batch, with no per-vertex objects. */
public final class SkyLinesMesh {
    @FunctionalInterface public interface Vertex { void vertex(double x, double y, double z, int color); }
    private SkyLinesMesh() { }
    public static void emit(SkyLinesMath.Geometry geometry, Vertex output) {
        // Geometry is already camera-relative: translation never drags the celestial sphere.
        for (SkyLinesMath.Segment segment : geometry.segments()) {
            ribbon(output, segment, 7, .055);
            ribbon(output, segment, 3.2, .11);
            ribbon(output, segment, 1, .82);
        }
        for (SkyLinesMath.Star star : geometry.stars()) {
            star(output, star, 4, .09);
            star(output, star, 1, .9);
        }
    }
    private static void ribbon(Vertex output, SkyLinesMath.Segment s,
                               double spread, double alpha) {
        SkyLinesMath.Point a = s.from(), b = s.to();
        double dx = b.x() - a.x(), dy = b.y() - a.y(), dz = b.z() - a.z();
        double mx = a.x() + b.x(), my = a.y() + b.y(), mz = a.z() + b.z();
        double nx = dy * mz - dz * my, ny = dz * mx - dx * mz, nz = dx * my - dy * mx;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1.0e-8) return;
        double scale = s.width() * spread / length;
        nx *= scale; ny *= scale; nz *= scale;
        int color = argb((float)(s.opacity() * alpha), s.color());
        output.vertex(a.x()-nx,a.y()-ny,a.z()-nz,color);
        output.vertex(b.x()-nx,b.y()-ny,b.z()-nz,color);
        output.vertex(b.x()+nx,b.y()+ny,b.z()+nz,color);
        output.vertex(a.x()+nx,a.y()+ny,a.z()+nz,color);
    }

    private static void star(Vertex output, SkyLinesMath.Star star,
                             double spread, double alpha) {
        SkyLinesMath.Point p = star.position();
        double horizontal = Math.hypot(p.x(), p.z());
        if (horizontal < 1.0e-8) return;
        double r = star.radius() * spread;
        double ux = -p.z() / horizontal * r, uz = p.x() / horizontal * r;
        double vx = -p.y() * p.x() / (horizontal * SkyLinesMath.RADIUS) * r;
        double vy = horizontal / SkyLinesMath.RADIUS * r;
        double vz = -p.y() * p.z() / (horizontal * SkyLinesMath.RADIUS) * r;
        int color = argb((float)(star.opacity() * alpha), star.color());
        output.vertex(p.x()-ux,p.y(),p.z()-uz,color);
        output.vertex(p.x()+vx,p.y()+vy,p.z()+vz,color);
        output.vertex(p.x()+ux,p.y(),p.z()+uz,color);
        output.vertex(p.x()-vx,p.y()-vy,p.z()-vz,color);
    }

    private static int argb(float alpha, int rgb) {
        return Math.clamp(Math.round(alpha * 255), 0, 255) << 24 | rgb & 0xFFFFFF;
    }
}
