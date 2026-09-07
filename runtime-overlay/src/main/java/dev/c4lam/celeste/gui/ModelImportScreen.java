package dev.c4lam.celeste.gui;

import dev.c4lam.celeste.module.impl.visual.PlayerModelsFeature;
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
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** A local-path prompt using ordinary game widgets; no native dialog or upload. */
public final class ModelImportScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget pathField;
    private ImportButton importButton;
    private ImportButton cancelButton;
    private String validationMessage;
    private boolean submitted;
    private int panelX;
    private int panelY;
    private int panelWidth;

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
        panelWidth = Math.min(460, Math.max(120, width - 32));
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(8, (height - 218) / 2);
        int controlX = panelX + 18;
        int controlWidth = panelWidth - 36;
        pathField = new TextFieldWidget(textRenderer, controlX + 9, panelY + 86,
            controlWidth - 18, 16, Text.literal("Absolute local GLB file path"));
        pathField.setMaxLength(8192);
        pathField.setDrawsBackground(false);
        pathField.setPlaceholder(Text.literal("C:\\Models\\player.glb"));
        pathField.setText(previousPath);
        pathField.setChangedListener(ignored -> {
            validationMessage = null;
            refresh();
        });
        addDrawableChild(pathField);

        int buttonWidth = (controlWidth - 8) / 2;
        importButton = addDrawableChild(new ImportButton(controlX, panelY + 119, buttonWidth, 25,
            "Import", true, this::submit));
        cancelButton = addDrawableChild(new ImportButton(controlX + buttonWidth + 8,
            panelY + 119, controlWidth - buttonWidth - 8, 25, "Cancel", false, this::close));
        setInitialFocus(pathField);
        refresh();
    }

    private void refresh() {
        if (importButton == null || pathField == null) return;
        boolean importing = PlayerModelsFeature.importing();
        importButton.active = !importing && !pathField.getText().isBlank();
        importButton.setMessage(Text.literal(importing ? "Importing..." : "Import"));
        cancelButton.setMessage(Text.literal(submitted || importing ? "Back" : "Cancel"));
        pathField.setEditable(!importing);
    }

    private void submit() {
        if (pathField == null || PlayerModelsFeature.importing()) return;
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
        // The feature owns background parsing and marshals completion to the client thread.
        PlayerModelsFeature.importModel(path);
        refresh();
    }

    @Override
    public void tick() {
        super.tick();
        refresh();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xB009060C);
        filledCard(context, panelX, panelY, panelWidth, 218, 0xF5190F19);
        context.fill(panelX + 18, panelY + 12, panelX + 49, panelY + 14, 0xFFFF2B8F);
        context.drawTextWithShadow(textRenderer, title, panelX + 18, panelY + 24, 0xFFFAF3FA);
        context.drawTextWithShadow(textRenderer, "Import a model from this computer.",
            panelX + 18, panelY + 42, 0xFFB8A9B8);
        context.drawTextWithShadow(textRenderer, "Local .glb file", panelX + 18, panelY + 64, 0xFFB8A9B8);

        int fieldX = panelX + 18;
        int fieldWidth = panelWidth - 36;
        filledCard(context, fieldX, panelY + 77, fieldWidth, 31,
            pathField.isFocused() ? 0xFF6B294C : 0xFF382638);
        filledCard(context, fieldX + 1, panelY + 78, fieldWidth - 2, 29, 0xFF100B13);

        String status = validationMessage != null ? validationMessage : PlayerModelsFeature.status();
        int statusColor = validationMessage != null ? 0xFFFFB0C8 : 0xFFB8A9B8;
        int lineY = panelY + 156;
        int lineCount = 0;
        for (OrderedText line : textRenderer.wrapLines(Text.literal(status), fieldWidth)) {
            if (lineCount++ == 3) break;
            context.drawTextWithShadow(textRenderer, line, fieldX, lineY, statusColor);
            lineY += 10;
        }
        String help = PlayerModelsFeature.importing()
            ? "Import continues when you go back."
            : submitted ? "Esc returns to module settings."
            : "Paste a full path. Quoted paths are accepted.";
        context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(help, fieldWidth),
            fieldX, panelY + 198, 0xFF877787);
        super.render(context, mouseX, mouseY, delta);
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

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static void filledCard(DrawContext context, int x, int y, int width, int height, int color) {
        context.fill(x + 2, y, x + width - 2, y + height, color);
        context.fill(x, y + 2, x + width, y + height - 2, color);
    }

    /** Filled controls retain standard focus, narration and text-field keyboard behavior. */
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
            int color = !active ? 0xFF392536 : primary
                ? highlighted ? 0xFFFF2B8F : 0xFFBF2170
                : highlighted ? 0xFF493046 : 0xFF2D1C2D;
            if (isFocused()) {
                filledCard(context, getX() - 1, getY() - 1, width + 2, height + 2, 0xFFFAF3FA);
            }
            filledCard(context, getX(), getY(), width, height, color);
            context.drawCenteredTextWithShadow(textRenderer, getMessage(), getX() + width / 2,
                getY() + (height - 8) / 2, active ? 0xFFFAF3FA : 0xFF877787);
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
