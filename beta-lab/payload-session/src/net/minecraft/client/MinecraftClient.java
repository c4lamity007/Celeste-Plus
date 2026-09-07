package net.minecraft.client;
import java.util.ArrayDeque;
public final class MinecraftClient {
    private static final MinecraftClient INSTANCE = new MinecraftClient();
    public static final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    public static boolean reject;
    public static MinecraftClient getInstance() { return INSTANCE; }
    public net.minecraft.client.texture.TextureManager getTextureManager() { return new net.minecraft.client.texture.TextureManager(); }
    public void send(Runnable action) { if (reject) throw new IllegalStateException("client stopped"); queue.addLast(action); }
}
