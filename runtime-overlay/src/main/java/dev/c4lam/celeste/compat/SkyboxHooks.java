package dev.c4lam.celeste.compat;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.state.SkyRenderState;
import net.minecraft.client.world.ClientWorld;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import dev.c4lam.celeste.module.delivery.ModuleHooks;

/** Permanent game hook surface. Implementation is owned by the enabled module. */
public final class SkyboxHooks {
 private SkyboxHooks() { }
 public static void extract(Object renderer, ClientWorld world, Camera camera, SkyRenderState state) { ModuleHooks.invoke("custom_sky", "sky.extract", null, renderer, world, camera, state); }
 public static boolean draw(Object renderer) { return (boolean) ModuleHooks.invoke("custom_sky", "sky.draw", false, renderer); }
 public static boolean ownsSky(Object renderer) { return (boolean) ModuleHooks.invoke("custom_sky", "sky.ownsSky", false, renderer); }
 public static void clear() { ModuleHooks.invoke("custom_sky", "sky.clear", null); }
}
