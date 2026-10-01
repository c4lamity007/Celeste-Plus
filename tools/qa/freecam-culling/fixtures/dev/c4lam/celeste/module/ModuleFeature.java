package dev.c4lam.celeste.module;
import net.minecraft.client.MinecraftClient;
public interface ModuleFeature {
    String name();
    default void onEnable(ModuleContext context) {}
    default void onStartTick(ModuleContext context) {}
    default void onRenderFrame(ModuleContext context) {}
    default void onDisable(ModuleContext context) {}
    default void onWorldChanged(ModuleContext context) {}
    default boolean canRun(MinecraftClient client) {
        return client != null && client.player != null && client.world != null;
    }
}
