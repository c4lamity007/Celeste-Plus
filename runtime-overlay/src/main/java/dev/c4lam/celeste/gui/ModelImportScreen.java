package dev.c4lam.celeste.gui;

import dev.c4lam.celeste.config.InterfaceConfig;
import dev.c4lam.celeste.render.GuiRenderer;
import dev.c4lam.celeste.render.RenderUtil;
import dev.c4lam.celeste.render.font.Font;
import dev.c4lam.celeste.render.font.Fonts;
import java.awt.Color;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Vector4f;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Local model import, painted entirely with Celeste's rounded/MSDF GUI renderer. */
public final class ModelImportScreen extends Screen {
    private static final Color TEXT = new Color(246, 243, 248);
    private static final Color MUTED = new Color(203, 199, 211);
    private static final Color DIM = new Color(177, 171, 187);
    private static final Color ACCENT = new Color(255, 55, 150);
    private static final Color ERROR = new Color(255, 163, 186);
    private static final Color PANEL = new Color(25, 15, 25, 246);
    private static final Color CARD_TOP = new Color(20, 12, 22, 210);
    private static final Color CARD_BOTTOM = new Color(8, 5, 10, 232);
    private static final Color CARD_HOVER_TOP = new Color(25, 16, 27, 214);
    private static final Color CARD_HOVER_BOTTOM = new Color(10, 6, 12, 235);
    private static final float PANEL_WIDTH = 438;
    private static final float PANEL_HEIGHT = 238;
    private final Screen parent;
    private PathField pathField;
    private ImportButton importButton;
    private ImportButton cancelButton;
    private String validationMessage;
    private boolean submitted;
    private float panelX;
    private float panelY;
    private float uiScale = 1;

    private ModelImportScreen(Screen parent) {
        super(Text.literal("Load player model"));
        this.parent = parent;
    }

    public static void open() {
        MinecraftClient client = MinecraftClient.getInstance();
        client.setScreen(new ModelImportScreen(client.currentScreen));
    }

    @Override
    protected void init() {
        String previousPath = pathField == null ? "" : pathField.getText();
        // Same physical-pixel reference as the main GUI, independent of Minecraft GUI scale.
        float gameScale = client == null ? 4 : Math.max(0.1f, (float) client.getWindow().getScaleFactor());
        float preferredScale = (8.0f / 3.0f) / gameScale
            * (InterfaceConfig.scale() / InterfaceConfig.DEFAULT_SCALE);
        uiScale = Math.max(0.05f, Math.min(preferredScale,
            Math.min((width - 16.0f) / PANEL_WIDTH, (height - 16.0f) / PANEL_HEIGHT)));
        panelX = (width - PANEL_WIDTH * uiScale) / 2;
        panelY = (height - PANEL_HEIGHT * uiScale) / 2;
        pathField = new PathField(px(24), py(90), size(390), size(36));
        pathField.setMaxLength(8192);
        pathField.setDrawsBackground(false);
        pathField.setText(previousPath);
        pathField.setChangedListener(ignored -> {
            validationMessage = null;
            refresh();
        });
        addDrawableChild(pathField);
        cancelButton = addDrawableChild(new ImportButton(px(24), py(146), size(94), size(34),
            "Cancel", false, this::close));
        importButton = addDrawableChild(new ImportButton(px(128), py(146), size(286), size(34),
            "Load model", true, this::submit));
        setInitialFocus(pathField);
        GuiCursors.hideNative(client);
        refresh();
    }

    private int px(float x) { return Math.round(panelX + x * uiScale); }
    private int py(float y) { return Math.round(panelY + y * uiScale); }
    private int size(float value) { return Math.max(1, Math.round(value * uiScale)); }

    private void refresh() {
        if (importButton == null || pathField == null) return;
        boolean importing = PlayerModelSelection.importing();
        importButton.active = !importing && !pathField.getText().isBlank();
        importButton.setMessage(Text.literal(importing ? "Loading model..." : "Load model"));
        cancelButton.setMessage(Text.literal(submitted || importing ? "Back" : "Cancel"));
        pathField.setEditable(!importing);
    }

