package dev.celestebeta.bootstrap.external;

import dev.c4lam.celeste.beta.delivery.BetaDeliveryClient;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Actual protected archives: A linkage/missing-install rejection and pre-activation B only. */
public final class ProtectedBootstrapArtifactCheck {
    private static int checks;
    private ProtectedBootstrapArtifactCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected merged A and protected B paths");
        Path a = Path.of(args[0]).toRealPath(), b = Path.of(args[1]).toRealPath();
        try (URLClassLoader host = new URLClassLoader("celeste-protected-host-check", new java.net.URL[] {a.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            verifyAuthenticationBoundary(a, host);
            Class<?> entry = Class.forName("dev.celestebeta.bootstrap.external.BootstrapHost", true, host);
            check(entry.getClassLoader() == host, "A Host is owned by its isolated archive loader");
            check(Files.isSameFile(a, Path.of(entry.getProtectionDomain().getCodeSource().getLocation().toURI())), "A CodeSource remains its exact merged archive");
            Object response = entry.getMethod("command", Instrumentation.class, String.class, String.class)
                .invoke(null, null, "{\"action\":\"status\"}", "0".repeat(64));
            check("{\"phase\":\"inactive\"}".equals(response), "A obfuscated implementation returns inactive status without attachment/delivery");
            Class<?> shim = Class.forName("dev.celestebeta.attach.AgentMain", false, host);
            check(Modifier.isStatic(shim.getMethod("agentmain", String.class, Instrumentation.class).getModifiers()), "Stable AgentMain entry remains present");
            Class<?> cli = Class.forName("dev.celestebeta.attach.AttachCli", false, host);
            check(Modifier.isStatic(cli.getMethod("main", String[].class).getModifiers()), "Stable CLI entry remains present");
            byte[] bytes = Files.readAllBytes(b);
            try (VerifiedBootstrapLoader internal = new VerifiedBootstrapLoader(bytes, VerifiedBootstrapLoader.hash(bytes), host);
                 JarFile archive = new JarFile(b.toFile())) {
                int classes = 0, entries = 0; long expanded = 0;
                var members = archive.entries();
                while (members.hasMoreElements()) {
                    var member = members.nextElement(); entries++;
                    if (member.isDirectory()) continue;
                    expanded += member.getSize();
                    if (!member.getName().endsWith(".class")) continue;
                    Class<?> type = internal.loadClass(member.getName().substring(0, member.getName().length() - 6).replace('/', '.'));
                    check(type.getClassLoader() == internal, "Every actual B class/helper is retained-owned");
                    type.getDeclaredMethods(); type.getDeclaredFields(); classes++;
                }
                Class<?> bootstrap = Class.forName(VerifiedBootstrapLoader.PREFIX + "MemoryPayloadBootstrap", true, internal);
                Class<?> capability = internal.loadClass(VerifiedBootstrapLoader.PREFIX + "MemoryPayloadBootstrap$PreparedGame");
                check(capability.isInterface() && capability.getMethod("verifyReadyForPayload").getReturnType() == void.class
                    && capability.getMethod("runtimeParent").getReturnType() == ClassLoader.class, "Fixed PreparedGame capability ABI survives");
                Class<?> release = Class.forName(BetaDeliveryClient.MemoryRelease.class.getName(), false, host);
                var load = bootstrap.getMethod("load", release, capability);
                check(Modifier.isStatic(load.getModifiers()), "Fixed B load entry is public/static");
                Class<?> session = load.getReturnType();
                for (String method : new String[] {"ready", "stopped", "stop"}) {
                    check(CompletionStage.class.isAssignableFrom(session.getMethod(method).getReturnType()), "Actual B Session public lifecycle ABI survives");
                }
                check(Map.class.isAssignableFrom(session.getMethod("status").getReturnType()), "Actual B Session status ABI survives");
                try {
                    // Actual layout parsing/string runtime executes, but the null
                    // release stops activation before PreparedGame, delivery or game work.
                    load.invoke(null, new Object[] {null, null});
                    throw new AssertionError("B unexpectedly accepted an absent verified release");
                } catch (InvocationTargetException failure) {
                    check(failure.getCause() instanceof NullPointerException && "release".equals(failure.getCause().getMessage()),
                        "Actual B decrypts/parses retained layout then rejects missing release before activation");
                }
                System.out.println("Protected B measured: compressed=" + bytes.length + ", expanded=" + expanded
                    + ", entries=" + entries + ", classes=" + classes);
            } finally { java.util.Arrays.fill(bytes, (byte) 0); }
        }
        System.out.println("PASS protected bootstrap artifacts: " + checks
            + " checks; A linkage/missing-install rejection and pre-activation B only, no game attachment, authentication or payload start");
    }

    private static void verifyAuthenticationBoundary(Path a, ClassLoader host) throws Exception {
        String signer = "dev/c4lam/celeste/beta/delivery/RuntimeInstallationSigner";
        String delivery = "dev/c4lam/celeste/beta/delivery/BetaDeliveryClient";
        try (JarFile archive = new JarFile(a.toFile())) {
            Map<String, Set<String>> declarations = Map.of(signer, methodDeclarations(archive, signer),
                delivery, methodDeclarations(archive, delivery));
            int[] calls = {0, 0};
            new ClassReader(classBytes(archive, "dev/celestebeta/bootstrap/external/BootstrapHost"))
                .accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                               String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int opcode, String owner, String member,
                                                                  String desc, boolean isInterface) {
                                if ((owner.equals(signer) && member.equals("load"))
                                    || (owner.equals(delivery) && member.equals("<init>"))) {
                                    check(declarations.get(owner).contains(member + desc),
                                        "Actual A call descriptor must match its stable declaration: " + owner + "." + member + desc);
                                    calls[owner.equals(signer) ? 0 : 1]++;
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            check(calls[0] > 0 && calls[1] > 0, "Actual Host retains both production installation/delivery calls");
            // Resolving declarations catches renamed types crossing untouched class boundaries,
            // without initializing classes or reaching a real account, game or network endpoint.
            var members = archive.entries();
            while (members.hasMoreElements()) {
                String name = members.nextElement().getName();
                if (!name.endsWith(".class") || name.equals("module-info.class") || name.startsWith("META-INF/")) continue;
                Class<?> type = Class.forName(name.substring(0, name.length() - 6).replace('/', '.'), false, host);
                check(type.getClassLoader() == host, "Every actual A class/helper is archive-owned");
                type.getDeclaredMethods(); type.getDeclaredFields(); type.getDeclaredConstructors();
            }
        }
        for (String canonical : new String[] {"DpopProofSigner", "PortableCredentialProtection", "EnvironmentGate", "EnvironmentGate$Command"}) {
            Class<?> type = Class.forName("dev.c4lam.celeste.auth." + canonical, false, host);
            check(type.getClassLoader() == host, "Shared authentication class retains its canonical ABI: " + canonical);
        }
        Class<?> bridge = Class.forName(signer.replace('/', '.'), false, host);
        var load = bridge.getMethod("load", Path.class, ClassLoader.class, byte[].class, String.class, long.class);
        check(load.getReturnType() == Class.forName("dev.c4lam.celeste.auth.DpopProofSigner", false, host),
            "Installation bridge exposes the canonical signer capability");
        Path emptyGame = Files.createTempDirectory("celeste-protected-missing-install-");
        try {
            try {
                load.invoke(null, emptyGame, host, new byte[32], "synthetic-missing-install", 1L);
                throw new AssertionError("Installation bridge accepted an empty synthetic game directory");
            } catch (InvocationTargetException failure) {
                check(failure.getCause() instanceof GeneralSecurityException
                    && "The selected instance has no protected Celeste+ installation".equals(failure.getCause().getMessage()),
                    "Actual installation bridge executes and intentionally rejects a missing installation");
            }
        } finally { Files.delete(emptyGame); }
    }

    private static byte[] classBytes(JarFile archive, String name) throws Exception {
        var member = archive.getJarEntry(name + ".class");
        check(member != null, "Required A class remains present: " + name);
        try (var input = archive.getInputStream(member)) { return input.readAllBytes(); }
    }

    private static Set<String> methodDeclarations(JarFile archive, String owner) throws Exception {
        Set<String> methods = new HashSet<>();
        new ClassReader(classBytes(archive, owner)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                methods.add(name + descriptor);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return methods;
    }

    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
}
