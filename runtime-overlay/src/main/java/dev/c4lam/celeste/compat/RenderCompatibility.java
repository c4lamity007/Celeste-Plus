package dev.c4lam.celeste.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Optional Iris queries resolved once; absent render mods cost only a null check per pass. */
public final class RenderCompatibility {
    private static final Logger LOGGER = dev.c4lam.celeste.compat.RuntimeLogging.getLogger("Celeste/RenderCompatibility");
    private static final IrisQueries IRIS = discover();
    private static volatile boolean queryFailed;

    private record IrisQueries(MethodHandle shaderPack, MethodHandle shadowPass) { }

    private RenderCompatibility() { }

    /** Shadow maps must never receive screen overlays, x-ray geometry, or extracted player effects. */
    public static boolean isShadowPass() {
        return IRIS != null && query(IRIS.shadowPass());
    }

    /** Packs own their atmospheric/sky model; preserve that model instead of drawing a second sky. */
    public static boolean isShaderPackActive() {
        return IRIS != null && query(IRIS.shaderPack());
    }

    private static IrisQueries discover() {
        if (!FabricLoader.getInstance().isModLoaded("iris")) return null;
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi", true,
                RenderCompatibility.class.getClassLoader());
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Object instance = lookup.findStatic(api, "getInstance", MethodType.methodType(api)).invoke();
            return new IrisQueries(
                lookup.findVirtual(api, "isShaderPackInUse", MethodType.methodType(boolean.class)).bindTo(instance),
                lookup.findVirtual(api, "isRenderingShadowPass", MethodType.methodType(boolean.class)).bindTo(instance));
        } catch (Throwable failure) {
            if (failure instanceof Error fatal && !(failure instanceof LinkageError)) throw fatal;
            // Installed but incompatible Iris must not let overlays corrupt a shadow framebuffer.
            queryFailed = true;
            LOGGER.warn("Iris render API is unavailable; custom world overlays will wait for a compatible renderer", failure);
            MethodHandle unavailable = MethodHandles.constant(boolean.class, true);
            return new IrisQueries(unavailable, unavailable);
        }
    }

    private static boolean query(MethodHandle query) {
        if (queryFailed) return true;
        try {
            return (boolean) query.invokeExact();
        } catch (Throwable failure) {
            if (failure instanceof Error fatal && !(failure instanceof LinkageError)) throw fatal;
            queryFailed = true;
            LOGGER.warn("Iris render state query failed; custom world overlays are suspended", failure);
            return true;
        }
    }
}
