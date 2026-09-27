package dev.c4lam.celeste.module.impl.combat;
import dev.c4lam.celeste.gui.model.GuiCatalog;
import net.minecraft.client.MinecraftClient;
/** The adapter controls physical key sampling; the coordinator owns every sequence decision. */
public final class DoubleAnchorMacroFeature {
 static boolean holdKeyDown(MinecraftClient client){return GuiCatalog.modules().stream().anyMatch(module -> module.name().equals("Double Anchor Macro")&&module.held);}
 public static boolean held(MinecraftClient client){return GuiCatalog.modules().stream().anyMatch(module -> module.name().equals("Double Anchor Macro")&&module.enabled&&module.held);}
}
