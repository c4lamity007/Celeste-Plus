package dev.c4lam.celeste.auth;

import java.util.concurrent.atomic.AtomicInteger;

record AuthConfig(String releaseDigest) {
    static volatile String digest = "fixture-digest";
    static final java.security.KeyPair incidentKey = key();
    private static java.security.KeyPair key() {
        try { return java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
        catch(java.security.GeneralSecurityException failure) { throw new AssertionError(failure); }
    }
    byte[] serverKey() { return Crypto.rawPublic(incidentKey.getPublic()); }
    static final AtomicInteger loads = new AtomicInteger();
    static AuthConfig load() { loads.incrementAndGet(); return new AuthConfig(digest); }
}
