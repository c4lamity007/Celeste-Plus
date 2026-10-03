package dev.c4lam.celeste.gui;

import dev.c4lam.celeste.config.InterfaceConfig;
import dev.c4lam.celeste.gui.model.GuiModule;
import dev.c4lam.celeste.gui.model.GuiSetting;
import dev.c4lam.celeste.module.ModuleManager;
import dev.c4lam.celeste.gui.HandModelsLayout;
import dev.c4lam.celeste.gui.HandModelsLayout.Position;
import dev.c4lam.celeste.render.GuiRenderer;
import dev.c4lam.celeste.render.RenderUtil;
import dev.c4lam.celeste.render.font.Fonts;
import java.awt.Color;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Arm;
import org.lwjgl.glfw.GLFW;

/** A small HUD-style editor over the actual first-person held-item pass. */
public final class HandModelsEditorScreen extends Screen {
    private static final Color SURFACE = new Color(27, 26, 32, 235);
    private static final Color TEXT = new Color(246, 243, 248);
    private static final Color MUTED = new Color(167, 160, 178);
    private static final Color ACCENT = new Color(255, 61, 150);
    private final Screen parent;
    private final GuiModule module;
    private Position right;
    private Position left;
    private double rightSize;
    private double leftSize;
    private Perspective originalPerspective;
    private boolean originalHudHidden;
    private Boolean draggingRight;
    private Boolean selectedRight;
    private Position dragStart;
    private double startMouseX;
    private double startMouseY;
    private boolean changed;
    private float scale = 1;

    private HandModelsEditorScreen(Screen parent, GuiModule module) {
        super(Text.literal("Hand Models"));
        this.parent = parent;
        this.module = module;
        right = read(true);
        left = read(false);
        rightSize = HandModelsLayout.size(slider(true, "Size").value());
        leftSize = HandModelsLayout.size(slider(false, "Size").value());
    }

    public static void open() {
        MinecraftClient client = MinecraftClient.getInstance();
        GuiModule module = ModuleManager.getInstance().module("Hand Models");
        if (module != null && client.player != null && client.world != null) {
            client.setScreen(new HandModelsEditorScreen(client.currentScreen, module));
        }
    }

    @Override protected void init() {
        draggingRight = null;
        if (originalPerspective == null) {
            originalPerspective = client.options.getPerspective();
            originalHudHidden = client.options.hudHidden;
        }
        client.options.setPerspective(Perspective.FIRST_PERSON);
        client.options.hudHidden = false;
        scale = EditorViewport.scale((8f / 3f) / (float) client.getWindow().getScaleFactor()
            * InterfaceConfig.scale() / InterfaceConfig.DEFAULT_SCALE, width, height);
        preview();
        GuiCursors.hideNative(client);
    }

    private Position read(boolean isRight) {
        return HandModelsLayout.bounded(isRight, slider(isRight, "X").value(), slider(isRight, "Y").value());
    }

    private GuiSetting.Slider slider(boolean isRight, String axis) {
        String name = (isRight ? "Right " : "Left ") + axis;
        for (GuiSetting setting : module.settings()) {
            if (setting instanceof GuiSetting.Slider slider && setting.label().equals(name)) return slider;
        }
        throw new IllegalStateException("Hand Models position setting missing: " + name);
    }

    private void preview() {
        HandModelPreview.setPreviewPosition(true, right.x(), right.y());
        HandModelPreview.setPreviewPosition(false, left.x(), left.y());
        HandModelPreview.setPreviewSize(true, rightSize);
        HandModelPreview.setPreviewSize(false, leftSize);
    }

    private void savePositions() {
        if (!changed) return;
        slider(true, "X").setValue(right.x());
        slider(true, "Y").setValue(right.y());
        slider(false, "X").setValue(left.x());
        slider(false, "Y").setValue(left.y());
        slider(true, "Size").setValue(rightSize);
        slider(false, "Size").setValue(leftSize);
        ModuleManager.getInstance().saveNow();
        changed = false;
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        MatrixStack matrices = RenderUtil.matrices();
        matrices.loadIdentity();
        GuiRenderer.getInstance().clearScissors();
        float panelWidth = 302 * scale;
        float panelX = (width - panelWidth) / 2;
        RenderUtil.RECT.draw(matrices, panelX, 12 * scale, panelWidth, 53 * scale, 10 * scale, SURFACE);
        Fonts.semibold().drawCenteredText(matrices, "Hand Models", width / 2f, 23 * scale, 12 * scale, TEXT);
        Fonts.regular().drawCenteredText(matrices, "Drag to snap on the arc · Scroll to resize", width / 2f,
            43 * scale, 8 * scale, MUTED);
        Boolean hoveredHand = hit(mouseX, mouseY);
        String leftText = labelText(false);
        String rightText = labelText(true);
        float leftWidth = Fonts.regular().getWidth(leftText, 7.5f * scale) + 16 * scale;
        float rightWidth = Fonts.regular().getWidth(rightText, 7.5f * scale) + 16 * scale;
        EditorViewport.Labels labels = EditorViewport.labels((float) (left.x() * width),
            (float) (left.y() * height + 24 * scale), leftWidth, (float) (right.x() * width),
            (float) (right.y() * height + 24 * scale), rightWidth, width, height, scale, !module.enabled());
        label(matrices, false, leftText, leftWidth, labels.left(), hoveredHand);
        label(matrices, true, rightText, rightWidth, labels.right(), hoveredHand);
        float bottom = height - 44 * scale;
        button(matrices, "Reset", width / 2f - 82 * scale, bottom, 75 * scale, mouseX, mouseY, false);
        button(matrices, "Done", width / 2f + 7 * scale, bottom, 75 * scale, mouseX, mouseY, true);
        if (!module.enabled()) Fonts.regular().drawCenteredText(matrices,
            "Positions saved — enable Hand Models to preview in game", width / 2f,
            bottom - 13 * scale, 7 * scale, MUTED);
        GuiCursors.draw(matrices, mouseX, mouseY, draggingRight != null || hoveredHand != null);
    }

