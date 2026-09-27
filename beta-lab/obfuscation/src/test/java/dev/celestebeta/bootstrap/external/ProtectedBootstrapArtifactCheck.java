package dev.celestebeta.bootstrap.external;

import dev.c4lam.celeste.beta.delivery.BetaDeliveryClient;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.jar.JarFile;

/** Actual protected archives: inactive A status and pre-activation B validation only. */
public final class ProtectedBootstrapArtifactCheck {
    private static int checks;
    private ProtectedBootstrapArtifactCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected merged A and protected B paths");
        Path a = Path.of(args[0]).toRealPath(), b = Path.of(args[1]).toRealPath();
        try (URLClassLoader host = new URLClassLoader("celeste-protected-host-check", new java.net.URL[] {a.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
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
            + " checks; inactive A and pre-activation B only, no game attachment, authentication or payload start");
    }

    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
}
