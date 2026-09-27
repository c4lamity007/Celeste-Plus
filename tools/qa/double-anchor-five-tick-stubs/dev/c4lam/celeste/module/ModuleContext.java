package dev.c4lam.celeste.module;
import dev.c4lam.celeste.gui.model.GuiModule;
import net.minecraft.client.MinecraftClient;
public record ModuleContext(MinecraftClient client,GuiModule module) {
 public double slider(String name,double fallback){return module.sliders.getOrDefault(name,fallback);}
 public boolean toggle(String name,boolean fallback){return module.toggles.getOrDefault(name,fallback);}
}
