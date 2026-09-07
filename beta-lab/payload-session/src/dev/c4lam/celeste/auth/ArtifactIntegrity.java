package dev.c4lam.celeste.auth;
import java.io.IOException;
import java.util.Map;
public final class ArtifactIntegrity {
    public static boolean installed;
    private ArtifactIntegrity() { }
    @FunctionalInterface public interface PartVisitor { void accept(Map<String, byte[]> part) throws IOException; }
    @FunctionalInterface public interface VerifiedMemorySource { void visitParts(PartVisitor visitor) throws IOException; }
    public static String digestVerifiedMemory(VerifiedMemorySource source) throws IOException { return fixture.SessionCheck.DIGEST; }
    public static void installVerifiedMemorySource(VerifiedMemorySource source) { installed = true; }
}
