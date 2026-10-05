package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.gui.model.GuiCatalog;
import dev.c4lam.celeste.gui.model.GuiModule;
import dev.c4lam.celeste.gui.model.GuiSetting;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/** Held modifier for Anchor Macro; the existing coordinator owns every action. */
public final class DoubleAnchorMacroFeature implements ModuleFeature {
    @Override
    public String name() {
        return "Double Anchor Macro";
    }

    public static boolean held(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null
            || client.interactionManager == null || client.getWindow() == null
            || client.currentScreen != null || client.getOverlay() != null
            || !client.isWindowFocused()) return false;
        boolean anchorEnabled = false;
        boolean modifierEnabled = false;
        for (GuiModule module : GuiCatalog.modules()) {
            if (module.name().equals("Double Anchor Macro")) modifierEnabled = module.enabled();
            else if (module.name().equals("Anchor Macro")) anchorEnabled = module.enabled();
        }
        return modifierEnabled && anchorEnabled && holdKeyDown(client);
    }

    /** Raw key state: screens/toggle changes must not manufacture a fresh hold. */
    static boolean holdKeyDown(MinecraftClient client) {
        if (client == null || client.getWindow() == null) return false;
        for (GuiModule module : GuiCatalog.modules()) {
            if (!module.name().equals("Double Anchor Macro")) continue;
            for (GuiSetting setting : module.settings()) {
                if (setting instanceof GuiSetting.Keybind bind && bind.label().equals("Hold")) {
                    int key = bind.keyCode();
                    return key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_LAST
                        && InputUtil.isKeyPressed(client.getWindow(), key);
                }
            }
        }
        return false;
    }

    @Override
    public void onDisable(ModuleContext context) {
        CrystalCombatCoordinator.INSTANCE.onDoubleAnchorDisabled(context.client());
    }
}
