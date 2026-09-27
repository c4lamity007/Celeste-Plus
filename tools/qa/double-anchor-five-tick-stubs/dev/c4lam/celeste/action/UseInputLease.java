package dev.c4lam.celeste.action;
import net.minecraft.client.MinecraftClient;
public final class UseInputLease {
 private static final UseInputLease INSTANCE=new UseInputLease(); private Object owner;
 public static UseInputLease getInstance(){return INSTANCE;}
 public static boolean physicalDown(MinecraftClient client){return client.useHeld;}
 public boolean acquire(Object owner,int priority,long tick,MinecraftClient client){this.owner=owner;client.options.useKey.pressed=false;client.options.useKey.queued=0;return true;}
 public void release(Object owner,MinecraftClient client){if(this.owner==owner){this.owner=null;client.options.useKey.pressed=client.useHeld;}}
 public boolean owns(Object owner){return this.owner==owner;} public void clear(MinecraftClient client){owner=null;}
}
