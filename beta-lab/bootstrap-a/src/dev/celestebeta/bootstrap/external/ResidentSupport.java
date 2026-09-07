package dev.celestebeta.bootstrap.external;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.c4lam.celeste.beta.memory.TwoPartMemoryClassLoader;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

/** Exact signed support copies: standard URL/native loading, canonical JDK bridges. */
final class ResidentSupport {
    static final String CONTRACT = "META-INF/celeste-beta/host-support.json";
    private static final Set<String> CLASSES = Set.of("com.sun.jna.", "oshi.", "dev.redstones.mediaplayerinfo.");
    private static final Set<String> RESOURCES = Set.of("com/sun/jna/", "oshi/", "mediaplayerinfo/", "dev/redstones/mediaplayerinfo/");
    private final Path directory;
    private final Map<String, Path> libraries;
    private final List<String> bridgeNames;
    private final Map<String, String> libraryHashes;
    private boolean installed;
    private JarFile retainedBridge;
    private SupportLoader loader;

    private ResidentSupport(Path directory, Map<String, Path> libraries, List<String> bridgeNames, Map<String, String> libraryHashes) {
        this.directory = directory;
        this.libraries = Map.copyOf(libraries);
        this.bridgeNames = List.copyOf(bridgeNames);
        this.libraryHashes = Map.copyOf(libraryHashes);
    }

    static ResidentSupport prepare(TwoPartMemoryClassLoader retained, Path supportRoot,
                                   ClassLoader agentResources) throws IOException {
        return prepare(retained, supportRoot, agentResources, null);
    }

