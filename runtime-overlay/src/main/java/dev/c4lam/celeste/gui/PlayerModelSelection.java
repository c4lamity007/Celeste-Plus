package dev.c4lam.celeste.gui;

import dev.c4lam.celeste.module.ModuleManager;
import java.nio.file.Path;
import java.util.Locale;
import net.minecraft.client.MinecraftClient;

/** Resident model-selection metadata. Parsing, animation and GPU work stay in the module. */
public final class PlayerModelSelection {
    public record Request(long version, boolean bundled, String path) { }
    private static long version;
    private static Request selected;
    private static String message = "Choose a self-contained .glb model with embedded textures.";
    private static boolean importing;
    private PlayerModelSelection() { }

    public static void importModel(String path) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) { client.execute(() -> importModel(path)); return; }
        try {
            String value = path.strip();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1)
                value = value.substring(1, value.length() - 1).strip();
            if (value.startsWith("\\\\") || value.startsWith("//")) throw new IllegalArgumentException("Choose a local file");
            Path selectedPath = Path.of(value);
            if (!selectedPath.isAbsolute() || !value.toLowerCase(Locale.ROOT).endsWith(".glb"))
                throw new IllegalArgumentException("Select an absolute local .glb path");
            selected = new Request(++version, false, selectedPath.normalize().toString());
            message = "Preparing Player Models…";
            importing = false;
            enable();
        } catch (RuntimeException failure) { message = "Invalid model path: " + failure.getMessage(); }
    }
    public static void loadSahur() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) { client.execute(PlayerModelSelection::loadSahur); return; }
        selected = new Request(++version, true, "");
        message = "Preparing Player Models…";
        importing = false;
        enable();
    }
    private static void enable() {
        var module = ModuleManager.getInstance().module("Player Models");
        if (module != null) module.setEnabled(true);
    }
    public static Request selected() { return selected; }
    public static String status() { return message; }
    public static boolean importing() { return importing; }
    public static void status(String text, boolean busy) { message = text; importing = busy; }
}
