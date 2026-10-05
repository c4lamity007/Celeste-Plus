package dev.c4lam.celeste.module.delivery;

/** Reviewed version 1 operations; all argument/result types are resident or game types. */
public final class ModuleHookAbi {
 private ModuleHookAbi() { }
 public static void declare(ModuleHookRegistry registry) {
  registry.declare("mining.active", 0);
  registry.declare("atmosphere.palette", 0);
  registry.declare("attack.hit", 2);
  registry.declare("chams.active", 0);
  registry.declare("fluid.after_interact", 2);
  registry.declare("fluid.capture", 3);
  registry.declare("fluid.clear", 0);
  registry.declare("fog.shared", 5);
  registry.declare("fog.vanilla", 10);
  registry.declare("freecam.control", 1);
  registry.declare("freecam.detached", 1);
  registry.declare("freecam.focus", 1);
  registry.declare("freecam.input", 3);
  registry.declare("freecam.look", 3);
  registry.declare("freecam.toggle", 1);
  registry.declare("gamble.attackBlock", 2);
  registry.declare("gamble.attackEntity", 1);
  registry.declare("gamble.beforeSetBlockState", 2);
  registry.declare("gamble.breakBlock", 1);
  registry.declare("gamble.cancelBlockBreaking", 0);
  registry.declare("gamble.chunkUnloaded", 2);
  registry.declare("gamble.clientItemTick", 1);
  registry.declare("gamble.creativeDrop", 1);
  registry.declare("gamble.creativeStack", 2);
  registry.declare("gamble.dropSelected", 2);
  registry.declare("gamble.ensureScreenPrizes", 1);
  registry.declare("gamble.handleClick", 5);
  registry.declare("gamble.interactBlock", 3);
  registry.declare("gamble.interactEntity", 2);
  registry.declare("gamble.interactItem", 2);
  registry.declare("gamble.itemTickAction", 1);
  registry.declare("gamble.noteUsedDispenser", 1);
  registry.declare("gamble.onPickBlock", 1);
  registry.declare("gamble.packet", 1);
  registry.declare("gamble.reapplyAfterSync", 0);
  registry.declare("gamble.serverBlockUpdate", 3);
  registry.declare("gamble.stashCursor", 2);
  registry.declare("gamble.tryPickup", 2);
  registry.declare("gamble.updateBlockBreakingProgress", 2);
  registry.declare("gamble.worldEvent", 5);
  registry.declare("hand.begin", 0);
  registry.declare("hand.context", 2);
  registry.declare("hand.placement", 3);
  registry.declare("item.highlight", 2);
  registry.declare("knockback.packet", 1);
  registry.declare("light.gamma", 1);
  registry.declare("light.night_vision", 1);
  registry.declare("light.refresh", 0);
  registry.declare("model.attack", 2);
  registry.declare("model.capture", 2);
  registry.declare("model.replace", 1);
  registry.declare("rotation.apply", 2);
  registry.declare("rotation.quantize", 0);
  registry.declare("rotation.visible", 1);
  registry.declare("sky.clear", 0);
  registry.declare("sky.draw", 1);
  registry.declare("sky.extract", 4);
  registry.declare("sky.fog_mode", 0);
  registry.declare("sky.ownsSky", 1);
  registry.declare("sprint.after_attack", 3);
  registry.declare("swing.duration", 2);
  registry.declare("swing.finish", 2);
  registry.declare("swing.transform", 5);
  registry.declare("world.block_changed", 2);
 }
}