    private String labelText(boolean isRight) {
        boolean hasItem = client.player != null && !(client.player.getMainArm() == (isRight ? Arm.RIGHT : Arm.LEFT)
            ? client.player.getMainHandStack() : client.player.getOffHandStack()).isEmpty();
        return (isRight ? "Right hand" : "Left hand") + " · "
            + Math.round((isRight ? rightSize : leftSize) * 100) + "%" + (hasItem ? "" : " · empty");
    }

    private void label(MatrixStack matrices, boolean isRight, String text, float labelWidth,
                       EditorViewport.Label position, Boolean hovered) {
        boolean selected = draggingRight != null ? draggingRight == isRight
            : hovered != null ? hovered == isRight : selectedRight != null && selectedRight == isRight;
        float x = position.centerX();
        float y = position.textY();
        float size = 7.5f * scale;
        RenderUtil.RECT.draw(matrices, x - labelWidth / 2, y - 4 * scale,
            labelWidth, 17 * scale, 5 * scale, SURFACE);
        Fonts.regular().drawCenteredText(matrices, text, x, y, size, selected ? ACCENT : MUTED);
    }

    private void button(MatrixStack matrices, String text, float x, float y, float w,
                        int mouseX, int mouseY, boolean primary) {
        boolean over = contains(mouseX, mouseY, x, y, w, 27 * scale);
        RenderUtil.RECT.draw(matrices, x, y, w, 27 * scale, 7 * scale,
            primary ? (over ? new Color(255, 89, 166) : ACCENT) : (over ? new Color(49, 45, 56, 245) : SURFACE));
        Fonts.semibold().drawCenteredText(matrices, text, x + w / 2, y + 8 * scale, 8.3f * scale, TEXT);
    }

    private Boolean hit(double x, double y) {
        boolean r = HandModelsLayout.contains(right, rightSize, x, y, width, height);
        boolean l = HandModelsLayout.contains(left, leftSize, x, y, width, height);
        if (r && l) return HandModelsLayout.distanceSquared(right, x, y, width, height)
            <= HandModelsLayout.distanceSquared(left, x, y, width, height);
        return r ? Boolean.TRUE : l ? Boolean.FALSE : null;
    }

    @Override public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        float bottom = height - 44 * scale;
        if (contains(click.x(), click.y(), width / 2f - 82 * scale, bottom, 75 * scale, 27 * scale)) {
            right = HandModelsLayout.defaults(true);
            left = HandModelsLayout.defaults(false);
            rightSize = leftSize = 1.0;
            draggingRight = null;
            selectedRight = null;
            changed = true;
            preview();
            savePositions();
        } else if (contains(click.x(), click.y(), width / 2f + 7 * scale, bottom, 75 * scale, 27 * scale)) {
            close();
        } else if (click.y() > 68 * scale) {
            draggingRight = hit(click.x(), click.y());
            if (draggingRight != null) {
                selectedRight = draggingRight;
                dragStart = draggingRight ? right : left;
                startMouseX = click.x();
                startMouseY = click.y();
            }
        }
        return true;
    }

    @Override public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (draggingRight != null && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            Position next = HandModelsLayout.drag(draggingRight, dragStart, draggingRight ? right : left, startMouseX, startMouseY,
                click.x(), click.y(), width, height);
            if (draggingRight) right = next; else left = next;
            changed = true;
            preview();
        }
        return true;
    }

    @Override public boolean mouseReleased(Click click) {
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            draggingRight = null;
            savePositions();
        }
        return true;
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        Boolean target = HandModelsLayout.wheelTarget(draggingRight, hit(mouseX, mouseY), selectedRight);
        if (target != null && verticalAmount != 0) {
            selectedRight = target;
            if (target) rightSize = HandModelsLayout.scrollSize(rightSize, verticalAmount);
            else leftSize = HandModelsLayout.scrollSize(leftSize, verticalAmount);
            changed = true;
            preview();
            savePositions();
        }
        return true;
    }

    @Override public void tick() {
        if (client.player == null || client.world == null) {
            // Let the world/disconnect transition choose its own screen. A null
            // screen while a world still exists requires a non-null player.
            HandModelPreview.clearPreview();
            return;
        }
        if (!client.player.isAlive()) {
            client.setScreen(null);
            return;
        }
        client.options.setPerspective(Perspective.FIRST_PERSON);
    }

    @Override public void close() { if (client != null) client.setScreen(parent); }
    @Override public void removed() {
        savePositions();
        HandModelPreview.clearPreview();
        if (client != null && originalPerspective != null) {
            client.options.setPerspective(originalPerspective);
            client.options.hudHidden = originalHudHidden;
        }
        GuiRenderer.getInstance().clearScissors();
        GuiCursors.restoreNative(client);
        super.removed();
    }
    @Override public boolean shouldPause() { return false; }
    @Override public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) { }
    @Override protected void applyBlur(DrawContext context) { }
    private static boolean contains(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }
}
