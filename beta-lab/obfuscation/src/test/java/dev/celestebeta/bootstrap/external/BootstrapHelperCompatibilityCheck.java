package dev.celestebeta.bootstrap.external;

import dev.c4lam.celeste.beta.delivery.BetaDeliveryClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Signed-archive helper compatibility only: no attachment, native loading or obfuscator execution. */
public final class BootstrapHelperCompatibilityCheck {
    private static final String ENTRY = VerifiedBootstrapLoader.PREFIX.replace('.', '/') + "MemoryPayloadBootstrap";
    private static final String CAPABILITY = ENTRY + "$PreparedGame";
    private static final String SESSION = ENTRY + "$Session";
    private static final String HELPER = "qN4z/Vf8wK/P7xDecode";
    private static final String NEIGHBOR = "qN4z/Vf8wK/ParentNeighbor";
    private static final String VAULT = "META-INF/qN4z/Vf8wK/K2dV.wR9e";
    private static final byte[] PLAINTEXT = bytes("signed helper string");
    private static int checks;

    private BootstrapHelperCompatibilityCheck() { }

    public static void main(String[] args) throws Exception {
        accepted();
        rejected();
        archiveBounds();
        System.out.println("PASS bootstrap B generated-helper compatibility: " + checks
            + " checks; executable helper/vault linkage, fixed reflective ABI and exact retained ownership");
    }

    private static void accepted() throws Exception {
        Map<String, byte[]> members = members(); byte[] archive = jar(members, true), snapshot = archive.clone();
        Parent parent = new Parent();
        URL retainedUrl;
        VerifiedBootstrapLoader loader = new VerifiedBootstrapLoader(archive, VerifiedBootstrapLoader.hash(archive), parent);
        try {
            Class<?> entry = loader.loadClass(ENTRY.replace('/', '.'));
            check(entry.getClassLoader() == loader && loader.loadClass(HELPER.replace('/', '.')).getClassLoader() == loader,
                "fixed B entry and retained arbitrary-package helper are child-owned");
            check(entry.getMethod("value").invoke(null).equals("signed helper string"),
                "executable B body reaches generated helper and exact retained vault");
            check(Arrays.equals((byte[]) loader.loadClass(HELPER.replace('/', '.')).getMethod("ownBytes").invoke(null), members.get(HELPER + ".class")),
                "generated helper self-fingerprint reads its exact retained class bytes through Class resources");
            check(parent.loadClass(HELPER.replace('/', '.')).getMethod("value").invoke(null).equals("parent helper"),
                "parent poison helper exists but is not used by retained B");
            check(loader.loadClass(NEIGHBOR.replace('/', '.')) == parent.loadClass(NEIGHBOR.replace('/', '.')),
                "same-package absent helper neighbor stays parent-owned");
            check(loader.loadClass(String.class.getName()) == String.class && loader.loadClass(BetaDeliveryClient.class.getName()) == BetaDeliveryClient.class,
                "JDK and shared delivery ABI retain parent identities");
            String missing = VerifiedBootstrapLoader.PREFIX + "Missing";
            check(parent.loadClass(missing).getClassLoader() == parent, "parent fixed-package poison class exists");
            expect(ClassNotFoundException.class, () -> loader.loadClass(missing), "missing fixed B class must not fall back");
            check(Arrays.equals(read(loader.getResourceAsStream(VAULT)), PLAINTEXT), "vault stream overrides parent exact resource");
            retainedUrl = loader.getResource(VAULT);
            check(retainedUrl != null && Arrays.equals(read(retainedUrl.openStream()), PLAINTEXT), "vault URL resolves retained bytes");
            List<URL> resources = Collections.list(loader.getResources(VAULT));
            check(resources.size() == 1 && Arrays.equals(read(resources.getFirst().openStream()), PLAINTEXT), "owned resource enumeration excludes parent alternatives");
            check(Arrays.equals(read(loader.getResourceAsStream(HELPER + ".class")), members.get(HELPER + ".class")),
                "helper class resource belongs to exact retained entry");
            String neighbor = "META-INF/qN4z/Vf8wK/Unowned.xQ";
            check(Arrays.equals(read(loader.getResourceAsStream(neighbor)), bytes("parent resource"))
                && Collections.list(loader.getResources(neighbor)).size() == 2,
                "unretained resource within helper package keeps ordinary parent lookup");
            check(Arrays.equals(read(loader.getResourceAsStream(VerifiedBootstrapLoader.LAYOUT)), members.get(VerifiedBootstrapLoader.LAYOUT)),
                "retained runtime mapping overrides parent metadata");
            String missingResource = VerifiedBootstrapLoader.PREFIX.replace('.', '/') + "Absent.random";
            check(loader.getResourceAsStream(missingResource) == null && loader.getResource(missingResource) == null
                && !loader.getResources(missingResource).hasMoreElements(), "missing fixed-package resources never fall back");
            reflectAbi(loader, entry);
        } finally { loader.close(); }
        loader.close();
        expect(ClassNotFoundException.class, () -> loader.loadClass(HELPER.replace('/', '.')), "retired helper never resolves previously loaded or parent class");
        expect(ClassNotFoundException.class, () -> loader.loadClass(ENTRY.replace('/', '.')), "retired fixed entry is revoked");
        check(loader.getResourceAsStream(VAULT) == null && loader.getResource(VAULT) == null && !loader.getResources(VAULT).hasMoreElements(),
            "retired vault names do not fall back to parent");
        expect(IOException.class, () -> read(retainedUrl.openStream()), "old retained URL is revoked after close");
        check(loader.getResource(VerifiedBootstrapLoader.LAYOUT) == null && loader.getResourceAsStream(VerifiedBootstrapLoader.LAYOUT) == null,
            "retired mandatory runtime layout remains unavailable");
        check(loader.loadClass(NEIGHBOR.replace('/', '.')) == parent.loadClass(NEIGHBOR.replace('/', '.')) && Arrays.equals(snapshot, archive),
            "close preserves unrelated parent delegation and caller input bytes");
    }