    static ResidentSupport prepare(TwoPartMemoryClassLoader retained, Path supportRoot,
                                   ClassLoader agentResources, ResidentSupport resident) throws IOException {
        Map<String, byte[]> wanted = new HashMap<>();
        byte[][] contract = new byte[1][];
        retained.visitRetainedParts(part -> {
            byte[] bytes = part.get(CONTRACT);
            if (bytes != null) {
                if (contract[0] != null || bytes.length > 65_536) throw new IOException("Invalid support contract");
                contract[0] = bytes.clone();
            }
        });
        if (contract[0] == null) throw new IOException("Retained support contract is absent");
        JsonObject value;
        try { value = JsonParser.parseString(new String(contract[0], java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (RuntimeException error) { throw new IOException("Invalid retained support contract", error); }
        if (!value.keySet().equals(Set.of("schema", "libraries", "bootstrap_classpath", "resident_url_classpath",
            "resident_owned_prefixes", "resident_resource_prefixes", "parent", "must_validate_from_retained_payload",
            "nested_jars_are_not_automatically_classpath_entries"))
            || !value.get("schema").getAsString().equals("celeste-beta-host-support-1")
            || !strings(value, "resident_owned_prefixes").equals(CLASSES)
            || !strings(value, "resident_resource_prefixes").equals(RESOURCES)
            || !strings(value, "bootstrap_classpath").equals(Set.of("celeste-beta-bridges.jar"))
            || !value.get("parent").getAsString().equals("actual verified Minecraft/Fabric game classloader")
            || !trueBoolean(value, "must_validate_from_retained_payload")
            || !trueBoolean(value, "nested_jars_are_not_automatically_classpath_entries")) {
            throw new IOException("Unsupported resident support contract");
        }
        Set<String> expectedResident = strings(value, "resident_url_classpath");
        Map<String, JsonObject> descriptors = new HashMap<>();
        try {
            for (var item : value.getAsJsonArray("libraries")) {
                JsonObject entry = item.getAsJsonObject();
                if (!entry.keySet().equals(Set.of("filename", "sha256", "size", "payload_backing", "bootstrap_entry"))) {
                    throw new IOException("Invalid support library fields");
                }
                String filename = entry.get("filename").getAsString();
                String backing = entry.get("payload_backing").getAsString();
                if (!filename.matches("[A-Za-z0-9_.+-]+\\.jar") || filename.contains("..")
                    || !entry.get("bootstrap_entry").getAsString().equals("META-INF/celeste-beta/host-jars/" + filename)
                    || descriptors.putIfAbsent(filename, entry) != null) throw new IOException("Invalid support library path");
                retained.visitRetainedParts(part -> {
                    byte[] bytes = part.get(backing);
                    if (bytes != null && wanted.putIfAbsent(filename, bytes.clone()) != null) throw new IOException("Duplicate support backing");
                });
                byte[] bytes = wanted.get(filename);
                if (bytes == null || bytes.length > 16 * 1024 * 1024 || bytes.length != exactSize(entry)
                    || !VerifiedBootstrapLoader.hash(bytes).equals(entry.get("sha256").getAsString())) {
                    throw new IOException("Retained support library digest differs");
                }
                try (InputStream input = agentResources.getResourceAsStream(entry.get("bootstrap_entry").getAsString())) {
                    if (input == null || !Arrays.equals(bytes, input.readNBytes(bytes.length + 1))) {
                        throw new IOException("Agent support library differs from retained payload");
                    }
                }
            }
            Set<String> actualResident = new HashSet<>(descriptors.keySet());
            if (!actualResident.remove("celeste-beta-bridges.jar") || !actualResident.equals(expectedResident) || actualResident.size() != 5) {
                throw new IOException("Resident support classpath differs from the release");
            }
            List<String> bridges = new ArrayList<>();
            try (java.util.zip.ZipInputStream jar = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(wanted.get("celeste-beta-bridges.jar")))) {
                for (var entry = jar.getNextEntry(); entry != null; entry = jar.getNextEntry()) {
                    String name = entry.getName();
                    if (entry.isDirectory() || name.equals("META-INF/MANIFEST.MF")) continue;
                    if (!name.startsWith("dev/celestebeta/host/") || name.contains("/instrument/") || !name.endsWith(".class")) {
                        throw new IOException("Canonical bridge archive contains other code");
                    }
                    bridges.add(name.substring(0, name.length() - 6).replace('/', '.'));
                }
            }
            if (!bridges.contains("dev.celestebeta.host.ResourceReloadBridge") || new HashSet<>(bridges).size() != bridges.size()) {
                throw new IOException("Invalid canonical bridge definitions");
            }
            Map<String, String> hashes = new HashMap<>();
            wanted.forEach((name, bytes) -> hashes.put(name, VerifiedBootstrapLoader.hash(bytes)));
            if (resident != null) {
                resident.requireCompatible(hashes, bridges);
                return resident;
            }
            Path root = supportRoot.toRealPath();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Support root must already be a directory");
            Path directory = Files.createTempDirectory(root, "celeste-beta-support-");
            Map<String, Path> paths = new HashMap<>();
            for (var entry : wanted.entrySet()) {
                Path file = directory.resolve(entry.getKey());
                Files.write(file, entry.getValue(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                if (!VerifiedBootstrapLoader.hash(Files.readAllBytes(file)).equals(descriptors.get(entry.getKey()).get("sha256").getAsString())) {
                    throw new IOException("Materialized support library digest differs");
                }
                paths.put(entry.getKey(), file);
            }
            return new ResidentSupport(directory, paths, bridges, hashes);
        } catch (IllegalStateException | IllegalArgumentException error) {
            throw new IOException("Invalid resident support metadata", error);
        } finally {
            wanted.values().forEach(bytes -> Arrays.fill(bytes, (byte) 0));
            Arrays.fill(contract[0], (byte) 0);
        }
    }

    synchronized void install(Instrumentation instrumentation, ClassLoader gameLoader, ClassLoader agentLoader) throws Exception {
        if (installed) {
            if (loader.getParent() != gameLoader) throw new IOException("Resident support belongs to another Minecraft loader; restart Minecraft");
            verifyFiles();
            verifyCanonicalBridges(gameLoader, agentLoader);
            return; // Never append bridges twice or create another JNI-owning loader.
        }
        if (retainedBridge != null || loader != null) throw new IOException("Resident support installation was incomplete; restart Minecraft");
        verifyFiles();
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (bridgeNames.contains(type.getName())) throw new IOException("A beta bridge is already resident; restart Minecraft before this beta");
        }
        retainedBridge = new JarFile(libraries.get("celeste-beta-bridges.jar").toFile());
        instrumentation.appendToBootstrapClassLoaderSearch(retainedBridge);
        // A bridge append cannot be removed by Java. Keep the file/JarFile alive even on later failure.
        verifyCanonicalBridges(gameLoader, agentLoader);
        List<URL> urls = new ArrayList<>();
        for (var entry : libraries.entrySet()) if (!entry.getKey().equals("celeste-beta-bridges.jar")) urls.add(entry.getValue().toUri().toURL());
        loader = new SupportLoader(urls.toArray(URL[]::new), gameLoader);
        for (String name : List.of("com.sun.jna.Native", "oshi.SystemInfo")) {
            if (Class.forName(name, false, loader).getClassLoader() != loader) throw new IOException("Resident dependency resolved outside the signed support loader");
        }
        installed = true;
    }

    private synchronized void requireCompatible(Map<String, String> hashes, List<String> bridges) throws IOException {
        if (!installed || !libraryHashes.equals(hashes) || !new HashSet<>(bridgeNames).equals(new HashSet<>(bridges))) {
            throw new IOException("The signed release requires different or incomplete resident support; restart Minecraft");
        }
        verifyFiles();
    }

    private void verifyFiles() throws IOException {
        for (var entry : libraries.entrySet()) {
            if (!Files.isRegularFile(entry.getValue(), LinkOption.NOFOLLOW_LINKS)
                || !VerifiedBootstrapLoader.hash(Files.readAllBytes(entry.getValue())).equals(libraryHashes.get(entry.getKey()))) {
                throw new IOException("Resident support bytes changed; restart Minecraft");
            }
        }
    }

    private void verifyCanonicalBridges(ClassLoader gameLoader, ClassLoader agentLoader) throws ClassNotFoundException, IOException {
        for (String name : bridgeNames) {
            Class<?> canonical = Class.forName(name, false, null);
            if (Class.forName(name, false, gameLoader) != canonical || Class.forName(name, false, agentLoader) != canonical) {
                throw new IOException("Minecraft cannot resolve canonical beta bridge " + name);
            }
        }
    }

    ClassLoader loader() { return java.util.Objects.requireNonNull(installed ? loader : null, "Support is not installed"); }
    Path directory() { return directory; }

    private static Set<String> strings(JsonObject object, String name) throws IOException {
        Set<String> values = new HashSet<>();
        for (var item : object.getAsJsonArray(name)) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString() || !values.add(item.getAsString())) {
                throw new IOException("Invalid support metadata list");
            }
        }
        return Set.copyOf(values);
    }

    private static boolean trueBoolean(JsonObject value, String key) {
        return value.get(key).isJsonPrimitive() && value.getAsJsonPrimitive(key).isBoolean() && value.get(key).getAsBoolean();
    }
    private static long exactSize(JsonObject value) throws IOException {
        try {
            if (!value.get("size").isJsonPrimitive() || !value.getAsJsonPrimitive("size").isNumber()
                || !value.get("size").toString().matches("[1-9][0-9]{0,8}")) throw new IOException("Support size must be an integer");
            long size = value.get("size").getAsBigDecimal().longValueExact();
            if (size <= 0 || size > 16 * 1024 * 1024) throw new IOException("Support size exceeds bounds");
            return size;
        } catch (ArithmeticException error) { throw new IOException("Support size must be an exact integer", error); }
    }

    private static final class SupportLoader extends URLClassLoader {
        SupportLoader(URL[] urls, ClassLoader parent) { super("celeste-beta-resident-support", urls, parent); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (CLASSES.stream().noneMatch(name::startsWith)) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null) type = findClass(name);
                if (resolve) resolveClass(type);
                return type;
            }
        }
        @Override public URL getResource(String name) {
            return RESOURCES.stream().anyMatch(name::startsWith) ? findResource(name) : super.getResource(name);
        }
        @Override public Enumeration<URL> getResources(String name) throws IOException {
            return RESOURCES.stream().anyMatch(name::startsWith) ? findResources(name) : super.getResources(name);
        }
    }
}
