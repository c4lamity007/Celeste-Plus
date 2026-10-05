package dev.c4lam.celeste.module.delivery;

/** Scoped frame storage. Game render states never retain a child-defined frame or key. */
public final class ModuleFrame<T> {
    private final ModuleScope scope;
    private Object state;
    private T value;
    private ModuleFrame(ModuleScope scope) {
        this.scope = scope;
        if (scope != null) scope.own(this::clear);
    }
    public static <T> ModuleFrame<T> create() { return new ModuleFrame<>(ModuleScope.current()); }
    public synchronized void set(Object state, T value) {
        if (scope != null && !scope.allowed()) return;
        this.state = state;
        this.value = value;
    }
    public synchronized T get(Object state) {
        return this.state == state && (scope == null || scope.allowed()) ? value : null;
    }
    public synchronized void clear() { state = null; value = null; }
}
