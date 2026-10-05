package dev.c4lam.celeste.gui;

import dev.c4lam.celeste.config.InterfaceConfig;
import dev.c4lam.celeste.gui.model.GuiSetting;
import dev.c4lam.celeste.module.ModuleManager;
import dev.c4lam.celeste.module.impl.visual.BlockEspSettings;
import dev.c4lam.celeste.render.GuiRenderer;
import dev.c4lam.celeste.render.RenderUtil;
import dev.c4lam.celeste.render.font.Fonts;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;

/** Searchable multi-select block list; previews are Minecraft item models, never icon glyphs. */
public final class BlockPickerScreen extends Screen {
    private static final Color TEXT = new Color(250, 243, 250), MUTED = new Color(184, 169, 184);
    private static final Color ACCENT = new Color(255, 65, 153), PANEL = new Color(25, 15, 25, 236);
    private static final Color CARD_TOP = new Color(68, 65, 77, 190);
    private static final Color CARD_BOTTOM = new Color(49, 47, 60, 204);
    private static final Color CARD_HOVER_TOP = new Color(83, 79, 94, 205);
    private static final Color CARD_HOVER_BOTTOM = new Color(61, 58, 75, 218);
    private static final Color CARD_SELECTED_TOP = new Color(94, 83, 108, 212);
    private static final Color CARD_SELECTED_BOTTOM = new Color(67, 61, 87, 223);
    private static final float W = BlockPickerLayout.PANEL_WIDTH, H = BlockPickerLayout.PANEL_HEIGHT;
    private static final int ROWS = 6;
    private final Screen parent;
    private final GuiSetting.BlockList selection;
    private final boolean storage;
    private final List<Entry> all = new ArrayList<>();
    private List<Entry> filtered = List.of();
    private SearchField search;
    private float x, y, scale;
    private int first, keyboardRow;
    private boolean selectedOnly;
    private final RowScroll rowScroll = new RowScroll();
    private boolean draggingScrollbar;
    private float scrollbarGrab;
    private String status = "";
    private record Entry(String id, String name, ItemStack stack) { }

