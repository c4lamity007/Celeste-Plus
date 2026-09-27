package dev.c4lam.celeste.compat;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.network.ClientPlayerInteractionManager;
public final class MinecraftAccess {
 public static int queuedPresses(KeyBinding binding){return binding.queued;}
 public static void syncSelectedSlot(ClientPlayerInteractionManager manager){manager.syncSelectedSlot();}
 public static int lastSelectedSlot(ClientPlayerInteractionManager manager){return manager.lastSelectedSlot;}
}
