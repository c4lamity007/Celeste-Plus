package dev.c4lam.celeste.hud;

import dev.c4lam.celeste.gui.GuiCursors;
import dev.c4lam.celeste.gui.CelesteScreen;
import dev.c4lam.celeste.gui.FirstUseTour;
import dev.c4lam.celeste.render.GuiRenderer;
import dev.c4lam.celeste.render.RenderUtil;
import dev.c4lam.celeste.render.font.Fonts;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.joml.Vector2f;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Borderless, in-world editor for Celeste HUD widgets. */
public final class HudEditorScreen extends Screen {
    private static final Color TEXT = color(250, 244, 250, 255);
    private static final Color MUTED = color(177, 162, 178, 255);
    private static final Color ACCENT = color(255, 43, 143, 255);
    private static final Color MENU_HOVER = color(70, 63, 73, 165);
    private static final float SNAP_DISTANCE = 5.0f;
    private static final float HANDLE_SIZE = 10.0f;
    private static final float EDGE_HIT = 10.0f;

    private final Screen parent;
    private final CelesteScreen tourOwner;
    private float tourDragX, tourDragY, tourResizeScale;
    private final HudManager manager = HudManager.getInstance();
    private final List<MenuHit> menuHits = new ArrayList<>();
    private final List<TargetPlacementHit> targetPlacementHits = new ArrayList<>();
    private HudElementState dragging;
    private float dragOffsetX;
    private float dragOffsetY;
    private HudElementState resizing;
    private ResizeHandle resizeHandle;
    private float resizeStartX;
    private float resizeStartY;
    private float resizeStartW;
    private float resizeStartH;
    private float resizeStartMouseX;
    private float resizeStartMouseY;
    private boolean boxSelecting;
    private float boxSelectStartX;
    private float boxSelectStartY;
    private float menuX = -1;
    private float menuY = -1;
    private boolean menuOpen;
    private FirstUseTour.Rect menuBounds;
    private boolean targetPlacementMenu;
    private Float verticalGuide;
    private Float horizontalGuide;
    private final float[] menuHover = new float[HudElementType.values().length];
    private final float[] targetPlacementHover = new float[HudManager.TargetPlacement.values().length];
    private float screenProgress;
    private float menuProgress;
    private float guideProgress;
    private float frameSeconds = 1.0f / 60.0f;
    private long lastFrameTime;
    private float helpTop;

    public HudEditorScreen(Screen parent) {
        this(parent, null);
    }

    public HudEditorScreen(Screen parent, CelesteScreen tourOwner) {
        super(Text.literal("Celeste HUD Editor"));
        this.parent = parent;
        this.tourOwner = tourOwner;
    }

    @Override
    protected void init() {
        dragging = null;
        resizing = null;
        resizeHandle = null;
        boxSelecting = false;
        menuOpen = false;
        menuBounds = null;
        menuHits.clear();
        targetPlacementHits.clear();
        verticalGuide = null;
        horizontalGuide = null;
        for (HudElementState state : manager.elements()) {
            state.setSelected(false);
        }
        manager.beginEditorSession();
        GuiCursors.hideNative(client);
        screenProgress = 0.0f;
        menuProgress = 0.0f;
        guideProgress = 0.0f;
        lastFrameTime = System.nanoTime();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        long now = System.nanoTime();
        frameSeconds = Math.min(0.05f, Math.max(0.0f, (now - lastFrameTime) / 1_000_000_000.0f));
        lastFrameTime = now;
        screenProgress = damp(screenProgress, 1.0f, 12.0f, frameSeconds);
        menuProgress = damp(menuProgress, menuOpen ? 1.0f : 0.0f,
            menuOpen ? 20.0f : 24.0f, frameSeconds);
        guideProgress = damp(guideProgress, verticalGuide != null || horizontalGuide != null ? 1.0f : 0.0f,
            18.0f, frameSeconds);

        MatrixStack matrices = RenderUtil.matrices();
        matrices.loadIdentity();
        GuiRenderer.getInstance().clearScissors();

        manager.renderEditor(context, mouseX, mouseY, dragging != null ? dragging : resizing);
        HudViewport viewport = HudViewport.current();
        matrices.push();
        viewport.apply(matrices);
        drawResizeHandles(matrices, mouseX, mouseY);
        drawGuides(matrices, viewport);
        if (boxSelecting) {
            drawBoxSelection(matrices, mouseX, mouseY);
        }
        matrices.pop();
        drawHelp(matrices);
        drawSelectionInfo(matrices);
        if (menuOpen || menuProgress > 0.01f) {
            if (targetPlacementMenu) {
                drawTargetPlacementMenu(matrices, mouseX, mouseY, easeOutCubic(menuProgress));

            } else {
                drawAddMenu(matrices, mouseX, mouseY, easeOutCubic(menuProgress));
            }
        } else {
            menuHits.clear();
            targetPlacementHits.clear();
        }
        boolean grab = resizing != null || dragging != null
            || (!boxSelecting && (handleAt(mouseX, mouseY) != null || editableHit(mouseX, mouseY) != null
                || overMenu(mouseX, mouseY)));
        FirstUseTour tour = tour();
        if (tour != null) {
            TourTarget guide = tourTarget(tour, viewport);
            tour.draw(matrices, width, height, new FirstUseTour.Rect(0, 0, width, height),
                guide.area(), mouseX, mouseY, "", true, guide.focus());
            if (tour.contains(mouseX, mouseY)) grab = tour.pointer(mouseX, mouseY);
        }
        GuiCursors.hideNative(client);
        GuiCursors.draw(matrices, mouseX, mouseY, grab);
    }

