package dev.c4lam.celeste.render;
public final class GuiPipelines {
    private GuiPipelines() { }
    public static void register() {
        if (!dev.c4lam.celeste.compat.BetaRenderPipelines.active) throw new IllegalStateException("register pipelines inside a lease");
    }
}
