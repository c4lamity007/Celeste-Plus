package dev.c4lam.celeste.compat;
public final class BetaRenderPipelines {
    public static boolean active, failClose;
    public static int opens, closes;
    private BetaRenderPipelines() { }
    public static Lease open() { if (active) throw new IllegalStateException("already open"); active = true; opens++; return new Lease(); }
    public static final class Lease implements AutoCloseable {
        private boolean closed;
        private Lease() { }
        @Override public void close() {
            if (closed) return;
            if (failClose) throw new IllegalStateException("pipeline cleanup failed");
            closed = true; active = false; closes++;
        }
    }
}
