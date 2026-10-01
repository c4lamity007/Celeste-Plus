package net.minecraft.client;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
public final class MinecraftClient {
    private static MinecraftClient instance;
    public ClientPlayerEntity player;
    public ClientWorld world;
    public final GameOptions options = new GameOptions();
    public final WorldRenderer worldRenderer = new WorldRenderer(this);
    public boolean chunkCullingEnabled = true;
    public Object currentScreen;
    private Entity camera;
    public MinecraftClient() { instance = this; }
    public static MinecraftClient getInstance() { return instance; }
    public Entity getCameraEntity() { return camera; }
    public void setCameraEntity(Entity entity) { camera = entity; }
}
