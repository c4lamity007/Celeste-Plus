package dev.c4lam.celeste.gui;

import dev.c4lam.celeste.config.InterfaceConfig;
import dev.c4lam.celeste.gui.model.AmbientEffectsSettings;
import dev.c4lam.celeste.gui.model.GuiModule;
import dev.c4lam.celeste.gui.model.GuiSetting;
import dev.c4lam.celeste.module.ModuleManager;
import dev.c4lam.celeste.render.GuiRenderer;
import dev.c4lam.celeste.render.RenderUtil;
import dev.c4lam.celeste.render.font.Fonts;
import java.awt.Color;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;

/** Compact mixer: all six effects fit without a nested scroll area. */
public final class AmbientEffectsEditorScreen extends Screen {
    private static final Color TEXT = new Color(247, 243, 249), MUTED = new Color(169, 162, 180);
    private static final Color ACCENT = new Color(255, 61, 150), PANEL = new Color(24, 19, 29, 240);
    private static final Color CARD = new Color(44, 39, 52, 205), HOVER = new Color(58, 50, 67, 225);
    private static final Color SELECTED = new Color(73, 47, 69, 230), TRACK = new Color(83, 73, 94, 220);
    private final Screen parent;
    private final GuiModule module;
    private final AmbientEffectsEditorState state;
    private AmbientEffectsEditorLayout.View view;
    private boolean changed;

