package dev.c4lam.celeste.auth;

import java.util.concurrent.atomic.AtomicInteger;

final class ArtifactIntegrity {
    static final AtomicInteger calls = new AtomicInteger();
    static volatile String observed = "fixture-digest";
    static volatile boolean retainedMemory;
    private ArtifactIntegrity() { }
    static String digest() { calls.incrementAndGet(); return observed; }
    static boolean hasRetainedMemorySource() { return retainedMemory; }
}
