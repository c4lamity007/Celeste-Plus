package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.action.HeldUseBroker;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.gui.model.GuiCatalog;
import dev.c4lam.celeste.gui.model.GuiModule;
import dev.c4lam.celeste.gui.model.GuiSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/** Activation constructs only this module's controller; retirement removes its callbacks and references. */
public final class DoubleAnchorMacroFeature implements ModuleFeature {
    private HeldUseBroker.Registration registration;
    private DoubleAnchorController controller;
    @Override public String name() { return "Double Anchor Macro"; }
    @Override public void onEnable(ModuleContext context) {
        if (registration != null) registration.close();
        controller = new DoubleAnchorController();
        registration = HeldUseBroker.INSTANCE.register(controller, 400);
    }
    @Override public void onStartTick(ModuleContext context) { HeldUseBroker.INSTANCE.onStartTick(context); }
    @Override public void onActionTick(ModuleContext context) { HeldUseBroker.INSTANCE.onActionTick(context); }
    @Override public void onDisable(ModuleContext context) {
        if (registration != null) registration.close();
        registration = null; controller = null;
    }
    @Override public void onWorldChanged(ModuleContext context) {
        if (controller != null) controller.stop(context.client());
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
    static int holdKeyCode() {
        for (GuiModule module : GuiCatalog.modules()) if (module.name().equals("Double Anchor Macro"))
            for (GuiSetting setting : module.settings())
                if (setting instanceof GuiSetting.Keybind bind && bind.label().equals("Hold")) return bind.keyCode();
        return -1;
    }
    static boolean holdKeyDown(MinecraftClient client) {
        int key = holdKeyCode();
        return client != null && client.getWindow() != null && key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_LAST
            && InputUtil.isKeyPressed(client.getWindow(), key);
    }
}