    private record TourTarget(FirstUseTour.Rect area, FirstUseTour.Rect focus) { }

    private TourTarget tourTarget(FirstUseTour tour, HudViewport viewport) {
        HudElementState widget = manager.elements().stream()
            .filter(state -> tourMovable(state) && state.selected()).findFirst().orElse(null);
        if (widget == null) for (HudElementType type : new HudElementType[]{
                HudElementType.EFFECTS, HudElementType.KEYBINDS, HudElementType.COORDINATES, HudElementType.ARMOR}) {
            if (tourMovable(manager.state(type))) { widget = manager.state(type); break; }
        }
        if (widget == null) widget = manager.elements().stream().filter(HudEditorScreen::tourMovable)
            .findFirst().orElse(null);
        boolean listOpen = menuOpen && !targetPlacementMenu;
        if (listOpen) {
            MenuHit row = menuHits.stream().filter(hit -> !hit.type().fixed()
                && hit.type() != HudElementType.SPOTIFY && !manager.state(hit.type()).enabled())
                .findFirst().orElse(null);
            if (row == null) row = menuHits.stream().filter(hit -> !hit.type().fixed()
                && hit.type() != HudElementType.SPOTIFY).findFirst().orElse(null);
            tour.state().hudContext(widget != null, widget != null && widget.selected(),
                row != null, row != null && !manager.state(row.type()).enabled());
            FirstUseTour.Rect focus = row == null ? menuBounds
                : new FirstUseTour.Rect(row.x(), row.y(), row.width(), row.height());
            return new TourTarget(menuBounds, focus);
        }
        tour.state().hudContext(widget != null, widget != null && widget.selected(), false, false);
        if (widget == null || menuOpen && targetPlacementMenu
                || tour.state().moved() && tour.state().resized() && !tour.state().added()) {
            if (menuOpen && targetPlacementMenu) tour.state().hudContext(false, false, false, false);
            FirstUseTour.Rect space = tourEmptySpace(viewport);
            return new TourTarget(space, space);
        }
        float x = viewport.hudToScreen(widget.displayX()), y = viewport.hudToScreen(widget.displayY());
        float w = viewport.hudToScreen(widget.width()), h = viewport.hudToScreen(widget.height());
        FirstUseTour.Rect focus = new FirstUseTour.Rect(x, y, w, h);
        if (tour.state().moved() && !tour.state().resized() && widget.selected()) {
            float size = viewport.hudToScreen(HANDLE_SIZE);
            float[][] corners = {{x + w, y + h}, {x, y + h}, {x + w, y}, {x, y}};
            for (float[] corner : corners) {
                if (corner[0] - size / 2 >= 0 && corner[1] - size / 2 >= 0
                        && corner[0] + size / 2 <= width && corner[1] + size / 2 <= height) {
                    focus = new FirstUseTour.Rect(corner[0] - size / 2, corner[1] - size / 2, size, size);
                    break;
                }
            }
        }
        // Reserve the whole widget and handles, even when the cue points at one corner.
        float pad = viewport.hudToScreen(HANDLE_SIZE * .5F);
        return new TourTarget(new FirstUseTour.Rect(x - pad, y - pad, w + pad * 2, h + pad * 2), focus);
    }

    private static boolean tourMovable(HudElementState state) {
        return state.enabled() && !state.type().fixed() && state.type() != HudElementType.SPOTIFY;
    }

    private FirstUseTour.Rect tourEmptySpace(HudViewport viewport) {
        float bottom = Math.max(44, helpTop - 8);
        for (float size : new float[]{72, 24}) {
            float w = Math.min(size, width - 16), h = Math.min(size * .5F, bottom - 16);
            for (float fy : new float[]{.45F, .65F, .25F, .8F}) {
                for (float fx : new float[]{.55F, .75F, .3F, .1F}) {
                    float x = Math.clamp(width * fx, 8, Math.max(8, width - w - 8));
                    float y = Math.clamp(bottom * fy, 8, Math.max(8, bottom - h - 8));
                    boolean occupied = false;
                    for (HudElementState state : manager.elements()) {
                        if (!state.enabled()) continue;
                        float sx = viewport.hudToScreen(state.displayX()) - 8;
                        float sy = viewport.hudToScreen(state.displayY()) - 8;
                        float sw = viewport.hudToScreen(state.width()) + 16;
                        float sh = viewport.hudToScreen(state.height()) + 16;
                        if (x < sx + sw && x + w > sx && y < sy + sh && y + h > sy) {
                            occupied = true; break;
                        }
                    }
                    if (!occupied && (!menuOpen || menuBounds == null || !rectanglesOverlap(x, y, w, h, menuBounds)))
                        return new FirstUseTour.Rect(x, y, w, h);
                }
            }
        }
        // Right-clicking a normal widget also opens the list if no clear patch fits.
        for (HudElementState state : manager.elements()) if (tourMovable(state) && state.type() != HudElementType.TARGET_HUD) {
            return new FirstUseTour.Rect(viewport.hudToScreen(state.displayX()), viewport.hudToScreen(state.displayY()),
                viewport.hudToScreen(state.width()), viewport.hudToScreen(state.height()));
        }
        return null;
    }

