package dev.c4lam.celeste.auth;

/** Live module evidence is tested separately; protocol fixtures never invent an artifact digest. */
final class ArtifactIntegrity {
    private ArtifactIntegrity() { }
    static String digest() throws java.io.IOException {
        throw new AssertionError("Live artifact reads forbidden in protocol fixture");
    }
}
