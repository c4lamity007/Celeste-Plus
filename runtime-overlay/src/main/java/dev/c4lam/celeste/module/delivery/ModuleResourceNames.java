package dev.c4lam.celeste.module.delivery;
import net.minecraft.util.Identifier;
/** Stable scoped registrations and private module asset names; vanilla assets retain their identity. */
public final class ModuleResourceNames {
    private ModuleResourceNames() { }
    public static Identifier asset(Identifier original) {
        ModuleScope scope=ModuleScope.current();
        return scope==null || !original.getNamespace().equals("celeste") || original.getPath().startsWith("textures/") || !ModuleResourceAssets.hasModuleAssets(scope)
            ? original : Identifier.of(original.getNamespace(),scope.registrationPath(original.getPath()));
    }
    public static Identifier reloader(Identifier original) {
        ModuleScope scope = ModuleScope.current();
        return scope == null ? original : Identifier.of(original.getNamespace(),scope.registrationPath(original.getPath()));
    }
}