    private static boolean rectanglesOverlap(float x, float y, float w, float h, FirstUseTour.Rect r) {
        return x < r.x() + r.width() && x + w > r.x() && y < r.y() + r.height() && y + h > r.y();
    }

    private void drawHelp(MatrixStack matrices) {
        String title = "HUD editor";
        float fontSize = 6.7F;
        float available = Math.max(24F, this.width - 40F);
        List<String> rows = new ArrayList<>();
        String row = "";
        for (String hint : new String[]{"Drag: move", "Corners: resize", "Shift-click: select",
            "Scroll: scale", "Right-click: widgets", "Esc: done"}) {
            String candidate = row.isEmpty() ? hint : row + "  •  " + hint;
            if (!row.isEmpty() && Fonts.regular().getWidth(candidate, fontSize) > available) {
                rows.add(row);
                row = hint;
            } else {
                row = candidate;
            }
        }
        if (!row.isEmpty()) rows.add(row);
        float contentWidth = Fonts.semibold().getWidth(title, 8.4F);
        for (String line : rows) contentWidth = Math.max(contentWidth, Fonts.regular().getWidth(line, fontSize));
        float panelWidth = Math.min(this.width - 12F, contentWidth + 24F);
        float panelHeight = 26F + rows.size() * 10F;
        float x = (this.width - panelWidth) / 2.0f;
        float progress = easeOutCubic(screenProgress);
        float y = Math.max(6.0f, this.height - panelHeight - 9.0f) + (1.0f - progress) * 8.0f;
        helpTop = y;
        RenderUtil.RECT.draw(matrices, x, y, panelWidth, panelHeight, 10, HudSurface.color(progress));
        Fonts.semibold().drawCenteredText(matrices, title, this.width / 2.0f, y + 7, 8.4f, withAlpha(TEXT, progress));
        for (int i = 0; i < rows.size(); i++) {
            Fonts.regular().drawCenteredText(matrices, rows.get(i), this.width / 2.0F,
                y + 22 + i * 10, fontSize, withAlpha(MUTED, progress));
        }
    }

    private void drawSelectionInfo(MatrixStack matrices) {
        if (menuOpen || boxSelecting) return;
        int selected = 0;
        HudElementState only = null;
        for (HudElementState state : manager.elements()) {
            if (!state.type().fixed() && state.enabled() && state.selected()) {
                selected++;
                only = state;
            }
        }
        if (selected == 0) return;
        String detail = selected > 1 ? selected + " selected  •  Scroll to scale together"
            : String.format(Locale.ROOT, "%s  •  %d × %d", only.type().label(),
                Math.round(only.width()), Math.round(only.height()));
        float textWidth = Fonts.regular().getWidth(detail, 6.7F);
        float cardWidth = textWidth + 22F;
        if (cardWidth > width - 12F) return;
        float x = (width - cardWidth) * .5F;
        float y = helpTop - 25.0F;
        if (y < 6.0F) return;
        float alpha = easeOutCubic(screenProgress);
        RenderUtil.RECT.draw(matrices, x, y, cardWidth, 20F, 7F, color(24, 24, 29, Math.round(220 * alpha)));
        Fonts.regular().drawCenteredText(matrices, detail, width * .5F, y + 6.5F, 6.7F, withAlpha(TEXT, alpha));
    }

    private void drawBoxSelection(MatrixStack matrices, int mouseX, int mouseY) {
        float hudMouseX = hud(mouseX);
        float hudMouseY = hud(mouseY);
        float x1 = Math.min(boxSelectStartX, hudMouseX);
        float y1 = Math.min(boxSelectStartY, hudMouseY);
        float x2 = Math.max(boxSelectStartX, hudMouseX);
        float y2 = Math.max(boxSelectStartY, hudMouseY);

        float selectionWidth = x2 - x1;
        float selectionHeight = y2 - y1;
        if (selectionWidth > 2 && selectionHeight > 2) {
            float radius = Math.min(4.5f, Math.min(selectionWidth, selectionHeight) * 0.5f);
            RenderUtil.RECT.draw(matrices, x1, y1, selectionWidth, selectionHeight, radius,
                color(180, 180, 190, 28));

            float thickness = 0.75f;
            float inset = Math.min(radius * 0.45f, 2.0f);
            Color topEdge = color(224, 224, 228, 100);
            Color otherEdge = color(170, 170, 176, 72);
            RenderUtil.RECT.draw(matrices, x1 + inset, y1,
                Math.max(0, selectionWidth - inset * 2), thickness, thickness / 2, topEdge);
            RenderUtil.RECT.draw(matrices, x1 + inset, y2 - thickness,
                Math.max(0, selectionWidth - inset * 2), thickness, thickness / 2, otherEdge);
            RenderUtil.RECT.draw(matrices, x1, y1 + inset,
                thickness, Math.max(0, selectionHeight - inset * 2), thickness / 2, otherEdge);
            RenderUtil.RECT.draw(matrices, x2 - thickness, y1 + inset,
                thickness, Math.max(0, selectionHeight - inset * 2), thickness / 2, otherEdge);
        }
    }

