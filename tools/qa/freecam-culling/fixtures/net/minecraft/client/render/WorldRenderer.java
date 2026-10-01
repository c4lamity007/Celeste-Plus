package net.minecraft.client.render;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
public final class WorldRenderer {
    private final MinecraftClient client;
    public final List<Boolean> updates = new ArrayList<>();
    public WorldRenderer(MinecraftClient client) { this.client = client; }
    public void scheduleTerrainUpdate() { updates.add(client.chunkCullingEnabled); }
}
