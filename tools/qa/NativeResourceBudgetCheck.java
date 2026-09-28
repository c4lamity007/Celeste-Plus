package dev.c4lam.celeste.auth;

import dev.c4lam.celeste.beta.memory.NativeResourceBudget;
import dev.c4lam.celeste.beta.memory.TwoPartMemoryClassLoader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Bounded synthetic PE resources only: no native loading or game/auth actions. */
public final class NativeResourceBudgetCheck {
    private static int checks;
    private static final String RESOURCE = "META-INF/approved/native.bin";
    private static final String HEADER = "celeste-native-auth-budget-1\n";
    private static final int MIB = 1024 * 1024;
    private static final TwoPartMemoryClassLoader.Namespaces NAMES =
        new TwoPartMemoryClassLoader.Namespaces(Set.of("fixture."), Set.of("META-INF/", "assets/"));
    private static final TwoPartMemoryClassLoader.Limits LIMITS =
        new TwoPartMemoryClassLoader.Limits(64 * MIB, 65536, 8 * MIB, 128L * MIB);

    private NativeResourceBudgetCheck() { }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static void reject(Action action, String why) throws Exception {
        checks++;
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError(why);
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    private static String hash(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
    private static String line(int size, String hash, String name) { return size + " " + hash + " " + name + "\n"; }
    private static byte[] index(byte[] data) throws Exception { return bytes(HEADER + line(data.length, hash(data), RESOURCE)); }
    private static byte[] pe(int size) {
        byte[] data = new byte[size];
        data[0]='M'; data[1]='Z'; data[60]=64;
        data[64]='P'; data[65]='E'; data[68]=0x64; data[69]=(byte)0x86; data[87]=0x20;
        data[88]=0x0b; data[89]=0x02;
        return data;
    }
    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (var item : entries.entrySet()) { zip.putNextEntry(new ZipEntry(item.getKey())); zip.write(item.getValue()); zip.closeEntry(); }
        }
        return buffer.toByteArray();
    }
    private static void load(Map<String, byte[]> first, NativeResourceBudget budget) throws Exception {
        byte[] a = zip(first), b = zip(Map.of("assets/fixture.txt", new byte[]{1}));
        try (TwoPartMemoryClassLoader loader = TwoPartMemoryClassLoader.open(a, hash(a), b, hash(b), NAMES, LIMITS,
                ClassLoader.getPlatformClassLoader(), budget)) {
            try (var stream = loader.getResourceAsStream(RESOURCE)) {
                check(stream != null && stream.read() == 'M', "Approved native resource retained");
            }
        }
    }
    private static String integrity(Map<String, byte[]> first, Map<String, byte[]> second) throws Exception {
        return ArtifactIntegrity.digestVerifiedMemory(visitor -> { visitor.accept(first); visitor.accept(second); });
    }

