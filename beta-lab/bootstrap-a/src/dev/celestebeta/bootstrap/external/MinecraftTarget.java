package dev.celestebeta.bootstrap.external;

import dev.celestebeta.host.instrument.CombinedHooksTransformer;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Verification of the explicitly selected, already-running supported Minecraft instance. */
record MinecraftTarget(Class<?> client, ClassLoader loader, List<Class<?>> targets) {
    static java.util.Map<String, String> describe(Instrumentation instrumentation) throws Exception {
        Class<?>[] loaded = instrumentation.getAllLoadedClasses();
        List<Class<?>> clients = Arrays.stream(loaded)
            .filter(type -> type.getName().equals("net.minecraft.class_310") || type.getName().equals("net.minecraft.client.MinecraftClient")).toList();
        if (clients.size() != 1) throw new IOException("Selected process does not contain one running Minecraft client");
        Class<?> client = clients.getFirst();
        boolean intermediary = client.getName().equals("net.minecraft.class_310");
        Object instance = client.getMethod(intermediary ? "method_1551" : "getInstance").invoke(null);
        Object directory = client.getField(intermediary ? "field_1697" : "runDirectory").get(instance);
        if (!(directory instanceof java.io.File gameDirectory)) throw new IOException("Minecraft game directory has an unexpected type");
        Class<?> fabric = Class.forName("net.fabricmc.loader.api.FabricLoader", false, client.getClassLoader());
        boolean celeste = Arrays.stream(client.getDeclaredMethods()).anyMatch(method -> method.getName().startsWith("celeste$"));
        ProcessHandle self = ProcessHandle.current();
        return java.util.Map.of("phase", "inspected", "game_directory", gameDirectory.toPath().toRealPath().toString(),
            "minecraft", version(fabric, client.getClassLoader(), "minecraft"), "celeste_loaded", Boolean.toString(celeste),
            "pid", Long.toString(self.pid()), "started_at", Long.toString(self.info().startInstant().orElseThrow().toEpochMilli()));
    }

    static MinecraftTarget inspect(Instrumentation instrumentation) throws Exception {
        if (Runtime.version().feature() != 21 || !instrumentation.isRetransformClassesSupported()) {
            throw new IOException("The beta requires Java 21 with class retransformation");
        }
        Class<?>[] loaded = instrumentation.getAllLoadedClasses();
        List<Class<?>> clients = Arrays.stream(loaded).filter(type -> type.getName().equals("net.minecraft.class_310")
            || type.getName().equals("net.minecraft.client.MinecraftClient")).toList();
        if (clients.size() != 1 || clients.getFirst().getClassLoader() == null) throw new IOException("Select one running Minecraft client");
        Class<?> client = clients.getFirst();
        ClassLoader loader = client.getClassLoader();
        Class<?> fabric = Class.forName("net.fabricmc.loader.api.FabricLoader", false, loader);
        if (!version(fabric, loader, "minecraft").equals("1.21.11")) throw new IOException("The beta currently supports Minecraft 1.21.11");
        requireInteractionApi(fabric);
        // The current startup distribution keeps these accessor names even when
        // its ordinary handler names are obfuscated. Never install a second runtime.
        for (Class<?> type : loaded) {
            if (type.getClassLoader() != loader) continue;
            if (type.getName().equals("dev.c4lam.celeste.CelesteClient")) throw duplicate();
        }
        for (var method : client.getDeclaredMethods()) if (method.getName().startsWith("celeste$")) throw duplicate();
        boolean intermediary = client.getName().equals("net.minecraft.class_310");
        List<Class<?>> targets = new ArrayList<>();
        for (String name : CombinedHooksTransformer.targets(intermediary)) {
            // Resolve without class initialization before any hook is installed;
            // this also observes Fabric's normal transformation/delegation path.
            Class<?> type = Class.forName(name.replace('/', '.'), false, loader);
            if (type.getClassLoader() != loader || !instrumentation.isModifiableClass(type)) {
                throw new IOException("A required Minecraft target is unavailable: " + name);
            }
            for (var method : type.getDeclaredMethods()) if (method.getName().startsWith("celeste$")) throw duplicate();
            targets.add(type);
        }
        return new MinecraftTarget(client, loader, List.copyOf(targets));
    }

    /** Check the loaded component, including nested Fabric API jars, without version/hash pinning. */
    static void requireInteractionApi(Class<?> fabric) throws Exception {
        Object api = fabric.getMethod("getInstance").invoke(null);
        Object result = fabric.getMethod("getModContainer", String.class)
            .invoke(api, "fabric-events-interaction-v0");
        if (!(result instanceof Optional<?> optional) || optional.isEmpty()) {
            throw new IOException("Fabric API is required. Enable it for this Minecraft instance and restart the game.");
        }
    }

    private static IOException duplicate() {
        return new IOException("Celeste is already loaded in this Minecraft process. Use a clean instance for the late-load beta.");
    }

    private static String version(Class<?> fabric, ClassLoader loader, String id) throws Exception {
        Object api = fabric.getMethod("getInstance").invoke(null);
        Object result = fabric.getMethod("getModContainer", String.class).invoke(api, id);
        if (!(result instanceof Optional<?> optional) || optional.isEmpty()) throw new IOException("Required Fabric component is absent: " + id);
        Object metadata = Class.forName("net.fabricmc.loader.api.ModContainer", false, loader)
            .getMethod("getMetadata").invoke(optional.get());
        Object version = Class.forName("net.fabricmc.loader.api.metadata.ModMetadata", false, loader).getMethod("getVersion").invoke(metadata);
        return (String) Class.forName("net.fabricmc.loader.api.Version", false, loader).getMethod("getFriendlyString").invoke(version);
    }
}
