package dev.c4lam.celeste.auth;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

final class CredentialStore {
    CredentialStore() { }
    CredentialStore(String product) { if (!product.equals("celeste_plus")) throw new IllegalArgumentException(); }
    static volatile boolean present = true;
    static final AtomicInteger loads = new AtomicInteger();
    static final AtomicInteger saves = new AtomicInteger();
    static final AtomicInteger clears = new AtomicInteger();
    boolean exists() { return present; }
    InstallCredential load() throws IOException { loads.incrementAndGet(); return new InstallCredential(); }
    void save(InstallCredential credential) throws IOException { saves.incrementAndGet(); present = true; }
    void clear() throws IOException { clears.incrementAndGet(); present = false; }
}
