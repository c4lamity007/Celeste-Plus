import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipFile;

/** Resolves actual independently protected module classes without initializing gameplay. */
public final class ModuleArtifactCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("classpath file, module JAR, factory class required");
        var urls = Files.readAllLines(Path.of(args[0])).stream().filter(line -> !line.isBlank())
            .map(line -> {
                try { return Path.of(line).toUri().toURL(); }
                catch (Exception error) { throw new IllegalArgumentException(error); }
            }).toArray(URL[]::new);
        Path artifact = Path.of(args[1]);
        Set<String> names = new LinkedHashSet<>();
        try (ZipFile archive = new ZipFile(artifact.toFile())) {
            archive.stream().filter(entry -> entry.getName().endsWith(".class")).forEach(entry -> {
                String name = entry.getName().substring(0, entry.getName().length() - 6).replace('/', '.');
                if (!names.add(name)) throw new IllegalArgumentException("Duplicate module class");
            });
        }
        if (names.isEmpty() || !names.contains(args[2])) throw new IllegalArgumentException("Missing factory");
        try (URLClassLoader parent = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
             URLClassLoader module = new URLClassLoader(new URL[]{artifact.toUri().toURL()}, parent) {
                 @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                     synchronized (getClassLoadingLock(name)) {
                         if (!names.contains(name)) return super.loadClass(name, resolve);
                         Class<?> value = findLoadedClass(name);
                         if (value == null) value = findClass(name);
                         if (resolve) resolveClass(value);
                         return value;
                     }
                 }
             }) {
            for (String name : names) {
                Class<?> type = Class.forName(name, false, module);
                if (type.getClassLoader() != module) throw new IllegalStateException("Module resolved from parent");
                type.getDeclaredConstructors();
                type.getDeclaredMethods();
                type.getDeclaredFields();
            }
            Class<?> entry = Class.forName(args[2], false, module);
            Class<?> api = Class.forName("dev.c4lam.celeste.module.delivery.ModuleEntrypoint", false, parent);
            if (!api.isAssignableFrom(entry) || !Modifier.isPublic(entry.getDeclaredConstructor().getModifiers()))
                throw new IllegalArgumentException("Factory must implement resident SPI and have a public no-arg constructor");
            System.out.println("PASS independently protected module linkage: " + names.size() + " classes; no initialization");
        }
    }
}