    private void drawGuides(MatrixStack matrices, HudViewport viewport) {
        if (verticalGuide != null) {
            RenderUtil.RECT.draw(matrices, verticalGuide - 0.5f, 0, 1, viewport.height(), 0,
                color(255, 86, 164, Math.round(145 * guideProgress)));
        }
        if (horizontalGuide != null) {
            RenderUtil.RECT.draw(matrices, 0, horizontalGuide - 0.5f, viewport.width(), 1, 0,
                color(255, 86, 164, Math.round(145 * guideProgress)));
        }
    }

    private void drawAddMenu(MatrixStack matrices, int mouseX, int mouseY, float progress) {
        float preferredColumnWidth = 156;
        float rowHeight = 21;
        int itemCount = HudElementType.values().length;
        int maximumRows = Math.max(1, (int) Math.floor((Math.max(rowHeight + 12, height - 12) - 12) / rowHeight));
        int minimumColumns = width >= preferredColumnWidth * 2 + 12 ? 2 : 1;
        int columnCount = Math.max(minimumColumns, (itemCount + maximumRows - 1) / maximumRows);
        int rowCount = (itemCount + columnCount - 1) / columnCount;
        float columnWidth = Math.min(preferredColumnWidth, Math.max(1, width - 12) / (float) columnCount);
        float menuWidth = columnWidth * columnCount;
        float menuHeight = 12 + rowCount * rowHeight;
        menuX = Math.max(6, Math.min(menuX, width - menuWidth - 6));
        menuY = Math.max(6, Math.min(menuY, height - menuHeight - 6));
        menuBounds = new FirstUseTour.Rect(menuX, menuY, menuWidth, menuHeight);
        RenderUtil.RECT.draw(matrices, menuX, menuY, menuWidth, menuHeight, 11, HudSurface.color(progress));
        menuHits.clear();
        targetPlacementHits.clear();

        HudElementType[] types = HudElementType.values();
        for (int index = 0; index < types.length; index++) {
            HudElementType type = types[index];
            int column = index / rowCount;
            int row = index % rowCount;
            float rowX = menuX + column * columnWidth;
            float rowY = menuY + 6 + row * rowHeight;
            HudElementState state = manager.state(type);
            boolean hovered = menuOpen && inside(mouseX, mouseY, rowX + 5, rowY, columnWidth - 10, rowHeight - 2);
            menuHover[index] = damp(menuHover[index], hovered ? 1.0f : 0.0f, hovered ? 22.0f : 15.0f, frameSeconds);
            if (menuHover[index] > 0.01f) {
                RenderUtil.RECT.draw(matrices, rowX + 5, rowY, columnWidth - 10, rowHeight - 2, 6,
                    withAlpha(MENU_HOVER, progress * menuHover[index]));
            }
            Fonts.regular().drawText(matrices, state.enabled() ? "−" : "+", rowX + 12, rowY + 6, 7.5f,
                state.enabled() ? MUTED : ACCENT);
            Fonts.semibold().drawText(matrices, type.label(), rowX + 27, rowY + 5, 7.6f,
                withAlpha(blend(MUTED, TEXT, menuHover[index]), progress));
            menuHits.add(new MenuHit(type, MenuAction.TOGGLE, rowX + 5, rowY, columnWidth - 10, rowHeight - 2));
        }

    }

    private void drawTargetPlacementMenu(MatrixStack matrices, int mouseX, int mouseY, float progress) {
        float menuWidth = 164.0F;
        float rowHeight = 22.0F;
        float menuHeight = 29.0F + HudManager.TargetPlacement.values().length * rowHeight;
        menuX = Math.max(6.0F, Math.min(menuX, width - menuWidth - 6.0F));
        menuY = Math.max(6.0F, Math.min(menuY, height - menuHeight - 6.0F));
        menuBounds = new FirstUseTour.Rect(menuX, menuY, menuWidth, menuHeight);
        RenderUtil.RECT.draw(matrices, menuX, menuY, menuWidth, menuHeight, 11, HudSurface.color(progress));
        Fonts.semibold().drawText(matrices, "Target HUD placement", menuX + 12.0F, menuY + 8.0F,
            7.4F, withAlpha(TEXT, progress));

        menuHits.clear();
        targetPlacementHits.clear();
        HudManager.TargetPlacement selected = manager.targetPlacement();
        HudManager.TargetPlacement[] placements = HudManager.TargetPlacement.values();
        for (int index = 0; index < placements.length; index++) {
            HudManager.TargetPlacement placement = placements[index];
            float rowX = menuX + 6.0F;
            float rowY = menuY + 25.0F + index * rowHeight;
            float rowWidth = menuWidth - 12.0F;
            boolean hovered = menuOpen && inside(mouseX, mouseY, rowX, rowY, rowWidth, rowHeight - 2.0F);
            targetPlacementHover[index] = damp(targetPlacementHover[index], hovered ? 1.0F : 0.0F,
                hovered ? 22.0F : 15.0F, frameSeconds);
            if (targetPlacementHover[index] > 0.01F) {
                RenderUtil.RECT.draw(matrices, rowX, rowY, rowWidth, rowHeight - 2.0F, 6.0F,
                    withAlpha(MENU_HOVER, progress * targetPlacementHover[index]));
            }

            boolean active = placement == selected;
            Fonts.regular().drawText(matrices, active ? "●" : "○", rowX + 8.0F, rowY + 6.0F,
                7.0F, withAlpha(active ? ACCENT : MUTED, progress));
            Fonts.semibold().drawText(matrices, placement.label(), rowX + 26.0F, rowY + 5.0F,
                7.5F, withAlpha(blend(MUTED, TEXT, Math.max(active ? 0.65F : 0.0F,
                    targetPlacementHover[index])), progress));
            targetPlacementHits.add(new TargetPlacementHit(placement, rowX, rowY, rowWidth,
                rowHeight - 2.0F));
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        FirstUseTour tour = tour();
        if (tour != null) {
            FirstUseTour.Action action = tour.click(click.x(), click.y(), click.button());
            if (action != FirstUseTour.Action.NONE) {
                tourOwner.handleHudTourAction(action);
                if (tour() == null) close();
                return true;
            }
        }
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            HudElementState clicked = editableHit(click.x(), click.y());
            for (HudElementState state : manager.elements()) {
                state.setSelected(state == clicked && clicked != null && clicked.type() == HudElementType.TARGET_HUD);
            }
            menuX = (float) click.x();
            menuY = (float) click.y();
            menuOpen = true;
            menuHits.clear();
            targetPlacementHits.clear();
            menuBounds = null;
            targetPlacementMenu = clicked != null && clicked.type() == HudElementType.TARGET_HUD;
            dragging = null;
            resizing = null;
            resizeHandle = null;
            boxSelecting = false;
            return true;
        }
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }

        if (menuOpen) {
            if (targetPlacementMenu) {
                for (TargetPlacementHit hit : targetPlacementHits) {
                    if (!hit.contains(click.x(), click.y())) {
                        continue;
                    }
                    manager.setTargetPlacement(hit.placement());
                    menuOpen = false;
                    return true;
                }
                menuOpen = false;
                return true;
            }
            for (MenuHit hit : menuHits) {
                if (!hit.contains(click.x(), click.y())) {
                    continue;
                }
                boolean before = manager.state(hit.type()).enabled();
                manager.toggle(hit.type());
                if (!hit.type().fixed() && manager.state(hit.type()).enabled()) {
                    placeNewElement(manager.state(hit.type()), menuX, menuY);
                }
                if (tour != null) tour.state().hudToggled(hit.type().name(), before, manager.state(hit.type()).enabled());
                menuOpen = false;
                return true;
            }
            menuOpen = false;
            return true;
        }

        boolean additiveSelection = (click.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        if (!additiveSelection && manager.handleMediaClick(click.x(), click.y())) {
            boxSelecting = false;
            return true;
        }

        HandleHit edge = handleAt(click.x(), click.y());
        if (edge != null) {
            if (!additiveSelection) {
                for (HudElementState state : manager.elements()) {
                    state.setSelected(state == edge.state());
                }
            } else {
                edge.state().setSelected(true);
            }
            beginResize(edge.state(), edge.handle(), hud(click.x()), hud(click.y()));
            dragging = null;
            boxSelecting = false;
            return true;
        }

        HudElementState hit = editableHit(click.x(), click.y());
        if (hit != null) {
            if (additiveSelection) {
                hit.setSelected(!hit.selected());
                dragging = null;
                resizing = null;
                boxSelecting = false;
                return true;
            }
            for (HudElementState state : manager.elements()) {
                state.setSelected(state == hit);
            }
            dragging = hit;
            tourDragX = hit.x();
            tourDragY = hit.y();
            dragOffsetX = hud(click.x()) - hit.displayX();
            dragOffsetY = hud(click.y()) - hit.displayY();
            resizing = null;
            boxSelecting = false;
            return true;
        } else {
            for (HudElementState state : manager.elements()) {
                state.setSelected(false);
            }
            boxSelecting = true;
            boxSelectStartX = hud(click.x());
            boxSelectStartY = hud(click.y());
            resizing = null;
            return true;
        }
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        
        if (resizing != null && !resizing.type().fixed() && resizeHandle != null) {
            applyResize(hud(click.x()), hud(click.y()));
            boxSelecting = false;
            return true;
        }
        if (dragging != null && !dragging.type().fixed()) {
            Vector2f snapped = snap(
                hud(click.x()) - dragOffsetX,
                hud(click.y()) - dragOffsetY,
                dragging
            );
            HudViewport viewport = HudViewport.current();
            dragging.moveTo(
                Math.max(0, Math.min(viewport.width() - dragging.width(), snapped.x)),
                Math.max(0, Math.min(viewport.height() - dragging.height(), snapped.y))
            );
            boxSelecting = false;
            return true;
        } else if (boxSelecting) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return super.mouseReleased(click);
        if (resizing != null) {
            HudViewport viewport = HudViewport.current();
            manager.clampToViewport(Math.round(viewport.width()), Math.round(viewport.height()));
            manager.saveLayout();
            if (tour() != null) tour().state().hudResized(resizing.type().name(), tourResizeScale, resizing.scale());
            resizing = null;
            resizeHandle = null;
        }
        if (dragging != null) {
            manager.saveLayout();
            if (tour() != null) tour().state().hudMoved(dragging.type().name(), tourDragX, tourDragY, dragging.x(), dragging.y());
            dragging = null;
        } else if (boxSelecting) {
            float hudMouseX = hud(click.x());
            float hudMouseY = hud(click.y());
            float x1 = Math.min(boxSelectStartX, hudMouseX);
            float y1 = Math.min(boxSelectStartY, hudMouseY);
            float x2 = Math.max(boxSelectStartX, hudMouseX);
            float y2 = Math.max(boxSelectStartY, hudMouseY);
            
            for (HudElementState state : manager.elements()) {
                if (!state.type().fixed() && state.enabled()) {
                    float elemX = state.displayX();
                    float elemY = state.displayY();
                    float elemWidth = state.width();
                    float elemHeight = state.height();
                    
                    if (elemX < x2 && elemX + elemWidth > x1 &&
                        elemY < y2 && elemY + elemHeight > y1) {
                        state.setSelected(true);
                    } else {
                        state.setSelected(false);
                    }
                }
            }
            boxSelecting = false;
        }
        
        dragging = null;
        verticalGuide = null;
        horizontalGuide = null;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (menuOpen) return true;
        if (tour() != null && tour().contains(mouseX, mouseY)) return true;
        List<HudElementState> selectedElements = new ArrayList<>();
        for (HudElementState state : manager.elements()) {
            if (!state.type().fixed() && state.enabled() && state.selected()) {
                selectedElements.add(state);
            }
        }
        if (!selectedElements.isEmpty() && verticalAmount != 0.0D) {
            // One reversible scalar keeps every selected card's original
            // aspect, glyph size, and internal spacing in lockstep.
            float scaleFactor = (float) Math.pow(1.1D, verticalAmount);
            float[] beforeScales = new float[selectedElements.size()];
            for (int index = 0; index < selectedElements.size(); index++)
                beforeScales[index] = selectedElements.get(index).scale();
            for (HudElementState state : selectedElements) {
                state.scaleBy(scaleFactor);
            }
            HudViewport viewport = HudViewport.current();
            manager.clampToViewport(Math.round(viewport.width()), Math.round(viewport.height()));
            manager.saveLayout();
            if (tour() != null) for (int index = 0; index < selectedElements.size(); index++) {
                HudElementState state = selectedElements.get(index);
                tour().state().hudResized(state.type().name(), beforeScales[index], state.scale());
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private Vector2f snap(float rawX, float rawY, HudElementState moving) {
        verticalGuide = null;
        horizontalGuide = null;
        HudViewport viewport = HudViewport.current();

        List<Float> verticalGuides = new ArrayList<>();
        verticalGuides.add(0.0f);
        verticalGuides.add(viewport.width() / 2.0f);
        verticalGuides.add(viewport.width());
        List<Float> horizontalGuides = new ArrayList<>();
        horizontalGuides.add(0.0f);
        horizontalGuides.add(viewport.height() / 2.0f);
        horizontalGuides.add(viewport.height());

        for (HudElementState other : manager.elements()) {
            if (!other.enabled() || other.type().fixed() || other == moving) {
                continue;
            }
            verticalGuides.add(other.displayX());
            verticalGuides.add(other.displayX() + other.width() / 2.0f);
            verticalGuides.add(other.displayX() + other.width());
            horizontalGuides.add(other.displayY());
            horizontalGuides.add(other.displayY() + other.height() / 2.0f);
            horizontalGuides.add(other.displayY() + other.height());
        }

        SnapResult snappedX = nearestSnap(rawX, new float[]{0, moving.width() / 2.0f, moving.width()}, verticalGuides);
        SnapResult snappedY = nearestSnap(rawY, new float[]{0, moving.height() / 2.0f, moving.height()}, horizontalGuides);
        verticalGuide = snappedX.guide();
        horizontalGuide = snappedY.guide();
        return new Vector2f(snappedX.position(), snappedY.position());
    }

    private static SnapResult nearestSnap(float origin, float[] anchorOffsets, List<Float> guides) {
        float closestDistance = SNAP_DISTANCE + 1.0f;
        float closestDelta = 0.0f;
        Float closestGuide = null;
        for (float guide : guides) {
            for (float offset : anchorOffsets) {
                float delta = guide - (origin + offset);
                float distance = Math.abs(delta);
                if (distance <= SNAP_DISTANCE && distance < closestDistance) {
                    closestDistance = distance;
                    closestDelta = delta;
                    closestGuide = guide;
                }
            }
        }
        return new SnapResult(origin + closestDelta, closestGuide);
    }

    private void placeNewElement(HudElementState state, float requestedX, float requestedY) {
        if (state.type().fixed()) return;
        HudViewport viewport = HudViewport.current();
        requestedX = viewport.screenToHud(requestedX);
        requestedY = viewport.screenToHud(requestedY);
        state.moveImmediately(
            Math.max(0, Math.min(viewport.width() - state.width(), requestedX)),
            Math.max(0, Math.min(viewport.height() - state.height(), requestedY))
        );
        manager.saveLayout();
    }

    private void beginResize(HudElementState state, ResizeHandle handle, float mouseX, float mouseY) {
        if (state.type().fixed()) return;
        tourResizeScale = state.scale();
        state.moveImmediately(state.x(), state.y());
        resizing = state;
        resizeHandle = handle;
        resizeStartX = state.x();
        resizeStartY = state.y();
        resizeStartW = state.width();
        resizeStartH = state.height();
        resizeStartMouseX = mouseX;
        resizeStartMouseY = mouseY;
    }

    private void applyResize(float mouseX, float mouseY) {
        if (resizing == null || resizing.type().fixed() || resizeHandle == null) {
            return;
        }
        float dx = mouseX - resizeStartMouseX;
        float dy = mouseY - resizeStartMouseY;
        float left = resizeStartX;
        float top = resizeStartY;
        float right = resizeStartX + resizeStartW;
        float bottom = resizeStartY + resizeStartH;
        if (resizeHandle.east()) {
            right += dx;
        }
        if (resizeHandle.west()) {
            left += dx;
        }
        if (resizeHandle.south()) {
            bottom += dy;
        }
        if (resizeHandle.north()) {
            top += dy;
        }

        float minW = resizing.intrinsicWidth() * HudElementState.MIN_SCALE;
        float minH = resizing.intrinsicHeight() * HudElementState.MIN_SCALE;
        if (resizeHandle.corner() && resizeStartW > 0.5f && resizeStartH > 0.5f) {
            float newW = Math.max(minW, right - left);
            float newH = Math.max(minH, bottom - top);
            float factor = Math.abs(dx) * resizeStartH > Math.abs(dy) * resizeStartW
                ? newW / resizeStartW
                : newH / resizeStartH;
            factor = Math.max(minW / resizeStartW, Math.max(minH / resizeStartH, factor));
            newW = resizeStartW * factor;
            newH = resizeStartH * factor;
            if (resizeHandle.east()) {
                right = left + newW;
            } else if (resizeHandle.west()) {
                left = right - newW;
            }
            if (resizeHandle.south()) {
                bottom = top + newH;
            } else if (resizeHandle.north()) {
                top = bottom - newH;
            }
        }

        if (right - left < minW) {
            if (resizeHandle.west()) {
                left = right - minW;
            } else {
                right = left + minW;
            }
        }
        if (bottom - top < minH) {
            if (resizeHandle.north()) {
                top = bottom - minH;
            } else {
                bottom = top + minH;
            }
        }

        float scaleX = (right - left) / Math.max(1.0f, resizing.intrinsicWidth());
        float scaleY = (bottom - top) / Math.max(1.0f, resizing.intrinsicHeight());
        resizing.setScale(resizeHandle.east() || resizeHandle.west() ? scaleX : scaleY);
        float width = resizing.width();
        float height = resizing.height();
        float x = resizeHandle.west() ? right - width : left;
        float y = resizeHandle.north() ? bottom - height : top;
        HudViewport viewport = HudViewport.current();
        resizing.moveImmediately(
            Math.max(0.0f, Math.min(viewport.width() - width, x)),
            Math.max(0.0f, Math.min(viewport.height() - height, y))
        );
    }

    private void drawResizeHandles(MatrixStack matrices, int mouseX, int mouseY) {
        if (menuOpen || boxSelecting) {
            return;
        }
        HandleHit hover = handleAt(mouseX, mouseY);
        double hudMouseX = hud(mouseX);
        double hudMouseY = hud(mouseY);
        for (HudElementState state : manager.elements()) {
            if (!state.enabled() || state.type().fixed()) {
                continue;
            }
            boolean show = state.selected() || state == resizing
                || nearBorder(state, hudMouseX, hudMouseY);
            if (!show) {
                continue;
            }
            float x = state.displayX();
            float y = state.displayY();
            float w = state.width();
            float h = state.height();
            drawCornerHandle(matrices, x, y, hover, state, ResizeHandle.NW);
            drawCornerHandle(matrices, x + w, y, hover, state, ResizeHandle.NE);
            drawCornerHandle(matrices, x, y + h, hover, state, ResizeHandle.SW);
            drawCornerHandle(matrices, x + w, y + h, hover, state, ResizeHandle.SE);
        }
    }

    private void drawCornerHandle(MatrixStack matrices, float centerX, float centerY,
                                  HandleHit hover, HudElementState state, ResizeHandle handle) {
        boolean hot = (hover != null && hover.state() == state && hover.handle() == handle)
            || (resizing == state && resizeHandle == handle);
        float s = HANDLE_SIZE;
        float x = centerX - s * 0.5f;
        float y = centerY - s * 0.5f;
        RenderUtil.RECT.draw(matrices, x, y, s, s, s * 0.5f, HudSurface.color(hot ? 1.0f : 0.9f));
        float inner = s - 3.4f;
        RenderUtil.RECT.draw(matrices, centerX - inner * 0.5f, centerY - inner * 0.5f,
            inner, inner, inner * 0.5f, withAlpha(blend(MUTED, TEXT, hot ? 1.0f : 0.42f), hot ? 0.95f : 0.82f));
    }

    /** Fixed widgets remain toggleable in the menu, but never enter editor transforms. */
    private HudElementState editableHit(double mouseX, double mouseY) {
        double hudX = hud(mouseX);
        double hudY = hud(mouseY);
        List<HudElementState> states = manager.elements();
        for (int index = states.size() - 1; index >= 0; index--) {
            HudElementState state = states.get(index);
            if (state.enabled() && !state.type().fixed() && state.contains(hudX, hudY)) return state;
        }
        return null;
    }

    private HandleHit handleAt(double mouseX, double mouseY) {
        double hudX = hud(mouseX);
        double hudY = hud(mouseY);
        for (int index = manager.elements().size() - 1; index >= 0; index--) {
            HudElementState state = manager.elements().get(index);
            if (!state.enabled() || state.type().fixed()) {
                continue;
            }
            ResizeHandle handle = handleOn(state, hudX, hudY);
            if (handle != null) {
                return new HandleHit(state, handle);
            }
            // A foreground card's body occludes corners on cards behind it.
            if (state.contains(hudX, hudY)) return null;
        }
        return null;
    }

    private static ResizeHandle handleOn(HudElementState state, double mouseX, double mouseY) {
        if (state.type().fixed()) return null;
        return switch (HudEditorHitPolicy.corner(state.displayX(), state.displayY(), state.width(),
            state.height(), mouseX, mouseY, EDGE_HIT)) {
            case 0 -> ResizeHandle.NW;
            case 1 -> ResizeHandle.NE;
            case 2 -> ResizeHandle.SW;
            case 3 -> ResizeHandle.SE;
            default -> null;
        };
    }

    private static boolean nearBorder(HudElementState state, double mouseX, double mouseY) {
        if (state.type().fixed()) return false;
        float x = state.displayX();
        float y = state.displayY();
        float w = state.width();
        float h = state.height();
        float pad = EDGE_HIT;
        if (mouseX < x - pad || mouseX > x + w + pad || mouseY < y - pad || mouseY > y + h + pad) {
            return false;
        }
        return Math.abs(mouseX - x) <= pad
            || Math.abs(mouseX - (x + w)) <= pad
            || Math.abs(mouseY - y) <= pad
            || Math.abs(mouseY - (y + h)) <= pad;
    }

    private boolean overMenu(double mouseX, double mouseY) {
        if (!menuOpen) {
            return false;
        }
        for (MenuHit hit : menuHits) {
            if (hit.contains(mouseX, mouseY)) {
                return true;
            }
        }
        for (TargetPlacementHit hit : targetPlacementHits) {
            if (hit.contains(mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        manager.saveLayout();
        GuiCursors.restoreNative(client);
        if (client != null) {
            client.setScreen(parent);
        }
    }

    private FirstUseTour tour() {
        return tourOwner == null ? null : tourOwner.hudTour();
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
    }

    @Override
    protected void applyBlur(DrawContext context) {
    }

    @Override
    public void removed() {
        manager.saveLayout();
        manager.releaseBlurCapture();
        GuiCursors.restoreNative(client);
        super.removed();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static boolean inside(double mouseX, double mouseY, float x, float y, float width, float height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private static float hud(double screenCoordinate) {
        return (float) HudViewport.current().screenToHud(screenCoordinate);
    }

    private static Color color(int red, int green, int blue, int alpha) {
        return new Color(red, green, blue, alpha);
    }

    private static float damp(float current, float target, float speed, float seconds) {
        return current + (target - current) * (1.0f - (float) Math.exp(-speed * seconds));
    }

    private static float easeOutCubic(float value) {
        float clamped = Math.max(0.0f, Math.min(1.0f, value));
        float inverse = 1.0f - clamped;
        return 1.0f - inverse * inverse * inverse;
    }

    private static Color blend(Color from, Color to, float amount) {
        float t = Math.max(0.0f, Math.min(1.0f, amount));
        return color(
            Math.round(from.getRed() + (to.getRed() - from.getRed()) * t),
            Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * t),
            Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * t),
            Math.round(from.getAlpha() + (to.getAlpha() - from.getAlpha()) * t)
        );
    }

    private static Color withAlpha(Color source, float opacity) {
        return color(source.getRed(), source.getGreen(), source.getBlue(),
            Math.max(0, Math.min(255, Math.round(source.getAlpha() * opacity))));
    }

    private enum MenuAction {
        TOGGLE
    }

    private record MenuHit(HudElementType type, MenuAction action, float x, float y, float width, float height) {
        boolean contains(double mouseX, double mouseY) {
            return inside(mouseX, mouseY, x, y, width, height);
        }
    }

    private record TargetPlacementHit(HudManager.TargetPlacement placement, float x, float y,
                                      float width, float height) {
        boolean contains(double mouseX, double mouseY) {
            return inside(mouseX, mouseY, x, y, width, height);
        }
    }

    private record SnapResult(float position, Float guide) {
    }

    private record HandleHit(HudElementState state, ResizeHandle handle) {
    }

    private enum ResizeHandle {
        N(false, true, false, false),
        S(false, false, false, true),
        E(false, false, true, false),
        W(true, false, false, false),
        NE(false, true, true, false),
        NW(true, true, false, false),
        SE(false, false, true, true),
        SW(true, false, false, true);

        private final boolean west;
        private final boolean north;
        private final boolean east;
        private final boolean south;

        ResizeHandle(boolean west, boolean north, boolean east, boolean south) {
            this.west = west;
            this.north = north;
            this.east = east;
            this.south = south;
        }

        boolean west() {
            return west;
        }

        boolean north() {
            return north;
        }

        boolean east() {
            return east;
        }

        boolean south() {
            return south;
        }

        boolean corner() {
            return (west || east) && (north || south);
        }
    }
}
