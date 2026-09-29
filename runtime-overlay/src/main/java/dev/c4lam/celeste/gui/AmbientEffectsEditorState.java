package dev.c4lam.celeste.gui;

import dev.c4lam.celeste.gui.model.AmbientEffectsSettings;
import dev.c4lam.celeste.gui.model.GuiModule;
import dev.c4lam.celeste.gui.model.GuiSetting;
import java.util.List;

/** Input state shared with deterministic tests; no world or renderer is needed to edit. */
public final class AmbientEffectsEditorState {
    public enum Result { NONE, CHANGED, CLOSE }
    private final GuiModule module;
    private String selected;
    private String dragging;
    private boolean adding;
    private int pickerRow;

    public AmbientEffectsEditorState(GuiModule module) {
        this.module = module;
        refresh();
    }
    public String selected() { refresh(); return selected; }
    public String dragging() { return dragging; }
    public boolean adding() { return adding; }
    public int pickerRow() { return pickerRow; }
    public List<String> active() { return AmbientEffectsSettings.layers(module).stream().map(AmbientEffectsSettings.Layer::style).toList(); }
    public List<String> available() { return AmbientEffectsSettings.STYLES.stream().filter(style -> !AmbientEffectsSettings.enabled(module, style)).toList(); }
    public List<String> rows() { return adding ? available() : active(); }
    public void refresh() {
        List<String> active = active();
        if (!active.contains(selected)) selected = active.isEmpty() ? null : active.getFirst();
        pickerRow = Math.clamp(pickerRow, 0, Math.max(0, available().size() - 1));
    }
    public boolean add(String style) {
        if (!AmbientEffectsSettings.setEnabled(module, style, true)) return false;
        selected = style; adding = false; dragging = null; return true;
    }
    public boolean remove(String style) {
        if (!AmbientEffectsSettings.setEnabled(module, style, false)) return false;
        dragging = null; refresh(); return true;
    }
    public void openPicker() {
        if (!available().isEmpty()) { adding = true; pickerRow = 0; dragging = null; }
    }
    public Result escape() {
        dragging = null;
        if (adding) { adding = false; return Result.NONE; }
        return Result.CLOSE;
    }
    public Result click(double x, double y) {
        return click(x, y, 0);
    }
    public Result click(double x, double y, int button) {
        if (button == 1) {
            if (adding) return Result.NONE;
            List<String> rows = active();
            for (int i = 0; i < rows.size(); i++) if (AmbientEffectsEditorLayout.row(i).contains(x, y))
                return remove(rows.get(i)) ? Result.CHANGED : Result.NONE;
            return Result.NONE;
        }
        if (button != 0) return Result.NONE;
        dragging = null; refresh();
        if (AmbientEffectsEditorLayout.DONE.contains(x, y)) return Result.CLOSE;
        if (AmbientEffectsEditorLayout.ENABLED.contains(x, y)) { module.toggle(); return Result.CHANGED; }
        if (AmbientEffectsEditorLayout.ADD.contains(x, y)) {
            if (adding) adding = false; else openPicker();
            return Result.NONE;
        }
        List<String> rows = rows();
        for (int i = 0; i < rows.size(); i++) if (AmbientEffectsEditorLayout.row(i).contains(x, y)) {
            String style = rows.get(i);
            if (adding) return add(style) ? Result.CHANGED : Result.NONE;
            if (AmbientEffectsEditorLayout.remove(i).contains(x, y)) return remove(style) ? Result.CHANGED : Result.NONE;
            selected = style; return Result.NONE;
        }
        if (!adding && selected != null) {
            if (AmbientEffectsEditorLayout.DENSITY.contains(x, y)) dragging = "Density";
            if (AmbientEffectsEditorLayout.SIZE.contains(x, y)) dragging = "Size";
            if (dragging != null) return drag(x) ? Result.CHANGED : Result.NONE;
        }
        return Result.NONE;
    }
    public boolean drag(double x) {
        if (dragging == null || adding || selected == null || !Double.isFinite(x)) return false;
        GuiSetting.Slider slider = AmbientEffectsSettings.slider(module, selected, dragging);
        double before = slider.value();
        var bounds = dragging.equals("Density") ? AmbientEffectsEditorLayout.DENSITY : AmbientEffectsEditorLayout.SIZE;
        slider.setValue(slider.minimum() + AmbientEffectsEditorLayout.progress(bounds, x) * (slider.maximum() - slider.minimum()));
        return before != slider.value();
    }
    public void release() { dragging = null; }
    public boolean wheel(double x, double y, double amount) {
        if (adding || selected == null || !Double.isFinite(amount) || amount == 0) return false;
        String control = AmbientEffectsEditorLayout.DENSITY.contains(x, y) ? "Density"
            : AmbientEffectsEditorLayout.SIZE.contains(x, y) ? "Size" : null;
        if (control == null) return false;
        GuiSetting.Slider slider = AmbientEffectsSettings.slider(module, selected, control);
        double before = slider.value();
        slider.setValue(before + Math.signum(amount) * slider.step());
        return before != slider.value();
    }
    public void move(int step) {
        if (adding) { pickerRow = Math.clamp(pickerRow + step, 0, Math.max(0, available().size() - 1)); return; }
        List<String> active = active();
        if (!active.isEmpty()) selected = active.get(Math.clamp(active.indexOf(selected) + step, 0, active.size() - 1));
    }
    public boolean enter() {
        if (adding && !available().isEmpty()) return add(available().get(pickerRow));
        openPicker(); return false;
    }
}