    private static void reflectAbi(VerifiedBootstrapLoader loader, Class<?> entry) throws Exception {
        Class<?> capability = loader.loadClass(CAPABILITY.replace('/', '.'));
        check(capability.isInterface() && capability.getMethod("verifyReadyForPayload").getReturnType() == void.class
            && capability.getMethod("runtimeParent").getReturnType() == ClassLoader.class, "fixed PreparedGame reflective names and descriptors survive");
        int[] checksCalled = {0};
        Object game = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {capability}, (proxy, method, arguments) -> {
            if (method.getName().equals("verifyReadyForPayload")) { checksCalled[0]++; return null; }
            if (method.getName().equals("runtimeParent")) return loader.getParent();
            throw new AssertionError("unexpected capability call");
        });
        Object session = entry.getMethod("load", BetaDeliveryClient.MemoryRelease.class, capability).invoke(null, null, game);
        check(checksCalled[0] == 1 && session.getClass().getClassLoader() == loader, "A-style reflective load crosses unchanged shared/capability ABI");
        for (String name : List.of("ready", "stopped", "stop")) {
            check(session.getClass().getMethod(name).invoke(session) instanceof CompletionStage<?>, "A-style lifecycle reflection succeeds: " + name);
        }
    }

    private static void rejected() throws Exception {
        for (String missing : List.of("META-INF/MANIFEST.MF", VerifiedBootstrapLoader.LAYOUT, ENTRY + ".class", CAPABILITY + ".class")) {
            Map<String, byte[]> entries = members(); entries.remove(missing); reject(entries, "mandatory fixed entry missing: " + missing);
        }
        Map<String, byte[]> wrongRole = members(); wrongRole.put("META-INF/MANIFEST.MF", bytes("Manifest-Version: 1.0\r\nCeleste-Beta-Bootstrap: A\r\n\r\n"));
        reject(wrongRole, "A role cannot masquerade as internal B");
        for (String name : List.of("java/lang/Helper", "javax/crypto/Helper", "jdk/internal/Helper", "sun/misc/Helper", "com/sun/jna/Helper",
            "com/oracle/Helper", "org/w3c/dom/Node", "org/xml/sax/Helper", "org/ietf/jgss/Helper", "org/jcp/xml/Helper", "org/omg/Helper", "netscape/javascript/Helper",
            "net/minecraft/Helper", "net/fabricmc/Helper", "org/spongepowered/asm/Helper", "com/llamalad7/mixinextras/Helper",
            "org/lwjgl/Helper", "org/joml/Helper", "org/slf4j/Helper", "org/jetbrains/Helper", "com/mojang/Helper",
            "com/google/gson/Helper", "org/objectweb/asm/Helper", "io/netty/Helper", "it/unimi/Helper", "org/apache/Helper", "oshi/Helper",
            "dev/redstones/mediaplayerinfo/Helper", "dev/celestebeta/host/Helper", "dev/celestebeta/bootstrap/external/Helper",
            "dev/c4lam/celeste/beta/delivery/Helper", "dev/c4lam/celeste/beta/memory/Helper", "dev/c4lam/celeste/Payload")) {
            Map<String, byte[]> entries = members(); entries.put(name + ".class", constant(name, "forbidden")); reject(entries, "parent namespace class rejected: " + name);
        }
        for (String name : List.of("Helper.class", "qN4z/7Invalid.class", "../escape.class", "./relative.class", "/absolute.class", "x//duplicate.class",
            "META-INF/fabric.mod.json", "fabric.mod.json", "celeste.mixins.json", "settings.json", "META-INF/services/qN4z/Vault.data",
            "META-INF/versions/qN4z/Vault.data", "META-INF/native/qN4z/Vault.data", "META-INF/fabric/qN4z/Vault.data",
            "META-INF/mixin/qN4z/Vault.data", "META-INF/celeste-beta/other.json", "META-INF/qN4z/Vault.DLL",
            "META-INF/qN4z/Vault.so", "META-INF/qN4z/Vault.dylib", "META-INF/qN4z/Vault.jnilib", "META-INF/qN4z/Vault.RSA",
            "META-INF/qN4z/Vault.SF", "META-INF/com/google/gson/Vault.data", "qN4z/Vault.random", "META-INF/Vault.random")) {
            Map<String, byte[]> entries = members(); entries.put(name, new byte[] {1}); reject(entries, "unsafe or unapproved class/resource rejected: " + name);
        }
        for (byte[] content : List.of(new byte[] {'M', 'Z', 0, 0}, hex(0x7f454c46), hex(0xfeedfacf), hex(0xcffaedfe), hex(0xcafebabe))) {
            Map<String, byte[]> entries = members(); entries.put(VAULT, content); reject(entries, "native magic rejected even under signed vault-shaped filename");
        }
        Map<String, byte[]> mismatch = members(); mismatch.put(HELPER + ".class", constant("different/package/Helper", "wrong"));
        reject(mismatch, "helper class declaration must match archive path");
        Map<String, byte[]> malformed = members(); malformed.put(HELPER + ".class", new byte[] {1, 2, 3}); reject(malformed, "malformed helper class rejected before loading");
        Map<String, byte[]> newer = members(); byte[] version = newer.get(HELPER + ".class").clone(); version[7] = 66; newer.put(HELPER + ".class", version);
        reject(newer, "helper newer than Java 21 rejected");
        Map<String, byte[]> directory = members(); directory.put("empty/", new byte[] {1}); reject(directory, "nonempty directory rejected");
        byte[] valid = jar(members(), true);
        expect(IOException.class, () -> new VerifiedBootstrapLoader(valid, "0".repeat(64), new Parent()), "outer signed digest remains mandatory");
        Map<String, byte[]> two = members(); two.put("META-INF/qN4z/Vf8wK/K2dV.xR9e", bytes("second"));
        byte[] duplicate = jar(two, true); replace(duplicate, "META-INF/qN4z/Vf8wK/K2dV.xR9e", VAULT);
        rejectBytes(duplicate, "duplicate local/central helper resource names rejected with matching outer hash");
        byte[] corrupt = valid.clone(); int offset = indexOf(corrupt, PLAINTEXT); check(offset >= 0, "stored vault located for CRC mutation"); corrupt[offset] ^= 1;
        rejectBytes(corrupt, "vault CRC corruption rejected with matching outer hash");
        int end = valid.length - 22, central = little(valid).getInt(end + 16);
        rejectBytes(Arrays.copyOf(valid, central), "local-only archive rejected");
        rejectBytes(Arrays.copyOf(valid, valid.length + 1), "unannounced trailing data rejected");
        byte[] disagree = valid.clone(); little(disagree).putInt(central + 24, little(disagree).getInt(central + 24) + 1);
        rejectBytes(disagree, "central/local size disagreement remains rejected");
    }

    private static void archiveBounds() throws Exception {
        rejectBytes(new byte[0], "empty B rejected"); rejectBytes(new byte[8 * 1024 * 1024 + 1], "compressed B bound preserved");
        Map<String, byte[]> entries = members(); entries.put(VAULT, new byte[2 * 1024 * 1024 + 1]); reject(entries, "helper vault per-entry bound preserved");
        entries = members(); for (int i = 0; i < 9; i++) entries.put("META-INF/qN4z/Large" + i + ".vault", new byte[2 * 1024 * 1024]);
        reject(entries, "aggregate helper resource expansion bound preserved");
        entries = members(); for (int i = 0; i < 513; i++) entries.put("dir" + i + "/", new byte[0]);
        reject(entries, "directory entries count toward B member bound");
    }

    private static Map<String, byte[]> members() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", bytes("Manifest-Version: 1.0\r\nCeleste-Beta-Bootstrap: B\r\n\r\n"));
        entries.put(VerifiedBootstrapLoader.LAYOUT, bytes("{\"schema\":\"fixture-retained-layout\"}"));
        entries.put(ENTRY + ".class", entry()); entries.put(CAPABILITY + ".class", capability());
        entries.put(SESSION + ".class", session()); entries.put(HELPER + ".class", helper()); entries.put(VAULT, PLAINTEXT.clone());
        return entries;
    }

    private static byte[] entry() {
        ClassWriter writer = base(ENTRY);
        MethodVisitor value = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "()Ljava/lang/String;", null, new String[] {"java/io/IOException"});
        value.visitCode(); value.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "value", "()Ljava/lang/String;", false); value.visitInsn(Opcodes.ARETURN); value.visitMaxs(1, 0); value.visitEnd();
        MethodVisitor load = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "load",
            "(Ldev/c4lam/celeste/beta/delivery/BetaDeliveryClient$MemoryRelease;L" + CAPABILITY + ";)L" + SESSION + ";", null, new String[] {"java/lang/Exception"});
        load.visitCode(); load.visitVarInsn(Opcodes.ALOAD, 1); load.visitMethodInsn(Opcodes.INVOKEINTERFACE, CAPABILITY, "verifyReadyForPayload", "()V", true);
        load.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "value", "()Ljava/lang/String;", false); load.visitInsn(Opcodes.POP);
        load.visitTypeInsn(Opcodes.NEW, SESSION); load.visitInsn(Opcodes.DUP); load.visitMethodInsn(Opcodes.INVOKESPECIAL, SESSION, "<init>", "()V", false);
        load.visitInsn(Opcodes.ARETURN); load.visitMaxs(2, 2); load.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    private static byte[] capability() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, CAPABILITY, null, "java/lang/Object", null);
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "verifyReadyForPayload", "()V", null, new String[] {"java/lang/Exception"}).visitEnd();
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "runtimeParent", "()Ljava/lang/ClassLoader;", null, null).visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    private static byte[] session() {
        ClassWriter writer = base(SESSION);
        for (String name : List.of("ready", "stopped", "stop")) {
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, "()Ljava/util/concurrent/CompletionStage;", null, null);
            method.visitCode(); method.visitInsn(Opcodes.ACONST_NULL);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/concurrent/CompletableFuture", "completedFuture", "(Ljava/lang/Object;)Ljava/util/concurrent/CompletableFuture;", false);
            method.visitInsn(Opcodes.ARETURN); method.visitMaxs(1, 1); method.visitEnd();
        }
        writer.visitEnd(); return writer.toByteArray();
    }
    private static byte[] helper() {
        ClassWriter writer = base(HELPER);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "()Ljava/lang/String;", null, new String[] {"java/io/IOException"});
        method.visitCode(); method.visitLdcInsn(Type.getObjectType(HELPER)); method.visitLdcInsn("/" + VAULT);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getResourceAsStream", "(Ljava/lang/String;)Ljava/io/InputStream;", false);
        method.visitVarInsn(Opcodes.ASTORE, 0); method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/InputStream", "readAllBytes", "()[B", false); method.visitVarInsn(Opcodes.ASTORE, 1);
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/InputStream", "close", "()V", false);
        method.visitTypeInsn(Opcodes.NEW, "java/lang/String"); method.visitInsn(Opcodes.DUP); method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitFieldInsn(Opcodes.GETSTATIC, "java/nio/charset/StandardCharsets", "UTF_8", "Ljava/nio/charset/Charset;");
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([BLjava/nio/charset/Charset;)V", false);
        method.visitInsn(Opcodes.ARETURN); method.visitMaxs(4, 2); method.visitEnd();
        MethodVisitor own = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "ownBytes", "()[B", null, new String[] {"java/io/IOException"});
        own.visitCode(); own.visitLdcInsn(Type.getObjectType(HELPER)); own.visitLdcInsn("P7xDecode.class");
        own.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getResourceAsStream", "(Ljava/lang/String;)Ljava/io/InputStream;", false);
        own.visitVarInsn(Opcodes.ASTORE, 0); own.visitVarInsn(Opcodes.ALOAD, 0);
        own.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/InputStream", "readAllBytes", "()[B", false); own.visitVarInsn(Opcodes.ASTORE, 1);
        own.visitVarInsn(Opcodes.ALOAD, 0); own.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/InputStream", "close", "()V", false);
        own.visitVarInsn(Opcodes.ALOAD, 1); own.visitInsn(Opcodes.ARETURN); own.visitMaxs(2, 2); own.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    private static byte[] constant(String owner, String value) {
        ClassWriter writer = base(owner); MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "()Ljava/lang/String;", null, null);
        method.visitCode(); method.visitLdcInsn(value); method.visitInsn(Opcodes.ARETURN); method.visitMaxs(1, 0); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    private static ClassWriter base(String owner) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null); init.visitCode(); init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false); init.visitInsn(Opcodes.RETURN); init.visitMaxs(1, 1); init.visitEnd(); return writer;
    }

    private static final class Parent extends ClassLoader {
        private final Map<String, byte[]> definitions = new HashMap<>();
        Parent() {
            super(BootstrapHelperCompatibilityCheck.class.getClassLoader());
            definitions.put(HELPER.replace('/', '.'), constant(HELPER, "parent helper"));
            definitions.put(NEIGHBOR.replace('/', '.'), constant(NEIGHBOR, "parent neighbor"));
            definitions.put(VerifiedBootstrapLoader.PREFIX + "Missing", constant(VerifiedBootstrapLoader.PREFIX.replace('.', '/') + "Missing", "parent fixed poison"));
        }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] bytes = definitions.get(name); if (bytes == null) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) { Class<?> type = findLoadedClass(name); if (type == null) type = defineClass(name, bytes, 0, bytes.length); if (resolve) resolveClass(type); return type; }
        }
        @Override public InputStream getResourceAsStream(String name) { return new ByteArrayInputStream(bytes("parent resource")); }
        @Override public URL getResource(String name) {
            try {
                return URL.of(java.net.URI.create("parent-memory:/" + name), new URLStreamHandler() {
                    @Override protected URLConnection openConnection(URL url) {
                        return new URLConnection(url) {
                            @Override public void connect() { connected = true; }
                            @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes("parent resource")); }
                        };
                    }
                });
            } catch (java.net.MalformedURLException failure) { throw new AssertionError(failure); }
        }
        @Override public Enumeration<URL> getResources(String name) { return Collections.enumeration(List.of(getResource(name), getResource(name))); }
    }

    private static byte[] jar(Map<String, byte[]> entries, boolean stored) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes)) {
            for (var entry : entries.entrySet()) {
                JarEntry member = new JarEntry(entry.getKey());
                if (stored) { CRC32 crc = new CRC32(); crc.update(entry.getValue()); member.setMethod(ZipEntry.STORED); member.setSize(entry.getValue().length); member.setCrc(crc.getValue()); }
                output.putNextEntry(member); output.write(entry.getValue()); output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static byte[] hex(int value) { return ByteBuffer.allocate(4).putInt(value).array(); }
    private static ByteBuffer little(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    private static byte[] read(InputStream input) throws IOException { if (input == null) throw new AssertionError("missing fixture resource"); try (input) { return input.readAllBytes(); } }
    private static int indexOf(byte[] haystack, byte[] needle) {
        outer: for (int i = 0; i <= haystack.length - needle.length; i++) { for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer; return i; } return -1;
    }
    private static void replace(byte[] data, String before, String after) {
        byte[] old = bytes(before), replacement = bytes(after); if (old.length != replacement.length) throw new AssertionError("fixture name lengths differ");
        int count = 0;
        for (int offset; (offset = indexOf(data, old)) >= 0;) { System.arraycopy(replacement, 0, data, offset, replacement.length); count++; }
        check(count == 2, "duplicate fixture updates both local and central names");
    }
    private static void reject(Map<String, byte[]> entries, String message) throws Exception { rejectBytes(jar(entries, false), message); }
    private static void rejectBytes(byte[] archive, String message) {
        expect(IOException.class, () -> { try (VerifiedBootstrapLoader loader = new VerifiedBootstrapLoader(archive, VerifiedBootstrapLoader.hash(archive), new Parent())) { loader.getResource(VerifiedBootstrapLoader.LAYOUT); } }, message);
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void expect(Class<? extends Throwable> type, Throwing action, String message) {
        checks++; try { action.run(); } catch (Throwable failure) { if (type.isInstance(failure)) return; throw new AssertionError(message, failure); } throw new AssertionError(message + ": no exception");
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
