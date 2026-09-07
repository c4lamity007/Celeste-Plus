package dev.c4lam.celeste.auth;

import dev.c4lam.celeste.beta.memory.TwoPartMemoryClassLoader;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Real packaged archive + real loader + real canonical-integrity adapter; no entrypoint invocation. */
public final class ActualPayloadCheck {
    private ActualPayloadCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length > 5 || Runtime.version().feature() != 21) throw new IllegalArgumentException("Expected package, two SHA-256 values, runtime digest, optional parent classpath file; Java 21 required");
        Path root = Path.of(args[0]).toRealPath();
        Set<String> classes = lines(root.resolve("class-prefixes.txt"));
        Set<String> resources = lines(root.resolve("resource-prefixes.txt"));
        byte[] first = Files.readAllBytes(root.resolve("payload-a.bin"));
        byte[] second = Files.readAllBytes(root.resolve("payload-b.bin"));
        AtomicInteger partCount = new AtomicInteger(), entryCount = new AtomicInteger(), applicationClasses = new AtomicInteger();
        Set<String> names = new HashSet<>();
        int supportCount = 0;
        int parentChecks = 0;
        TwoPartMemoryClassLoader loader = TwoPartMemoryClassLoader.open(first, args[1], second, args[2],
            new TwoPartMemoryClassLoader.Namespaces(classes, resources),
            new TwoPartMemoryClassLoader.Limits(64 * 1024 * 1024, 65536, 8 * 1024 * 1024, 128L * 1024 * 1024),
            ClassLoader.getPlatformClassLoader());
        try {
            loader.visitRetainedParts(part -> {
                partCount.incrementAndGet();
                part.forEach((name, data) -> {
                    if (!names.add(name)) throw new AssertionError("Duplicate actual retained entry " + name);
                    entryCount.incrementAndGet();
                    if (name.endsWith(".class")) applicationClasses.incrementAndGet();
                });
            });
            require(partCount.get() == 2, "Actual loader did not retain exactly two parts");
            ArtifactIntegrity.installVerifiedMemorySource(visitor -> loader.visitRetainedParts(visitor::accept));
            String digest = ArtifactIntegrity.digest();
            require(digest.equals(args[3]), "Actual production integrity digest disagrees with packaging");
            // Make sure original caller-buffer cleanup cannot change the retained payload.
            Arrays.fill(first, (byte) 0);
            Arrays.fill(second, (byte) 0);
            require(ArtifactIntegrity.digest().equals(digest), "Caller-buffer cleanup changed retained integrity");
            for (String row : Files.readAllLines(root.resolve("host-support.tsv"), StandardCharsets.UTF_8)) {
                if (row.isBlank()) continue;
                String[] fields = row.split("\t", -1);
                require(fields.length == 3, "Invalid support manifest row");
                byte[] host = Files.readAllBytes(root.resolve("host-support").resolve(fields[0]));
                require(hash(host).equals(fields[1]), "Resident support artifact changed: " + fields[0]);
                try (var stream = loader.getResourceAsStream(fields[2])) {
                    require(stream != null && Arrays.equals(host, stream.readAllBytes()), "Support no longer matches exact retained integrity bytes: " + fields[0]);
                }
                supportCount++;
            }
            require(names.stream().noneMatch(name -> name.startsWith("dev/celestebeta/host/") || name.startsWith("dev/redstones/mediaplayerinfo/")), "Parent-owned classes accidentally remain child-owned");
            require(names.contains("META-INF/celeste-beta/source-root/fabric.mod.json"), "Fabric descriptor provenance missing");
            if (args.length == 5) {
                List<URL> urls = new ArrayList<>();
                for (String path : Files.readAllLines(Path.of(args[4]), StandardCharsets.UTF_8)) {
                    if (!path.isBlank()) urls.add(Path.of(path).toRealPath().toUri().toURL());
                }
                try (URLClassLoader parent = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
                    List<String> missing = new ArrayList<>();
                    for (String name : lines(root.resolve("parent-classes.txt"))) {
                        try { Class.forName(name, false, parent); parentChecks++; }
                        catch (ClassNotFoundException | LinkageError failure) { missing.add(name + " (" + failure.getClass().getSimpleName() + ")"); }
                    }
                    require(missing.isEmpty(), "Parent classpath cannot resolve: " + missing);
                }
            }
        } finally {
            loader.close();
        }
        try {
            ArtifactIntegrity.digest();
            throw new AssertionError("Closed retained source unexpectedly fell back to disk");
        } catch (IOException expected) {
            // Real installed retained source must remain selected and fail after closure.
        }
        String report = "{\"schema\":\"celeste-beta-package-validation-1\",\"status\":\"passed\",\"runtime_sha256\":\"" + args[3]
            + "\",\"payload_a_sha256\":\"" + args[1] + "\",\"payload_b_sha256\":\"" + args[2]
            + "\",\"retained_parts\":" + partCount.get() + ",\"entries\":" + entryCount.get() + ",\"application_classes\":" + applicationClasses.get()
            + ",\"integrity_backed_host_libraries\":" + supportCount + ",\"parent_runtime_checked\":" + (args.length == 5)
            + ",\"parent_classes_checked\":" + parentChecks + ",\"game_tested\":false,\"entrypoint_executed\":false}\n";
        Files.writeString(root.resolve("java-validation.json"), report, StandardCharsets.UTF_8);
        System.out.println("PASS actual beta package: two retained parts, " + applicationClasses.get() + " application classes, "
            + supportCount + " exact integrity-backed support libraries; parent runtime checked=" + (args.length == 5));
    }

    private static Set<String> lines(Path file) throws IOException {
        return new HashSet<>(Files.readAllLines(file, StandardCharsets.UTF_8).stream().filter(value -> !value.isBlank()).toList());
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static void require(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
