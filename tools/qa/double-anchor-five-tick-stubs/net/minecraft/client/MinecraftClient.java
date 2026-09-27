package net.minecraft.client;
import net.minecraft.client.network.*;import net.minecraft.client.world.ClientWorld;import net.minecraft.client.option.GameOptions;import net.minecraft.util.hit.HitResult;import net.minecraft.entity.Entity;
public final class MinecraftClient {
 private static MinecraftClient instance;public ClientPlayerEntity player=new ClientPlayerEntity();public ClientWorld world=new ClientWorld();public ClientPlayerInteractionManager interactionManager;
 public final GameOptions options=new GameOptions();public boolean useHeld=true,focused=true;public Object currentScreen,overlay;public HitResult crosshairTarget;public Entity targetedEntity;
 public MinecraftClient(){instance=this;player.world=world;interactionManager=new ClientPlayerInteractionManager(this);}
 public static MinecraftClient getInstance(){return instance;}public boolean isWindowFocused(){return focused;}public boolean isOnThread(){return true;}public Object getOverlay(){return overlay;}
}
