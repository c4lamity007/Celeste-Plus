package dev.c4lam.celeste.module.delivery;

import dev.c4lam.celeste.module.ModuleManager;

/** Resident bridge: no implementation class, child object cache or module class literal. */
public final class ModuleHooks {
    private ModuleHooks() { }
    public static Object invoke(String module, String operation, Object fallback, Object... arguments) {
        try { return ModuleManager.getInstance().hooks().invoke(module, operation, fallback, arguments); }
        catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException("Module hook failed: " + module + "/" + operation, failure); }
    }
}