    private void submit() {
        if (pathField == null || PlayerModelSelection.importing()) return;
        String path = pathField.getText().strip();
        if (path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"")) {
            path = path.substring(1, path.length() - 1).strip();
        }
        try {
            if (path.isEmpty() || !Path.of(path).isAbsolute()) {
                validationMessage = "Enter an absolute local path, such as C:\\Models\\player.glb.";
                return;
            }
            if (path.startsWith("\\\\") || path.startsWith("//")) {
                validationMessage = "Choose a file on this computer, not a network path.";
                return;
            }
            if (!path.toLowerCase(Locale.ROOT).endsWith(".glb")) {
                validationMessage = "Choose a binary glTF model with the .glb extension.";
                return;
            }
        } catch (InvalidPathException exception) {
            validationMessage = "That path is not valid. Copy the file's full local path and try again.";
            return;
        }
        validationMessage = null;
        submitted = true;
        // Background parsing and replacement/retirement remain owned by the feature.
        PlayerModelSelection.importModel(path);
        refresh();
    }

    @Override
    public void tick() {
        super.tick();
        refresh();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        MatrixStack matrices = RenderUtil.matrices();
        matrices.loadIdentity();
        GuiRenderer.getInstance().clearScissors();
        RenderUtil.captureBlur();
        RenderUtil.BLUR_RECT.draw(matrices, panelX, panelY, PANEL_WIDTH * uiScale,
            PANEL_HEIGHT * uiScale, 14 * uiScale, PANEL, InterfaceConfig.glassTintMix());
        label(matrices, "PLAYER MODELS", 24, 20, 7.4f, MUTED, false);
        label(matrices, "Load player model", 24, 39, 17, TEXT, true);
        drawSecondaryGlass(matrices, px(372), py(20), 42 * uiScale, 18 * uiScale, 5 * uiScale, false);
        Fonts.semibold().drawCenteredText(matrices, "GLB", px(393), py(24), 7.4f * uiScale, ACCENT);
        label(matrices, "Paste a local file path", 24, 69, 7.3f, DIM, false);
        drawStatus(matrices, validationMessage != null ? validationMessage : PlayerModelSelection.status(),
            validationMessage != null ? ERROR : MUTED);
        super.render(context, mouseX, mouseY, delta);
        GuiCursors.draw(matrices, mouseX, mouseY,
            importButton.active && importButton.isMouseOver(mouseX, mouseY)
                || cancelButton.isMouseOver(mouseX, mouseY));
    }

    private void label(MatrixStack matrices, String text, float x, float y, float fontSize,
                       Color color, boolean bold) {
        (bold ? Fonts.semibold() : Fonts.regular()).drawText(matrices, text, px(x), py(y),
            fontSize * uiScale, color);
    }

    private void drawSecondaryGlass(MatrixStack matrices, float x, float y, float width,
                                    float height, float radius, boolean highlighted) {
        Color top = highlighted ? CARD_HOVER_TOP : CARD_TOP;
        Color bottom = highlighted ? CARD_HOVER_BOTTOM : CARD_BOTTOM;
        RenderUtil.BLUR_RECT.draw(matrices, x, y, width, height, new Vector4f(radius),
            top, top, bottom, bottom, InterfaceConfig.glassTintMix());
    }

    private void drawStatus(MatrixStack matrices, String status, Color color) {
        if (status == null || status.isBlank()) return;
        Font font = Fonts.regular();
        float fontSize = 8.1f * uiScale;
        float availableWidth = 390 * uiScale;
        String remaining = status.strip();
        for (int line = 0; line < 3 && !remaining.isEmpty(); line++) {
            int end = remaining.length();
            while (end > 0 && font.getWidth(remaining.substring(0, end), fontSize) > availableWidth) {
                end = remaining.offsetByCodePoints(end, -1);
            }
            if (end == 0) break;
            if (end < remaining.length() && line < 2) {
                int space = remaining.lastIndexOf(' ', end);
                if (space > 0) end = space;
            }
            String text = remaining.substring(0, end).stripTrailing();
            if (line == 2 && end < remaining.length()) {
                while (!text.isEmpty() && font.getWidth(text + "...", fontSize) > availableWidth) {
                    text = text.substring(0, text.offsetByCodePoints(text.length(), -1));
                }
                text += "...";
            }
            font.drawText(matrices, text, px(24), py(195 + line * 11), fontSize, color);
            remaining = remaining.substring(end).stripLeading();
        }
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (pathField != null && pathField.isFocused()
            && (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER)) {
            if (importButton.active) submit();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) { }
    @Override protected void applyBlur(DrawContext context) { }
    @Override public boolean shouldPause() { return false; }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    @Override
    public void removed() {
        GuiRenderer.getInstance().clearScissors();
        GuiCursors.restoreNative(client);
        RenderUtil.releaseBlur();
        super.removed();
    }

    /** Keep Minecraft's editing, clipboard and narration, not its font or field rendering. */
    private final class PathField extends TextFieldWidget {
        private final ModelImportTextLayout textLayout = new ModelImportTextLayout();
        private int selectionAnchor;
        private float horizontalOffset;
        private boolean editable = true;
        private long caretResetAt = System.nanoTime();

        private PathField(int x, int y, int width, int height) {
            super(ModelImportScreen.this.textRenderer, x, y, width, height,
                Text.literal("Absolute local GLB file path"));
        }

        @Override
        public void setEditable(boolean editable) {
            super.setEditable(editable);
            this.editable = editable;
        }

        @Override
        public void setSelectionEnd(int position) {
            super.setSelectionEnd(position);
            selectionAnchor = Math.max(0, Math.min(getText().length(), position));
        }

        @Override
        public void setSelectionStart(int position) {
            super.setSelectionStart(position);
            caretResetAt = System.nanoTime();
        }

        private void updateLayout() {
            float fontSize = 9.3f * uiScale;
            textLayout.update(getText(), fontSize, text -> Fonts.regular().getWidth(text, fontSize));
            float viewWidth = Math.max(1, width - 26 * uiScale);
            float caret = textLayout.x(getCursor());
            if (caret < horizontalOffset) horizontalOffset = caret;
            if (caret > horizontalOffset + viewWidth) horizontalOffset = caret - viewWidth;
            horizontalOffset = Math.max(0, Math.min(horizontalOffset, textLayout.width() - viewWidth));
        }

        private int cursorAt(Click click) {
            updateLayout();
            return textLayout.nearest((float) click.x() - getX() - 12 * uiScale + horizontalOffset);
        }

        @Override
        public void onClick(Click click, boolean doubled) {
            if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
            int cursor = cursorAt(click);
            if (doubled) {
                // A path is one value; double-click selects it for quick replacement.
                setCursor(0, false);
                setCursorToEnd(true);
            } else {
                setCursor(cursor, click.hasShift());
            }
        }

        @Override
        protected void onDrag(Click click, double deltaX, double deltaY) {
            setCursor(cursorAt(click), true);
        }

        @Override
        public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            MatrixStack matrices = RenderUtil.matrices();
            drawSecondaryGlass(matrices, getX(), getY(), width, height, 7 * uiScale, isFocused());
            updateLayout();
            float textX = getX() + 12 * uiScale;
            float fontSize = 9.3f * uiScale;
            float textY = getY() + (height - Fonts.regular().getHeight(fontSize)) / 2;
            GuiRenderer renderer = GuiRenderer.getInstance();
            renderer.pushScissor(textX, getY() + 4 * uiScale, width - 24 * uiScale, height - 8 * uiScale);
            try {
                float cursorX = textX + textLayout.x(getCursor()) - horizontalOffset;
                float anchorX = textX + textLayout.x(selectionAnchor) - horizontalOffset;
                if (isFocused() && getCursor() != selectionAnchor) {
                    RenderUtil.RECT.draw(matrices, Math.min(cursorX, anchorX), textY - uiScale,
                        Math.abs(cursorX - anchorX), Fonts.regular().getHeight(fontSize) + 2 * uiScale,
                        2 * uiScale, new Color(255, 55, 150, 65));
                }
                if (getText().isEmpty()) {
                    Fonts.regular().drawText(matrices, "C:\\Models\\player.glb", textX, textY, fontSize, DIM);
                } else {
                    int start = textLayout.floor(horizontalOffset);
                    int end = textLayout.ceiling(horizontalOffset + width - 24 * uiScale);
                    Fonts.regular().drawText(matrices, getText().substring(start, end),
                        textX + textLayout.drawX(start) - horizontalOffset, textY, fontSize, editable ? TEXT : MUTED);
                }
                if (editable && isFocused() && (System.nanoTime() - caretResetAt) / 500_000_000L % 2 == 0) {
                    RenderUtil.RECT.draw(matrices, cursorX, textY, Math.max(0.6f, uiScale),
                        Fonts.regular().getHeight(fontSize), 0, ACCENT);
                }
            } finally {
                renderer.popScissor();
            }
        }
    }

    /** Custom surfaces/text, with standard keyboard focus and narration. */
    private final class ImportButton extends ClickableWidget {
        private final boolean primary;
        private final Runnable action;

        private ImportButton(int x, int y, int width, int height, String label, boolean primary, Runnable action) {
            super(x, y, width, height, Text.literal(label));
            this.primary = primary;
            this.action = action;
        }

        @Override
        protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            boolean highlighted = hovered || isFocused();
            MatrixStack matrices = RenderUtil.matrices();
            if (primary && active) {
                RenderUtil.RECT.draw(matrices, getX(), getY(), width, height, 7 * uiScale,
                    highlighted ? new Color(255, 84, 166) : ACCENT);
            } else {
                drawSecondaryGlass(matrices, getX(), getY(), width, height, 7 * uiScale,
                    active && highlighted);
            }
            float fontSize = 9.2f * uiScale;
            Fonts.semibold().drawCenteredText(matrices, getMessage().getString(), getX() + width / 2.0f,
                getY() + (height - Fonts.semibold().getHeight(fontSize)) / 2,
                fontSize, active ? TEXT : DIM);
            if (isFocused()) {
                RenderUtil.RECT.draw(matrices, getX() + width / 2.0f - 9 * uiScale,
                    getY() + height - 5 * uiScale, 18 * uiScale, uiScale, uiScale / 2, TEXT);
            }
        }

        @Override
        public void onClick(Click click, boolean doubled) {
            if (active && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) action.run();
        }

        @Override
        public boolean keyPressed(KeyInput input) {
            if (active && isFocused() && (input.key() == GLFW.GLFW_KEY_ENTER
                || input.key() == GLFW.GLFW_KEY_KP_ENTER || input.key() == GLFW.GLFW_KEY_SPACE)) {
                action.run();
                return true;
            }
            return super.keyPressed(input);
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder) {
            appendDefaultNarrations(builder);
        }
    }
}
