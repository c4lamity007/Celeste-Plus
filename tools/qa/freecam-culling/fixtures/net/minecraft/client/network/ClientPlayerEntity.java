package net.minecraft.client.network;
import net.minecraft.client.input.Input;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
public final class ClientPlayerEntity extends Entity {
    public final Input input = new Input();
    private final Object profile = new Object();
    public ClientPlayerEntity(ClientWorld world) { super(world); }
    public Object getGameProfile() { return profile; }
}