    public static void main(String[] args) throws Exception {
        byte[] dll = pe(20_523_520); // Exact size of the first observed VMP candidate.
        byte[] approved = index(dll);
        NativeResourceBudget budget = NativeResourceBudget.parseTrusted(approved);
        Map<String, byte[]> first = Map.of(RESOURCE, dll, NativeResourceBudget.RESOURCE, approved);
        load(first, budget);
        check(budget.entryLimit(RESOURCE, dll.length, 8*MIB) == dll.length, "Only exact approved path receives exception");
        check(budget.entryLimit("assets/ordinary", 9*MIB, 8*MIB) == 8*MIB, "Normal entry bound unchanged");
        reject(() -> load(first, NativeResourceBudget.empty()), "Self-declared payload index widened default limits");
        byte[] a = zip(first), b = zip(Map.of("assets/fixture.txt", new byte[]{1}));
        reject(() -> { try (var ignored = TwoPartMemoryClassLoader.open(a, hash(a), b, hash(b), NAMES, LIMITS,
                         ClassLoader.getPlatformClassLoader())) { check(ignored.retainedByteCount() > 0, "Unexpected default load"); } },
               "Old overload accepted a 20MiB self-declared native");
        reject(() -> load(Map.of(RESOURCE, dll), budget), "Authenticated budget absent in retained payload");
        reject(() -> load(Map.of(NativeResourceBudget.RESOURCE, approved, "assets/unrelated", new byte[8*MIB+1]), budget),
               "Approved budget widened unrelated resource");
        dll[100] = 1;
        reject(() -> load(first, budget), "Approved native hash mismatch accepted");
        reject(() -> integrity(first, Map.of("assets/fixture.txt", new byte[]{1})), "Integrity accepted altered native bytes");
        dll[100] = 0;
        String digest = integrity(first, Map.of("assets/fixture.txt", new byte[]{1}));
        check(digest.matches("[0-9a-f]{64}"), "Actual retained integrity accepts exact approved20MiB resource");
        reject(() -> integrity(Map.of(RESOURCE, dll), Map.of("assets/fixture.txt", new byte[]{1})), "Integrity default cap widened");
        reject(() -> integrity(first, Map.of(NativeResourceBudget.RESOURCE, approved)), "Duplicate index accepted across parts");
        reject(() -> integrity(first, Map.of("assets/too-big", new byte[8*MIB+1])), "Integrity widened ordinary entry cap");
        reject(() -> budget.entryLimit(RESOURCE, dll.length-1, 8*MIB), "Advertised exact size mismatch accepted");
        reject(() -> budget.requireComplete(Set.of()), "Missing declared native accepted");
        byte[] malformedPe = dll.clone(); malformedPe[68]=0x4c;
        reject(() -> load(Map.of(RESOURCE, malformedPe, NativeResourceBudget.RESOURCE, index(malformedPe)),
                         NativeResourceBudget.parseTrusted(index(malformedPe))), "Wrong machine PE accepted");
        malformedPe[68]=0x64; malformedPe[87]=0;
        reject(() -> NativeResourceBudget.parseTrusted(index(malformedPe)).verify(RESOURCE, malformedPe), "EXE without DLL flag accepted");
        String zero = "0".repeat(64);
        reject(() -> NativeResourceBudget.parseTrusted(bytes(HEADER+line(32*MIB+1,zero,RESOURCE))), "Per-native cap widened");
        reject(() -> NativeResourceBudget.parseTrusted(bytes(HEADER+line(32*MIB,zero,"META-INF/a.bin")
                 +line(32*MIB,zero,"META-INF/b.bin")+line(90,zero,"META-INF/c.bin"))), "Aggregate cap widened");
        NativeResourceBudget.parseTrusted(bytes(HEADER+line(32*MIB,zero,"META-INF/a.bin")+line(32*MIB,zero,"META-INF/b.bin")));
        checks++;
        for (String invalid : new String[]{
                HEADER, HEADER+"090 "+zero+" "+RESOURCE+"\n", HEADER+line(90,zero,RESOURCE)+line(90,zero,RESOURCE),
                HEADER+line(90,zero,"META-INF/z.bin")+line(90,zero,"META-INF/a.bin"),
                HEADER+"90 "+zero+" "+RESOURCE+" extra\n", HEADER+line(90,zero,"META-INF/../a.bin"),
                HEADER+line(90,zero,"assets/native.bin"), HEADER+line(90,zero,"META-INF/a.class"),
                HEADER+line(90,zero,"META-INF/celeste-beta/a.bin"), HEADER+line(90,zero,"META-INF/jars/a.bin"),
                HEADER+line(90,zero,"META-INF/.r/a.bin"), HEADER+line(90,zero,"META-INF/MANIFEST.MF"),
                HEADER+line(90,zero,"META-INF/"+"a".repeat(505)), HEADER+line(90,zero,"META-INF/a\u007f.bin"),
                HEADER+line(90,"a".repeat(63)+"A",RESOURCE), HEADER+line(90,zero,RESOURCE)+"\n",
                HEADER+line(90,zero,RESOURCE).replace("\n","\r\n"),
                HEADER+line(90,zero,"META-INF/a")+line(90,zero,"META-INF/b")+line(90,zero,"META-INF/c")
                    +line(90,zero,"META-INF/d")+line(90,zero,"META-INF/e")}) {
            reject(() -> NativeResourceBudget.parseTrusted(bytes(invalid)), "Invalid/noncanonical index accepted");
        }
        reject(() -> NativeResourceBudget.parseTrusted(new byte[16*1024+1]), "Oversized index accepted");
        reject(() -> NativeResourceBudget.readTrusted(new java.io.ByteArrayInputStream(new byte[16*1024+1])), "Bounded index read widened");
        check(NativeResourceBudget.readTrusted(null) == NativeResourceBudget.empty(), "Old Java-only artifacts need no exception");
        System.out.println("PASS native resource budget: "+checks+" checks; exact20,523,520-byte PE succeeds; no native execution");
    }
}
