package dev.c4lam.celeste.gui;

import dev.c4lam.celeste.gui.model.*;
import dev.c4lam.celeste.module.ModuleManager;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class AmbientLayersUiCheck {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    private static void near(double expected, double actual, String message) {
        check(Math.abs(expected - actual) < 1e-6, message + ": " + expected + " != " + actual);
    }
    private static GuiModule fresh() {
        return new GuiModule("Ambient Effects", "", CelestePage.VISUALS, false, AmbientEffectsSettings.settings(() -> {}));
    }
    public static void main(String[] args) throws Exception {
        System.setProperty("celeste.qa.config", Path.of(args[0]).toAbsolutePath().toString());
        defaultsAndInputs(); migrationAndActualPersistence(); geometry();
        System.out.println("Ambient layers UI: " + checks + " checks passed (actual settings, input state, ModuleManager load/import/export/save)");
    }
    private static void defaultsAndInputs() {
        GuiModule module = fresh();
        var state = new AmbientEffectsEditorState(module);
        check(state.active().equals(List.of("Fireflies")), "default has one existing effect");
        check(!module.enabled(), "editor does not enable module");
        check(module.visibleSettings().size() == 5, "only editor and shared sky settings visible");
        near(.6, AmbientEffectsSettings.slider(module, "Fireflies", "Density").value(), "default density");
        state.click(160, 60);
        check(state.adding(), "add button opens picker");
        check(!state.rows().contains("Fireflies"), "picker excludes active styles");
        state.click(35, AmbientEffectsEditorLayout.row(4).y() + 9);
        check(!state.adding() && "Tron Trails".equals(state.selected()), "picker adds and selects Tron");
        check(state.active().size() == 2, "both effects enabled");
        check(!state.add("Tron Trails"), "duplicates cannot be added");
        check(state.click(313, 145) == AmbientEffectsEditorState.Result.CHANGED, "density pointer routed");
        state.drag(394); state.release();
        near(1, AmbientEffectsSettings.slider(module, "Tron Trails", "Density").value(), "density drag endpoint");
        near(.6, AmbientEffectsSettings.slider(module, "Fireflies", "Density").value(), "independent settings");
        state.click(393, 200); state.drag(1000); state.release();
        near(2, AmbientEffectsSettings.slider(module, "Tron Trails", "Size").value(), "drag clamps outside panel");
        check(!state.wheel(30, 90, -5), "scroll over rows cannot change sliders");
        check(state.wheel(280, 200, -1), "scroll at size changes size");
        near(1.9, AmbientEffectsSettings.slider(module, "Tron Trails", "Size").value(), "size wheel one step");
        check(!state.wheel(280, 200, Double.NaN), "nonfinite wheel ignored");
        state.click(188, AmbientEffectsEditorLayout.row(1).y() + 9);
        check(state.active().equals(List.of("Fireflies")), "row minus removes correct effect");
        state.add("Tron Trails");
        near(1.9, AmbientEffectsSettings.slider(module, "Tron Trails", "Size").value(), "readding remembers size");
        near(1, AmbientEffectsSettings.slider(module, "Tron Trails", "Density").value(), "readding remembers density");
        for (String style : AmbientEffectsSettings.STYLES) state.add(style);
        check(state.active().size() == 6, "six unique effects");
        state.openPicker(); check(!state.adding(), "full list cannot open empty picker");
        for (String style : List.copyOf(state.active())) state.remove(style);
        check(state.active().isEmpty() && state.selected() == null, "remove final effect leaves intentional empty list");
        state.openPicker(); state.move(5);
        check(state.enter() && "Tron Trails".equals(state.selected()), "keyboard picker selection");
        state.openPicker();
        check(state.escape() == AmbientEffectsEditorState.Result.NONE && !state.adding(), "escape closes picker first");
        check(state.escape() == AmbientEffectsEditorState.Result.CLOSE, "escape returns to parent");
        state.click(350, 27); check(module.enabled(), "explicit module switch enables");
        check(!state.add("Unknown"), "unknown style ignored");
        check(state.click(-200, -200) == AmbientEffectsEditorState.Result.NONE, "outside click ignored");
    }
    private static void migrationAndActualPersistence() throws Exception {
        ModuleManager manager = ModuleManager.getInstance();
        GuiModule module = GuiCatalog.AMBIENT;
        String prefix = AmbientEffectsSettings.PREFIX;
        for (String style : AmbientEffectsSettings.STYLES) {
            Properties legacy = new Properties();
            legacy.setProperty("module.ambient_effects.enabled", "true");
            legacy.setProperty(prefix + "style", style);
            legacy.setProperty(prefix + "density", "0.85");
            legacy.setProperty(prefix + "size", "1.7");
            legacy.setProperty(prefix + "sky_lines", "false");
            legacy.setProperty("module.other.setting.amount", "0.7");
            manager.importState(legacy);
            check(!legacy.containsKey(prefix + "layers_version"), "migration does not mutate imported snapshot");
            check(AmbientEffectsSettings.layers(module).size() == 1, "legacy import resets previous layers");
            var layer = AmbientEffectsSettings.layers(module).getFirst();
            check(layer.style().equals(style), "each legacy style preserved");
            near(.85, layer.density(), "legacy density preserved"); near(1.7, layer.size(), "legacy size preserved");
            check(module.enabled(), "module enabled preserved");
            check(manager.exportState().getProperty(prefix + "sky_lines").equals("false"), "sky preserved");
            check(manager.exportState().getProperty("module.other.setting.amount").startsWith("0.7"), "other modules unchanged");
        }
        var state = new AmbientEffectsEditorState(module);
        state.add("Snowfall"); state.add("Tesseracts");
        AmbientEffectsSettings.slider(module, "Snowfall", "Density").setValue(.25);
        AmbientEffectsSettings.slider(module, "Tesseracts", "Size").setValue(.8);
        Properties exported = manager.exportState();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); exported.store(bytes, "roundtrip");
        Properties imported = new Properties(); imported.load(new ByteArrayInputStream(bytes.toByteArray()));
        for (String style : AmbientEffectsSettings.STYLES) state.remove(style);
        manager.importState(imported);
        check(manager.exportState().equals(exported), "full multi-effect Properties roundtrip through actual manager");
        Path file = Path.of(System.getProperty("celeste.qa.config"), "celeste", "modules.properties");
        check(Files.isRegularFile(file), "manager saved local config");
        Properties disk = new Properties(); try (InputStream stream = Files.newInputStream(file)) { disk.load(stream); }
        check(disk.equals(exported), "disk save includes hidden layers");
        var load = ModuleManager.class.getDeclaredMethod("load"); load.setAccessible(true);
        for (String style : AmbientEffectsSettings.STYLES) state.remove(style);
        load.invoke(manager);
        check(manager.exportState().equals(exported), "actual startup load restores multilayer config");
        Properties oldDisk = new Properties(); oldDisk.setProperty(prefix + "style", "Falling Leaves");
        oldDisk.setProperty(prefix + "density", ".35"); oldDisk.setProperty(prefix + "size", "1.4");
        try (OutputStream stream = Files.newOutputStream(file)) { oldDisk.store(stream, "legacy"); }
        load.invoke(manager);
        var oldLayer = AmbientEffectsSettings.layers(module).getFirst();
        check(AmbientEffectsSettings.layers(module).size() == 1 && oldLayer.style().equals("Falling Leaves"), "actual startup load migrates legacy");
        near(.35, oldLayer.density(), "startup density"); near(1.4, oldLayer.size(), "startup size");
        for (String style : AmbientEffectsSettings.STYLES) state.remove(style);
        Properties empty = manager.exportState();
        state.add("Fireflies"); manager.importState(empty);
        check(AmbientEffectsSettings.layers(module).isEmpty(), "saved modern empty list stays empty");
        Properties sparse = new Properties(); sparse.setProperty(prefix + "layers_version", "1");
        sparse.setProperty(prefix + "tron_trails_enabled", "true");
        sparse.setProperty(prefix + "tron_trails_density", "NaN"); sparse.setProperty(prefix + "tron_trails_size", "Infinity");
        manager.importState(sparse);
        check(AmbientEffectsSettings.layers(module).size() == 1, "modern missing flags clear previous layers");
        near(.6, AmbientEffectsSettings.layers(module).getFirst().density(), "invalid density finite fallback");
        near(1, AmbientEffectsSettings.layers(module).getFirst().size(), "invalid size finite fallback");
        Properties unrelated = new Properties(); unrelated.setProperty("module.other.enabled", "false");
        Properties before = manager.exportState(); manager.importState(unrelated);
        check(manager.exportState().getProperty(prefix + "tron_trails_enabled").equals(before.getProperty(prefix + "tron_trails_enabled")), "partial unrelated snapshot leaves ambient unchanged");
        Properties legacyDefault = new Properties(); legacyDefault.setProperty("module.ambient_effects.enabled", "false");
        manager.importState(legacyDefault);
        check(AmbientEffectsSettings.layers(module).getFirst().style().equals("Fireflies"), "old defaults reset to original style");
    }
    private static void geometry() {
        for (int width : new int[]{160, 320, 640, 854, 1280, 1920, 3840})
            for (int height : new int[]{90, 180, 240, 480, 720, 1080, 2160})
                for (float scale : new float[]{.5f, 1, 1.5f, 2.66f}) {
                    var view = AmbientEffectsEditorLayout.fit(width, height, scale);
                    check(view.x() >= 0 && view.y() >= 0, "panel remains inside viewport");
                    check(view.x() + AmbientEffectsEditorLayout.WIDTH * view.scale() <= width + .001, "panel right clipped safely");
                    check(view.y() + AmbientEffectsEditorLayout.HEIGHT * view.scale() <= height + .001, "panel bottom clipped safely");
                    for (int i = 0; i < 6; i++) {
                        var row = AmbientEffectsEditorLayout.row(i);
                        double x = view.x() + (row.x() + 10) * view.scale(), y = view.y() + (row.y() + 10) * view.scale();
                        check(row.contains(view.localX(x), view.localY(y)), "scaled pointer matches rendered row");
                        check(row.bottom() <= AmbientEffectsEditorLayout.LIST.bottom(), "all rows fit no hidden scrolling");
                        check(row.right() < AmbientEffectsEditorLayout.DETAILS.x(), "row and sliders do not overlap");
                        check(!row.contains(row.right(), row.bottom()), "right/bottom bounds exclusive");
                    }
                }
        check(!AmbientEffectsEditorLayout.DENSITY.contains(Double.NaN, 145), "NaN pointer ignored");
        check(AmbientEffectsEditorLayout.row(5).bottom() < AmbientEffectsEditorLayout.DONE.y(), "sixth row cannot click footer");
    }
}
