package dev.c4lam.celeste.module.impl.visual.expansion;

/** Obsolete aggregate: each module factory now installs its own scoped renderer. */
@Deprecated
public final class VisualExpansion {
    private VisualExpansion() { }
    public static void register(Object... unused) {
        throw new IllegalStateException("Use scoped module factories; aggregate registration is obsolete");
    }
}
