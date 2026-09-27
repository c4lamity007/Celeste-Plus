package dev.celestebeta.bootstrap.external;

import dev.c4lam.celeste.beta.memory.TwoPartMemoryClassLoader;
import java.net.URL;
import java.net.URLClassLoader;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Verify exact packaged class declarations without initializing payload or game classes. */
public final class ProtectedPayloadClassCheck {
    private ProtectedPayloadClassCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Expected package, two hashes, parent classpath");
        Path root = Path.of(args[0]).toRealPath();
        List<URL> parents = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(args[3]))) {
            if (!line.isBlank()) parents.add(Path.of(line).toRealPath().toUri().toURL());
        }
        for (String line : Files.readAllLines(root.resolve("host-support.tsv"))) {
            if (!line.isBlank()) parents.add(root.resolve("host-support").resolve(line.split("\t", -1)[0]).toRealPath().toUri().toURL());
        }
        try (URLClassLoader parent = new URLClassLoader(parents.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
             TwoPartMemoryClassLoader payload = TwoPartMemoryClassLoader.open(
                 Files.readAllBytes(root.resolve("payload-a.bin")), args[1],
                 Files.readAllBytes(root.resolve("payload-b.bin")), args[2],
                 new TwoPartMemoryClassLoader.Namespaces(lines(root.resolve("class-prefixes.txt")), lines(root.resolve("resource-prefixes.txt"))),
                 new TwoPartMemoryClassLoader.Limits(64 * 1024 * 1024, 65536, 8 * 1024 * 1024, 128L * 1024 * 1024), parent)) {
            Set<String> names = new HashSet<>();
            byte[][] authentication = {null};
            payload.visitRetainedParts(part -> part.forEach((name, bytes) -> {
                if (name.endsWith(".class")) names.add(name.substring(0, name.length() - 6).replace('/', '.'));
                if (name.equals("dev/c4lam/celeste/auth/AuthServiceClient.class")) authentication[0] = bytes;
            }));
            if (names.isEmpty()) throw new AssertionError("No actual payload classes retained");
            verifyProcessIdentityBoundary(root, authentication[0], parent);
            for (String name : names.stream().sorted().toList()) {
                Class<?> type = Class.forName(name, false, payload);
                if (type.getClassLoader() != payload) throw new AssertionError("Payload class escaped its retained loader: " + name);
                type.getDeclaredConstructors();
                type.getDeclaredMethods();
                type.getDeclaredFields();
            }
            System.out.println("PASS actual protected payload declarations: " + names.size()
                + " classes with -Xverify:all; canonical process identity linkage/body checked; no payload initialization, authentication or game startup");
        }
    }

    private static void verifyProcessIdentityBoundary(Path root, byte[] authentication, ClassLoader parent) throws Exception {
        if (authentication == null) throw new AssertionError("Actual runtime authentication class is missing");
        Class<?> bridge = Class.forName("dev.celestebeta.host.ResourceReloadBridge", false, parent);
        if (bridge.getClassLoader() != parent || !Files.isSameFile(root.resolve("host-support/celeste-beta-bridges.jar"),
            Path.of(bridge.getProtectionDomain().getCodeSource().getLocation().toURI()))) {
            throw new AssertionError("Process identity must resolve from the exact packaged canonical bridge");
        }
        var identity = bridge.getMethod("processSessionId");
        if (!Modifier.isStatic(identity.getModifiers()) || identity.getReturnType() != String.class) {
            throw new AssertionError("Canonical process identity ABI changed");
        }
        int[] calls = {0};
        new ClassReader(authentication).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String member,
                                                          String desc, boolean isInterface) {
                        if (owner.equals("dev/celestebeta/host/ResourceReloadBridge") && member.equals("processSessionId")) {
                            if (opcode != Opcodes.INVOKESTATIC || isInterface || !desc.equals(Type.getMethodDescriptor(identity))) {
                                throw new AssertionError("Actual runtime process identity call differs from the packaged bridge ABI");
                            }
                            calls[0]++;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (calls[0] == 0) throw new AssertionError("Runtime does not use the canonical process identity bridge");
        Object first = identity.invoke(null);
        if (!(first instanceof String value) || !value.matches("[A-Za-z0-9_-]{32}") || !first.equals(identity.invoke(null))) {
            throw new AssertionError("Canonical identity is not stable or is malformed");
        }
    }

    private static Set<String> lines(Path path) throws Exception {
        Set<String> result = new HashSet<>(Files.readAllLines(path));
        result.removeIf(String::isBlank);
        return result;
    }
}
