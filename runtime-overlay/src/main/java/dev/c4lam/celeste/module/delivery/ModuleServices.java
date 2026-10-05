package dev.c4lam.celeste.module.delivery;

import dev.c4lam.celeste.module.ModuleContext;
import java.util.Objects;
import java.util.function.Supplier;

/** Resident services supplied to a child factory; all ownership is activation-scoped. */
public final class ModuleServices {
    private final String moduleId;
    private final long generation;
    private final ModuleScope scope;
    private final ModuleHookRegistry hooks;
    private final Supplier<ModuleContext> context;

    public ModuleServices(String moduleId, long generation, ModuleScope scope,
                          ModuleHookRegistry hooks, Supplier<ModuleContext> context) {
        this.moduleId = Objects.requireNonNull(moduleId);
        this.generation = generation;
        this.scope = Objects.requireNonNull(scope);
        this.hooks = Objects.requireNonNull(hooks);
        this.context = Objects.requireNonNull(context);
    }

    public String moduleId() { return moduleId; }
    public long generation() { return generation; }
    public ModuleScope scope() { return scope; }
    public ModuleContext context() { return context.get(); }
    public ModuleHookRegistry.Registration hook(String operation, ModuleHookRegistry.Handler handler) {
        ModuleHookRegistry.Registration registration = hooks.connect(moduleId, generation, operation, arguments -> {
            try (var ownership = scope.tryEnter()) {
                if (ownership == null) return ModuleHookRegistry.UNAVAILABLE;
                return handler.invoke(arguments);
            }
        });
        try { return scope.own(registration, registration.detached()); }
        catch (RuntimeException failure) { registration.close(); throw failure; }
    }
}

