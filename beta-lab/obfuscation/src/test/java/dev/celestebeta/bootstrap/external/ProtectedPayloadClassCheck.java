package dev.celestebeta.bootstrap.external;

import dev.c4lam.celeste.beta.memory.TwoPartMemoryClassLoader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
            payload.visitRetainedParts(part -> part.forEach((name, bytes) -> {
                if (name.endsWith(".class")) names.add(name.substring(0, name.length() - 6).replace('/', '.'));
            }));
            if (names.isEmpty()) throw new AssertionError("No actual payload classes retained");
            for (String name : names.stream().sorted().toList()) {
                Class<?> type = Class.forName(name, false, payload);
                if (type.getClassLoader() != payload) throw new AssertionError("Payload class escaped its retained loader: " + name);
                type.getDeclaredConstructors();
                type.getDeclaredMethods();
                type.getDeclaredFields();
            }
            System.out.println("PASS actual protected payload declarations: " + names.size()
                + " classes with -Xverify:all; no class initialization, authentication or game startup");
        }
    }

    private static Set<String> lines(Path path) throws Exception {
        Set<String> result = new HashSet<>(Files.readAllLines(path));
        result.removeIf(String::isBlank);
        return result;
    }
}
