package dev.c4lam.celeste.beta.resources;
import java.util.Map;
public final class InternalMemoryResources {
    public static boolean closed;
    public static int lastByte;
    public InternalMemoryResources(Map<String, byte[]> source) {
        lastByte = source.get("assets/celeste/test.png")[0];
    }
    public void close() { closed = true; }
}
