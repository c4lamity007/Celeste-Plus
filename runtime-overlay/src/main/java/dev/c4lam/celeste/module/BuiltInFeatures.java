package dev.c4lam.celeste.module;

/** Selects one registration protocol. Modular runtimes never resolve the legacy registry. */
public final class BuiltInFeatures {
    private BuiltInFeatures() { }
    public static void register(ModuleManager modules) {
        if (modules.payloadsConfigured()) {
            modules.registerPayloadCatalog();
            return;
        }
        // Kept only for the existing monolithic release/development profile. The modular
        // producer excludes this legacy class and requires configurePayloads beforehand.
        try {
            Class<?> legacy = Class.forName("dev.c4lam.celeste.module.LegacyBuiltInFeatures", true,
                BuiltInFeatures.class.getClassLoader());
            legacy.getMethod("register", ModuleManager.class).invoke(null, modules);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Module delivery was not configured for this runtime", failure);
        }
    }
}
