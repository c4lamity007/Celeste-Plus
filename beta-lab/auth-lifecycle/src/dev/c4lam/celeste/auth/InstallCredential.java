package dev.c4lam.celeste.auth;
final class InstallCredential implements AutoCloseable {
    static final java.util.List<InstallCredential> created = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean destroyed;
    InstallCredential() { created.add(this); }
    boolean isDestroyed() { return destroyed; }
    @Override public void close() { destroyed = true; }
}