    private AmbientEffectsEditorScreen(Screen parent, GuiModule module) {
        super(Text.literal("Ambient Effects"));
        this.parent = parent; this.module = module;
        state = new AmbientEffectsEditorState(module);
    }
    public static void open() {
        MinecraftClient client = MinecraftClient.getInstance();
        GuiModule module = ModuleManager.getInstance().module("Ambient Effects");
        if (module != null) client.setScreen(new AmbientEffectsEditorScreen(client.currentScreen, module));
    }
    @Override protected void init() {
        state.release();
        float preferred = (8f / 3f) / (float) client.getWindow().getScaleFactor()
            * InterfaceConfig.scale() / InterfaceConfig.DEFAULT_SCALE;
        view = AmbientEffectsEditorLayout.fit(width, height, preferred);
        GuiCursors.hideNative(client);
    }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        MatrixStack matrices = RenderUtil.matrices();
        matrices.loadIdentity();
        GuiRenderer.getInstance().clearScissors();
        state.refresh();
        double mx = view.localX(mouseX), my = view.localY(mouseY);
        RenderUtil.captureBlur();
        RenderUtil.BLUR_RECT.draw(matrices, px(0), py(0), AmbientEffectsEditorLayout.WIDTH * view.scale(),
            AmbientEffectsEditorLayout.HEIGHT * view.scale(), new Vector4f(12 * view.scale()),
            PANEL, PANEL, PANEL, PANEL, InterfaceConfig.glassTintMix());
        label(matrices, "Ambient Effects", 16, 17, 13, TEXT);
        label(matrices, "Add effects and tune each one.", 16, 36, 8, MUTED);
        button(matrices, AmbientEffectsEditorLayout.ENABLED, module.enabled() ? "Effects on" : "Effects off",
            module.enabled(), mx, my);
        label(matrices, state.adding() ? "Add an effect" : "Active effects", 16, 56, 8.4f, MUTED);
        boolean canAdd = !state.available().isEmpty();
        button(matrices, AmbientEffectsEditorLayout.ADD, state.adding() ? "Back" : canAdd ? "+ Add" : "All added", false,
            canAdd || state.adding() ? mx : -1, canAdd || state.adding() ? my : -1);
        List<String> rows = state.rows();
        String selected = state.selected();
        for (int i = 0; i < rows.size(); i++) {
            String style = rows.get(i);
            var bounds = AmbientEffectsEditorLayout.row(i);
            boolean active = state.adding() ? i == state.pickerRow() : style.equals(selected);
            rectangle(matrices, bounds, 5, active ? SELECTED : bounds.contains(mx, my) ? HOVER : CARD);
            label(matrices, style, 26, bounds.y() + 8, 8.6f, active ? TEXT : MUTED);
            if (state.adding()) label(matrices, "+", 187, bounds.y() + 7, 10, ACCENT);
            else {
                var remove = AmbientEffectsEditorLayout.remove(i);
                if (remove.contains(mx, my)) rectangle(matrices, remove, 4, HOVER);
                centered(matrices, "-", remove, 11, remove.contains(mx, my) ? TEXT : MUTED);
            }
        }
        rectangle(matrices, AmbientEffectsEditorLayout.DETAILS, 6, CARD);
        if (state.adding()) {
            label(matrices, "Pick an effect", 232, 97, 9.4f, TEXT);
            label(matrices, "It will run with your other effects.", 232, 120, 7.5f, MUTED);
            label(matrices, "Each one has its own size", 232, 143, 7.5f, MUTED);
            label(matrices, "and density.", 232, 155, 7.5f, MUTED);
        } else if (selected != null) {
            label(matrices, selected, 232, 97, 10, TEXT);
            slider(matrices, "Density", AmbientEffectsEditorLayout.DENSITY, selected, mx, my);
            slider(matrices, "Size", AmbientEffectsEditorLayout.SIZE, selected, mx, my);
        } else {
            label(matrices, "No effects yet", 26, 94, 9.2f, TEXT);
            label(matrices, "Use + Add to choose one.", 26, 115, 7.7f, MUTED);
            label(matrices, "Size and density", 232, 97, 9.4f, MUTED);
            label(matrices, "Select an effect to adjust it.", 232, 120, 7.7f, MUTED);
        }
        String footer = state.adding() ? rows.size() + " available" : state.active().size() + " active";
        label(matrices, footer, 16, 273, 8, MUTED);
        button(matrices, AmbientEffectsEditorLayout.DONE, "Done", true, mx, my);
        GuiCursors.draw(matrices, mouseX, mouseY, state.dragging() != null || interactive(mx, my));
    }
    private boolean interactive(double x, double y) {
        boolean row = false;
        for (int i = 0; i < state.rows().size(); i++) row |= AmbientEffectsEditorLayout.row(i).contains(x, y);
        return AmbientEffectsEditorLayout.ENABLED.contains(x, y)
            || (state.adding() || !state.available().isEmpty()) && AmbientEffectsEditorLayout.ADD.contains(x, y)
            || AmbientEffectsEditorLayout.DONE.contains(x, y) || row
            || !state.adding() && state.selected() != null && (AmbientEffectsEditorLayout.DENSITY.contains(x, y)
                || AmbientEffectsEditorLayout.SIZE.contains(x, y));
    }
    private void slider(MatrixStack matrices, String title, AmbientEffectsEditorLayout.Rect box,
                        String style, double mx, double my) {
        GuiSetting.Slider slider = AmbientEffectsSettings.slider(module, style, title);
        label(matrices, title, box.x(), box.y(), 8.3f, MUTED);
        String value = Math.round(slider.value() * 100) + "%";
        label(matrices, value, box.right() - Fonts.regular().getWidth(value, 8.3f), box.y(), 8.3f, TEXT);
        float trackY = box.y() + 25, handleX = box.x() + (float) slider.progress() * box.width();
        rectangle(matrices, new AmbientEffectsEditorLayout.Rect(box.x(), trackY, box.width(), 3), 1.5f, TRACK);
        rectangle(matrices, new AmbientEffectsEditorLayout.Rect(box.x(), trackY,
            (float) slider.progress() * box.width(), 3), 1.5f, ACCENT);
        rectangle(matrices, new AmbientEffectsEditorLayout.Rect(handleX - 3, trackY - 2, 6, 7), 3,
            box.contains(mx, my) || title.equals(state.dragging()) ? TEXT : ACCENT);
    }
    private void rectangle(MatrixStack matrices, AmbientEffectsEditorLayout.Rect box, float radius, Color color) {
        RenderUtil.RECT.draw(matrices, px(box.x()), py(box.y()), box.width() * view.scale(),
            box.height() * view.scale(), radius * view.scale(), color);
    }
    private void button(MatrixStack matrices, AmbientEffectsEditorLayout.Rect box, String text,
                        boolean active, double mx, double my) {
        rectangle(matrices, box, 5, active ? ACCENT : box.contains(mx, my) ? HOVER : CARD);
        centered(matrices, text, box, 8.1f, active ? TEXT : MUTED);
    }
    private void centered(MatrixStack matrices, String text, AmbientEffectsEditorLayout.Rect box, float size, Color color) {
        Fonts.regular().drawCenteredText(matrices, text, px(box.x() + box.width() / 2),
            py(box.y() + (box.height() - Fonts.regular().height(size)) / 2), size * view.scale(), color);
    }
    private void label(MatrixStack matrices, String text, float x, float y, float size, Color color) {
        Fonts.regular().drawText(matrices, text, px(x), py(y), size * view.scale(), color);
    }
    private float px(float value) { return view.x() + value * view.scale(); }
    private float py(float value) { return view.y() + value * view.scale(); }
    private void save() {
        if (changed) { ModuleManager.getInstance().saveNow(); changed = false; }
    }
    private void result(AmbientEffectsEditorState.Result result) {
        if (result == AmbientEffectsEditorState.Result.CLOSE) close();
        if (result == AmbientEffectsEditorState.Result.CHANGED) { changed = true; if (state.dragging() == null) save(); }
    }
    @Override public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT)
            result(state.click(view.localX(click.x()), view.localY(click.y())));
        return true;
    }
    @Override public boolean mouseDragged(Click click, double dx, double dy) {
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) changed |= state.drag(view.localX(click.x()));
        return true;
    }
    @Override public boolean mouseReleased(Click click) {
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) { state.release(); save(); }
        return true;
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        changed |= state.wheel(view.localX(x), view.localY(y), vertical); save(); return true;
    }
    @Override public boolean keyPressed(KeyInput input) {
        switch (input.key()) {
            case GLFW.GLFW_KEY_ESCAPE -> result(state.escape());
            case GLFW.GLFW_KEY_UP -> state.move(-1);
            case GLFW.GLFW_KEY_DOWN -> state.move(1);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { changed |= state.enter(); save(); }
            case GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE -> {
                if (!state.adding() && state.selected() != null) { changed |= state.remove(state.selected()); save(); }
            }
            case GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> state.openPicker();
            default -> { return super.keyPressed(input); }
        }
        return true;
    }
    @Override public void close() { save(); if (client != null) client.setScreen(parent); }
    @Override public void removed() {
        state.release(); save(); GuiRenderer.getInstance().clearScissors(); GuiCursors.restoreNative(client); super.removed();
    }
    @Override public boolean shouldPause() { return false; }
    @Override public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) { }
    @Override protected void applyBlur(DrawContext context) { }
}
