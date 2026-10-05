package dev.c4lam.celeste.module.impl.visual;

/** Obsolete aggregate: each module factory now installs its own scoped renderer. */
@Deprecated
public final class VisualWorldRenderer {
    private VisualWorldRenderer() { }
    public static void initialize(Object... unused) {
        throw new IllegalStateException("Use scoped module factories; aggregate registration is obsolete");
    }
}
