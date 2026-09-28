package dev.c4lam.celeste.beta.memory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Exact native sizes/hashes carried by already authenticated outer artifacts.
 * Parsing this index does not authenticate it or authorize arbitrary bytes.
 */
public final class NativeResourceBudget {
    public static final String RESOURCE = "META-INF/celeste-beta/native-auth-resources.txt";
    public static final int MAX_INDEX = 16 * 1024;
    public static final int MAX_NATIVE = 32 * 1024 * 1024;
    public static final long MAX_TOTAL_NATIVE = 64L * 1024 * 1024;
    private static final String HEADER = "celeste-native-auth-budget-1\n";
    private static final NativeResourceBudget EMPTY = new NativeResourceBudget(Map.of(), null);
    private record Entry(int size, String sha256) { }
    private final Map<String, Entry> entries;
    private final byte[] index;

    private NativeResourceBudget(Map<String, Entry> entries, byte[] index) {
        this.entries = Map.copyOf(entries);
        this.index = index;
    }

    public static NativeResourceBudget empty() { return EMPTY; }

    /** The caller must establish this stream's signed-artifact provenance first. */
    public static NativeResourceBudget readTrusted(InputStream input) throws IOException {
        if (input == null) return empty(); // Previous Java-only artifacts have no exception.
        byte[] bytes = input.readNBytes(MAX_INDEX + 1);
        try { return parseTrusted(bytes); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }

    public static NativeResourceBudget parseTrusted(byte[] input) throws IOException {
        if (input == null || input.length == 0 || input.length > MAX_INDEX) throw invalid();
        for (byte value : input) if (value < 0 || value == '\r' || value == 0) throw invalid();
        String text = new String(input, StandardCharsets.US_ASCII);
        if (!text.startsWith(HEADER) || !text.endsWith("\n")) throw invalid();
        String[] lines = text.substring(HEADER.length()).split("\n", -1);
        if (lines.length < 2 || lines.length > 5 || !lines[lines.length - 1].isEmpty()) throw invalid();
        Map<String, Entry> entries = new HashMap<>();
        String previous = "";
        long total = 0;
        for (int number = 0; number < lines.length - 1; number++) {
            String[] fields = lines[number].split(" ", -1);
            if (fields.length != 3 || !fields[0].matches("[1-9][0-9]{0,8}")
                || !fields[1].matches("[0-9a-f]{64}") || !nativePath(fields[2])
                || fields[2].compareTo(previous) <= 0) throw invalid();
            int size;
            try { size = Integer.parseInt(fields[0]); }
            catch (NumberFormatException error) { throw invalid(); }
            if (size < 90 || size > MAX_NATIVE || total > MAX_TOTAL_NATIVE - size) throw invalid();
            total += size; // Whole declaration is bounded before any native allocation.
            entries.put(fields[2], new Entry(size, fields[1]));
            previous = fields[2];
        }
        return new NativeResourceBudget(entries, input.clone());
    }

    private static boolean nativePath(String path) {
        if (!path.startsWith("META-INF/") || path.length() > 512 || path.equals("META-INF/MANIFEST.MF")
            || path.startsWith("META-INF/celeste-beta/") || path.startsWith("META-INF/jars/") || path.startsWith("META-INF/.r/")
            || path.endsWith("/") || path.endsWith(".class") || path.endsWith(".jar")
            || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0) return false;
        for (int i = 0; i < path.length(); i++) if (path.charAt(i) < 33 || path.charAt(i) > 126) return false;
        for (String part : path.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        return true;
    }

    public int entryLimit(String name, long advertisedSize, int ordinaryLimit) throws IOException {
        Entry entry = entries.get(name);
        if (entry == null) return ordinaryLimit;
        if (advertisedSize != entry.size()) throw new IOException("Approved native size differs");
        return entry.size();
    }

    public boolean contains(String name) { return entries.containsKey(name); }

    /** Verify native content before retaining/exposing it, even below 8 MiB. */
    public void verify(String name, byte[] content) throws IOException {
        Entry entry = entries.get(name);
        if (entry == null) return;
        if (content.length != entry.size() || !pe64Dll(content)
            || !digest(content).equals(entry.sha256())) throw new IOException("Approved native bytes differ");
    }

    public void verifyIndex(byte[] content) throws IOException {
        if (!Arrays.equals(index, content)) throw new IOException("Authenticated native resource budget differs");
    }

    public void requireComplete(Set<String> observed) throws IOException {
        if (!observed.containsAll(entries.keySet())) throw new IOException("Approved native resource is missing");
    }

    private static boolean pe64Dll(byte[] value) {
        if (value.length < 90 || value[0] != 'M' || value[1] != 'Z') return false;
        long offset = u32(value, 60);
        if (offset < 64 || offset > value.length - 26) return false;
        int at = (int) offset;
        return value[at] == 'P' && value[at + 1] == 'E' && value[at + 2] == 0 && value[at + 3] == 0
            && u16(value, at + 4) == 0x8664 && (u16(value, at + 22) & 0x2000) != 0 && u16(value, at + 24) == 0x20b;
    }

    private static int u16(byte[] bytes, int at) { return (bytes[at] & 255) | (bytes[at + 1] & 255) << 8; }
    private static long u32(byte[] bytes, int at) { return (long) u16(bytes, at) | (long) u16(bytes, at + 2) << 16; }
    private static String digest(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static IOException invalid() { return new IOException("Invalid authenticated native resource budget"); }
}
