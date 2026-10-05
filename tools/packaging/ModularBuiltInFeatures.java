package dev.c4lam.celeste.module;

/** The reviewed registry used only by independently delivered module builds. */
public final class BuiltInFeatures {
    private BuiltInFeatures() { }
    public static void register(ModuleManager modules) {
        if (!modules.payloadsConfigured())
            throw new IllegalStateException("Independent module delivery is unavailable");
        modules.registerPayloadCatalog();
    }
}
