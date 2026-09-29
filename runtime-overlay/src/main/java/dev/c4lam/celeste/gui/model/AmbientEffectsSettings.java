package dev.c4lam.celeste.gui.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** Ordinary module settings keep layers in local, PNG and cloud configurations. */
public final class AmbientEffectsSettings {
    public static final List<String> STYLES = List.of("Fireflies", "Crystal Motes", "Snowfall",
        "Falling Leaves", "Tesseracts", "Tron Trails");
    public static final String PREFIX = "module.ambient_effects.setting.";
    public record Layer(String style, double density, double size) { }
    private AmbientEffectsSettings() { }

    public static GuiSetting[] settings(Runnable editor) {
        List<GuiSetting> result = new ArrayList<>();
        result.add(new GuiSetting.Action("Edit effects", "Add effects and adjust each one", editor));
        // Keep the legacy names so an older config can be migrated without losing its look.
        result.add(new GuiSetting.Mode("Style", "Ambient geometry", STYLES, "Fireflies").visibleWhen(() -> false));
        result.add(new GuiSetting.Slider("Density", "Amount", .6, 0, 1, .05).hidden());
        result.add(new GuiSetting.Slider("Size", "Size", 1, .5, 2, .1).hidden());
        result.add(new GuiSetting.Slider("Layers Version", "Configuration version", 1, 1, 1, 1).hidden());
        for (String style : STYLES) {
            result.add(new GuiSetting.Toggle(style + " Enabled", "Active effect", style.equals("Fireflies")).hidden());
            result.add(new GuiSetting.Slider(style + " Density", "Amount", .6, 0, 1, .05).hidden());
            result.add(new GuiSetting.Slider(style + " Size", "Size", 1, .5, 2, .1).hidden());
        }
        result.add(new GuiSetting.Toggle("Sky Lines", "Slow luminous arcs above the horizon", true));
        result.add(new GuiSetting.Mode("Sky Pattern", "Sky line design",
            List.of("Orbital Ribbons", "Constellation Arcs", "Light Rails"), "Orbital Ribbons"));
        result.add(new GuiSetting.Slider("Sky Intensity", "Sky line brightness", .65, 0, 1, .05));
        result.add(new GuiSetting.Slider("Sky Speed", "Sky line motion", .6, 0, 2, .1));
        return result.toArray(GuiSetting[]::new);
    }

    public static List<Layer> layers(GuiModule module) {
        List<Layer> result = new ArrayList<>();
        if (module != null) for (String style : STYLES) if (enabled(module, style))
            result.add(new Layer(style, slider(module, style, "Density").value(), slider(module, style, "Size").value()));
        return List.copyOf(result);
    }

    public static boolean enabled(GuiModule module, String style) {
        GuiSetting setting = find(module, style + " Enabled");
        return STYLES.contains(style) && setting instanceof GuiSetting.Toggle toggle && toggle.value();
    }

    public static boolean setEnabled(GuiModule module, String style, boolean enabled) {
        if (!STYLES.contains(style)) return false;
        GuiSetting setting = find(module, style + " Enabled");
        if (!(setting instanceof GuiSetting.Toggle toggle) || toggle.value() == enabled) return false;
        toggle.setValue(enabled);
        return true;
    }

    public static GuiSetting.Slider slider(GuiModule module, String style, String control) {
        if (!STYLES.contains(style) || !(control.equals("Density") || control.equals("Size")))
            throw new IllegalArgumentException("Unknown ambient control");
        GuiSetting setting = find(module, style + " " + control);
        if (setting instanceof GuiSetting.Slider slider) return slider;
        throw new IllegalArgumentException("Missing ambient control: " + style + " " + control);
    }

    private static GuiSetting find(GuiModule module, String label) {
        if (module != null) for (GuiSetting setting : module.settings())
            if (setting.label().equals(label)) return setting;
        return null;
    }

    /** Normalize each complete ambient snapshot before applying it, never inherited live state. */
    public static Properties prepare(Properties original) {
        if (original == null || original.stringPropertyNames().stream()
            .noneMatch(key -> key.startsWith("module.ambient_effects."))) return original;
        Properties values = new Properties();
        values.putAll(original);
        boolean modern = finite(values.getProperty(PREFIX + "layers_version"), 0) >= 1;
        String legacy = values.getProperty(PREFIX + "style", "Fireflies");
        if (!STYLES.contains(legacy)) legacy = "Fireflies";
        for (String style : STYLES) {
            String name = key(style);
            boolean selected = modern
                ? Boolean.parseBoolean(values.getProperty(PREFIX + name + "_enabled", "false"))
                : style.equals(legacy);
            values.setProperty(PREFIX + name + "_enabled", Boolean.toString(selected));
            double density = finite(values.getProperty(PREFIX + (modern ? name + "_density" : "density")), .6);
            double size = finite(values.getProperty(PREFIX + (modern ? name + "_size" : "size")), 1);
            if (!modern && !selected) { density = .6; size = 1; }
            values.setProperty(PREFIX + name + "_density", Double.toString(Math.clamp(density, 0, 1)));
            values.setProperty(PREFIX + name + "_size", Double.toString(Math.clamp(size, .5, 2)));
        }
        values.setProperty(PREFIX + "layers_version", "1");
        return values;
    }

    private static double finite(String raw, double fallback) {
        try { double value = Double.parseDouble(raw); return Double.isFinite(value) ? value : fallback; }
        catch (NumberFormatException | NullPointerException ignored) { return fallback; }
    }

    public static String key(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }
}
