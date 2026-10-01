package dev.c4lam.celeste.module;
import net.minecraft.client.MinecraftClient;
public final class ModuleContext {
    private final MinecraftClient client;
    public double speed = 10.0D;
    public ModuleContext(MinecraftClient client) { this.client = client; }
    public MinecraftClient client() { return client; }
    public double slider(String name, double fallback) {
        return name.equals("Speed") ? speed : fallback;
    }
}