    private BlockPickerScreen(Screen parent, String title, GuiSetting.BlockList selection, boolean storage) {
        super(Text.literal(title));
        this.parent = parent;
        this.selection = selection;
        this.storage = storage;
        HashSet<String> seen = new HashSet<>();
        for (var item : Registries.ITEM) if (item instanceof BlockItem blockItem) {
            String id = Registries.BLOCK.getId(blockItem.getBlock()).toString();
            if ((!storage || BlockEspSettings.STORAGE_OPTIONS.contains(id)) && seen.add(id)) {
                ItemStack stack = new ItemStack(item);
                all.add(new Entry(id, stack.getName().getString(), stack));
            }
        }
        all.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::id));
    }
    public static void open(String title, GuiSetting.BlockList selection, boolean storage) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.setScreen(new BlockPickerScreen(client.currentScreen, title, selection, storage));
    }
    @Override protected void init() {
        draggingScrollbar = false;
        rowScroll.reset();
        String text = search == null ? "" : search.getText();
        float preferred = (8f / 3f) / (float) client.getWindow().getScaleFactor()
            * InterfaceConfig.scale() / InterfaceConfig.DEFAULT_SCALE;
        scale = Math.max(.05f, Math.min(preferred, Math.min((width - 16f) / W, (height - 16f) / H)));
        x = (width - W * scale) / 2; y = (height - H * scale) / 2;
        BlockPickerLayout.Rect searchBounds = BlockPickerLayout.SEARCH;
        search = new SearchField(px(searchBounds.x()), py(searchBounds.y()),
            size(searchBounds.width()), size(searchBounds.height()));
        search.setMaxLength(96);
        search.setDrawsBackground(false);
        search.setText(text);
        search.setChangedListener(ignored -> { first = keyboardRow = 0; rowScroll.reset(); refresh(); });
        addDrawableChild(search);
        setInitialFocus(search);
        refresh();
        GuiCursors.hideNative(client);
    }
    private void refresh() {
        String query = search == null ? "" : search.getText();
        filtered = all.stream().filter(entry -> (!selectedOnly || selection.contains(entry.id))
            && BlockPickerSearch.rank(query, entry.name, entry.id) >= 0)
            .sorted(Comparator.comparingInt(entry -> BlockPickerSearch.rank(query, entry.name, entry.id))).toList();
        first = Math.max(0, Math.min(first, Math.max(0, filtered.size() - ROWS)));
        keyboardRow = Math.max(0, Math.min(keyboardRow, Math.max(0, filtered.size() - 1)));
    }
    private void changed() {
        ModuleManager.getInstance().saveNow();
        status = "";
        refresh();
    }
    private void toggle(Entry entry) {
        if (selection.toggle(entry.id)) changed();
        else status = "You can select up to 256 blocks.";
    }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        MatrixStack matrices = RenderUtil.matrices();
        matrices.loadIdentity();
        GuiRenderer.getInstance().clearScissors();
        RenderUtil.captureBlur();
        // Keep the configured glass card, but leave the world untouched. A
        // picker is a lightweight editor, not a modal blackout.
        RenderUtil.BLUR_RECT.draw(matrices, x, y, W * scale, H * scale, new Vector4f(13 * scale),
            PANEL, PANEL, PANEL, PANEL, InterfaceConfig.glassTintMix());
        glass(matrices, 16, 51, 378, 25, 4, search != null && search.isFocused(), false);
        if (storage) {
            glass(matrices, 16, 86, 82, 22, 4, hit(mouseX, mouseY, 16, 86, 82, 22), false);
            glass(matrices, 104, 86, 70, 22, 4, hit(mouseX, mouseY, 104, 86, 70, 22), false);
        }
        glass(matrices, 180, 86, 106, 22, 4,
            hit(mouseX, mouseY, 180, 86, 106, 22), selectedOnly);
        glass(matrices, 292, 86, 102, 22, 4, hit(mouseX, mouseY, 292, 86, 102, 22), false);
        glass(matrices, 326, 312, 68, 22, 4, hit(mouseX, mouseY, 326, 312, 68, 22), true);
        for (int row = 0; row < ROWS && first + row < filtered.size(); row++) {
            int index = first + row;
            float rowY = 115 + row * 31;
            glass(matrices, 16, rowY, 378, 29, 3,
                hit(mouseX, mouseY, 16, rowY, 378, 29) || index == keyboardRow,
                selection.contains(filtered.get(index).id));
        }
        // Glass is committed before Minecraft draws the item models. Celeste
        // text is added afterward so neither pass obscures the previews.
        GuiRenderer.getInstance().flush();
        label(matrices, getTitle().getString(), 16, 14, 13.5f, TEXT);
        label(matrices, storage ? "Choose the storage blocks to show." : "Choose the blocks to show.",
            16, 34, 8.1f, MUTED);
        if (storage) {
            button(matrices, "Containers", 16, 86, 82, false);
            button(matrices, "Work blocks", 104, 86, 70, false);
        } else labelCenteredY(matrices, filtered.size() + " blocks", 16, 86,
            BlockPickerLayout.BUTTON_HEIGHT, 8, MUTED);
        button(matrices, selectedOnly ? "All blocks" : "Selected", 180, 86, 106, selectedOnly);
        button(matrices, "Clear", 292, 86, 102, false);
        for (int row = 0; row < ROWS && first + row < filtered.size(); row++) {
            int index = first + row;
            Entry entry = filtered.get(index);
            float rowY = 115 + row * 31;
            boolean selected = selection.contains(entry.id);
            context.getMatrices().pushMatrix();
            context.getMatrices().translate(px(23), py(rowY + 6));
            context.getMatrices().scale(1.06f * scale, 1.06f * scale);
            context.drawItem(entry.stack, 0, 0);
            context.getMatrices().popMatrix();
            float nameSize = 8.4F, idSize = 6.4F, lineGap = 1.2F;
            float pairY = BlockPickerLayout.centeredPairY(rowY, BlockPickerLayout.ROW_HEIGHT,
                Fonts.regular().height(nameSize), Fonts.regular().height(idSize), lineGap);
            label(matrices, fit(entry.name, 255, nameSize), 51, pairY, nameSize, TEXT);
            label(matrices, fit(entry.id, 255, idSize), 51,
                pairY + Fonts.regular().height(nameSize) + lineGap, idSize, MUTED);
            labelRightCenteredY(matrices, selected ? "Selected" : "Add", 378, rowY,
                BlockPickerLayout.ROW_HEIGHT, 7.5F, selected ? ACCENT : MUTED);
        }
        if (filtered.isEmpty()) label(matrices, "No blocks found.", 23, 134, 8.5f, MUTED);
        if (filtered.size() > ROWS) {
            float thumb = RowScroll.thumbHeight(filtered.size(), ROWS, 184);
            float top = 115 + RowScroll.thumbTop(first, filtered.size(), ROWS, 184);
            RenderUtil.RECT.draw(matrices, px(400), py(top), 2 * scale, thumb * scale, scale, MUTED);
        }
        labelCenteredY(matrices, status.isEmpty() ? selection.ids().size() + " selected" : status,
            16, 312, BlockPickerLayout.BUTTON_HEIGHT, 8, status.isEmpty() ? MUTED : ACCENT);
        button(matrices, "Done", 326, 312, 68, true);
        super.render(context, mouseX, mouseY, delta);
        GuiCursors.draw(matrices, mouseX, mouseY, hit(mouseX, mouseY, 16, 86, 378, 215));
    }
    private void label(MatrixStack matrices, String text, float lx, float ly, float size, Color color) {
        Fonts.regular().drawText(matrices, text, px(lx), py(ly), size * scale, color);
    }
    private void labelCenteredY(MatrixStack matrices, String text, float lx, float top,
                                float height, float size, Color color) {
        label(matrices, text, lx, BlockPickerLayout.centeredTextY(top, height,
            Fonts.regular().height(size)), size, color);
    }
    private void labelRightCenteredY(MatrixStack matrices, String text, float right, float top,
                                     float height, float size, Color color) {
        float localWidth = Fonts.regular().width(text, size);
        labelCenteredY(matrices, text, right - localWidth, top, height, size, color);
    }
    private String fit(String value, float width, float size) {
        if (Fonts.regular().getWidth(value, size) <= width) return value;
        int end = value.length();
        while (end > 0 && Fonts.regular().getWidth(value.substring(0, end) + "...", size) > width)
            end = value.offsetByCodePoints(end, -1);
        return value.substring(0, end) + "...";
    }
    private void button(MatrixStack matrices, String label, float lx, float ly, float w,
                        boolean active) {
        float textY = BlockPickerLayout.centeredTextY(ly, BlockPickerLayout.BUTTON_HEIGHT,
            Fonts.regular().height(8.0F));
        Fonts.regular().drawCenteredText(matrices, label, px(lx + w / 2), py(textY),
            8 * scale, active ? TEXT : MUTED);
    }
    private void glass(MatrixStack matrices, float lx, float ly, float width, float height,
                       float radius, boolean hover, boolean selected) {
        Color top = selected ? CARD_SELECTED_TOP : hover ? CARD_HOVER_TOP : CARD_TOP;
        Color bottom = selected ? CARD_SELECTED_BOTTOM : hover ? CARD_HOVER_BOTTOM : CARD_BOTTOM;
        RenderUtil.BLUR_RECT.draw(matrices, px(lx), py(ly), width * scale, height * scale,
            new Vector4f(radius * scale), top, top, bottom, bottom, InterfaceConfig.glassTintMix());
    }
    @Override public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (hit(click.x(), click.y(), 326, 312, 68, 22)) { close(); return true; }
            if (hit(click.x(), click.y(), 180, 86, 106, 22)) {
                selectedOnly = !selectedOnly; first = keyboardRow = 0; rowScroll.reset(); refresh(); return true;
            }
            if (hit(click.x(), click.y(), 292, 86, 102, 22)) { selection.setIds(List.of()); changed(); return true; }
            int preset = storage ? BlockPickerSearch.storagePresetAt(
                (click.x() - x) / scale, (click.y() - y) / scale) : -1;
            if (preset >= 0) {
                selection.setIds(preset == 0 ? BlockEspSettings.CONTAINERS : BlockEspSettings.WORKSTATIONS);
                changed(); return true;
            }
            if (filtered.size() > ROWS && hit(click.x(), click.y(), 395, 115, 11, 184)) {
                float localY = (float) ((click.y() - y) / scale) - 115;
                float thumb = RowScroll.thumbHeight(filtered.size(), ROWS, 184);
                float top = RowScroll.thumbTop(first, filtered.size(), ROWS, 184);
                scrollbarGrab = localY >= top && localY <= top + thumb ? localY - top : thumb / 2;
                draggingScrollbar = true;
                dragScrollbar(click.y());
                return true;
            }
            for (int row = 0; row < ROWS && first + row < filtered.size(); row++) {
                if (hit(click.x(), click.y(), 16, 115 + row * 31, 378, 29)) {
                    keyboardRow = first + row; toggle(filtered.get(keyboardRow)); return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }
    @Override public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (vertical != 0 && hit(mx, my, 16, 115, 390, 184)) {
            first = rowScroll.wheel(first, filtered.size(), ROWS, vertical);
            keyboardRow = Math.max(first, Math.min(first + ROWS - 1, keyboardRow));
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }
    private void dragScrollbar(double mouseY) {
        float top = (float) ((mouseY - y) / scale) - 115 - scrollbarGrab;
        first = RowScroll.fromThumb(top, filtered.size(), ROWS, 184);
        keyboardRow = Math.max(first, Math.min(first + ROWS - 1, keyboardRow));
        rowScroll.reset();
    }
    @Override public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (draggingScrollbar && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            dragScrollbar(click.y());
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }
    @Override public boolean mouseReleased(Click click) {
        if (draggingScrollbar && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            dragScrollbar(click.y());
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(click);
    }
    @Override public boolean keyPressed(KeyInput input) {
        if (!filtered.isEmpty()) {
            if (input.key() == GLFW.GLFW_KEY_UP || input.key() == GLFW.GLFW_KEY_DOWN
                || input.key() == GLFW.GLFW_KEY_PAGE_UP || input.key() == GLFW.GLFW_KEY_PAGE_DOWN) {
                int step = input.key() == GLFW.GLFW_KEY_PAGE_UP ? -ROWS
                    : input.key() == GLFW.GLFW_KEY_PAGE_DOWN ? ROWS
                    : input.key() == GLFW.GLFW_KEY_UP ? -1 : 1;
                keyboardRow = Math.max(0, Math.min(filtered.size() - 1,
                    keyboardRow + step));
                first = Math.min(first, keyboardRow);
                first = Math.max(first, keyboardRow - ROWS + 1);
                rowScroll.reset();
                return true;
            }
            if (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) {
                toggle(filtered.get(keyboardRow)); return true;
            }
            if (input.key() == GLFW.GLFW_KEY_TAB) { search.setText(filtered.get(keyboardRow).name); return true; }
        }
        return super.keyPressed(input);
    }
    @Override public void close() { if (client != null) client.setScreen(parent); }
    @Override public void removed() {
        ModuleManager.getInstance().saveNow();
        GuiRenderer.getInstance().clearScissors();
        GuiCursors.restoreNative(client);
        super.removed();
    }
    @Override public boolean shouldPause() { return false; }
    @Override public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) { }
    @Override protected void applyBlur(DrawContext context) { }
    private int px(float value) { return Math.round(x + value * scale); }
    private int py(float value) { return Math.round(y + value * scale); }
    private int size(float value) { return Math.max(1, Math.round(value * scale)); }
    private boolean hit(double mx, double my, float lx, float ly, float w, float h) {
        return mx >= px(lx) && my >= py(ly) && mx <= px(lx + w) && my <= py(ly + h);
    }

    private final class SearchField extends TextFieldWidget {
        private final ModelImportTextLayout layout = new ModelImportTextLayout();
        private int anchor;
        private float offset;
        SearchField(int x, int y, int w, int h) {
            super(BlockPickerScreen.this.textRenderer, x, y, w, h, Text.literal("Search blocks"));
        }
        @Override public void setSelectionEnd(int position) { super.setSelectionEnd(position); anchor = position; }
        private void layout() {
            layout.update(getText(), 9 * scale, text -> Fonts.regular().getWidth(text, 9 * scale));
            float visible = Math.max(1, width - 22 * scale);
            offset = Math.max(0, Math.min(offset, Math.max(0, layout.width() - visible)));
            if (layout.x(getCursor()) < offset) offset = layout.x(getCursor());
            if (layout.x(getCursor()) > offset + visible) offset = layout.x(getCursor()) - visible;
        }
        private int cursorAt(Click click) {
            layout(); return layout.nearest((float) click.x() - getX() - 10 * scale + offset);
        }
        @Override public void onClick(Click click, boolean doubled) {
            if (doubled) { setCursor(0, false); setCursorToEnd(true); }
            else setCursor(cursorAt(click), click.hasShift());
        }
        @Override protected void onDrag(Click click, double dx, double dy) { setCursor(cursorAt(click), true); }
        @Override public void renderWidget(DrawContext context, int mx, int my, float delta) {
            MatrixStack matrices = RenderUtil.matrices();
            layout();
            float textSize = 9.0F * scale;
            float tx = getX() + 10 * scale;
            float ty = getY() + Math.max(0.0F, height - Fonts.regular().height(textSize)) * 0.5F;
            GuiRenderer renderer = GuiRenderer.getInstance();
            renderer.pushScissor(tx, getY(), width - 20 * scale, height);
            try {
                float caret = tx + layout.x(getCursor()) - offset;
                if (isFocused() && anchor != getCursor()) {
                    float other = tx + layout.x(Math.max(0, Math.min(getText().length(), anchor))) - offset;
                    RenderUtil.RECT.draw(matrices, Math.min(caret, other), ty - scale,
                        Math.abs(caret - other), 12 * scale, 2 * scale, new Color(255, 65, 153, 60));
                }
                if (getText().isEmpty()) Fonts.regular().drawText(matrices, "Search by name or ID", tx, ty, textSize, MUTED);
                else {
                    int start = layout.floor(offset), end = layout.ceiling(offset + width - 20 * scale);
                    Fonts.regular().drawText(matrices, getText().substring(start, end), tx + layout.drawX(start) - offset,
                        ty, textSize, TEXT);
                }
                if (isFocused() && System.nanoTime() / 500_000_000L % 2 == 0)
                    RenderUtil.RECT.draw(matrices, caret, ty, Math.max(.6f, scale), 10 * scale, 0, ACCENT);
            } finally { renderer.popScissor(); }
        }
    }
}
