package dev.c4lam.celeste.hud;

import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;

import dev.c4lam.celeste.CelesteClient;
import dev.c4lam.celeste.module.expansion.inventory.SilentInventoryScreen;
import dev.c4lam.celeste.config.InterfaceConfig;
import dev.c4lam.celeste.gui.model.GuiCatalog;
import dev.c4lam.celeste.gui.model.GuiModule;
import dev.c4lam.celeste.gui.model.GuiSetting;
import dev.c4lam.celeste.license.LicenseIdentity;
import dev.c4lam.celeste.media.MediaPlaybackSnapshot;
import dev.c4lam.celeste.media.MediaArtwork;
import dev.c4lam.celeste.media.SystemMediaService;
import dev.c4lam.celeste.render.GuiRenderer;
import dev.c4lam.celeste.render.GuiTextures;
import dev.c4lam.celeste.render.RenderUtil;
import dev.c4lam.celeste.render.font.Font;
import dev.c4lam.celeste.render.font.Fonts;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.network.AbstractClientPlayerEntity;

import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;
import org.joml.Vector4f;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** Owns HUD widget state and supplies both editor previews and the live overlay. */
public final class HudManager {
    private static final Identifier LAYER_ID = Identifier.of(CelesteClient.MOD_ID, "hud_elements");
    private static final Identifier MEDIA_PREVIOUS_ICON = Identifier.of(CelesteClient.MOD_ID,
        "textures/gui/icons/media_previous.png");
    private static final Identifier MEDIA_PLAY_ICON = Identifier.of(CelesteClient.MOD_ID,
        "textures/gui/icons/media_play.png");
    private static final Identifier MEDIA_PAUSE_ICON = Identifier.of(CelesteClient.MOD_ID,
        "textures/gui/icons/media_pause.png");
    private static final Identifier MEDIA_NEXT_ICON = Identifier.of(CelesteClient.MOD_ID,
        "textures/gui/icons/media_next.png");
    private static final Identifier MEDIA_SHUFFLE_ICON = Identifier.of(CelesteClient.MOD_ID,
        "textures/gui/icons/media_shuffle.png");
    private static final float[] MEDIA_BUTTON_CENTERS = {58.0f, 95.0f, 132.0f, 164.0f};
    private static final HudManager INSTANCE = new HudManager();
    private static final Color TEXT = color(250, 244, 250, 255);
    private static final Color MUTED = color(173, 158, 174, 255);
    private static final Color ACCENT = color(255, 43, 143, 255);
    private static final Color BACKGROUND_TOP = color(31, 18, 31, 244);
    private static final Color BACKGROUND_BOTTOM = color(14, 10, 17, 249);
    private static final Color BACKGROUND_HOVER_TOP = color(46, 27, 44, 246);
    private static final Color BACKGROUND_HOVER_BOTTOM = color(22, 15, 24, 250);
    private static final float ARRAYLIST_FONT = 7.2f;
    private static final float ARRAYLIST_PAD_X = 7.0f;
    private static final float ARRAYLIST_ROW_HEIGHT = 13.0f;
    private static final float ARRAYLIST_CORNER = 4.0f;
    private static final float ARRAYLIST_ROW_OVERLAP = 1.0f;

    private final List<HudElementState> elements = new ArrayList<>();
    private final HudTargetTracker targetTracker = HudTargetTracker.getInstance();
    private final Path layoutFile = FabricLoader.getInstance().getConfigDir().resolve("celeste").resolve("hud-layout.properties");
    private final float[] mediaButtonHover = new float[4];
    private float renderedMediaProgress;
    private String renderedMediaTimelineKey = "";
    private float renderedMediaArtworkOpacity;
    private float renderedMediaPlaying;
    private float renderedMediaShuffle;
    private float renderedMediaMetadataOpacity = 1.0f;
    private String renderedArtworkKey = "";
    private float frameSeconds = 1.0f / 60.0f;
    private final Map<String, Float> moduleEntrance = new HashMap<>();
    private List<String> frameModuleNames = List.of();
    private long lastPresentationUpdate;
    private boolean initialized;
    private boolean closed;
    private TargetPlacement targetPlacement = TargetPlacement.HUD;
    // HUD selection state
    private boolean isSelecting;
    private float selectionStartX;
    private float selectionStartY;
    private float selectionEndX;
    private float selectionEndY;
    private final List<HudElementState> selectedElements = new ArrayList<>();

      private HudManager() {
          elements.add(new HudElementState(HudElementType.WATERMARK, 8, 8, true, false));
          elements.add(new HudElementState(HudElementType.COORDINATES, 8, 44, true, false));
          elements.add(new HudElementState(HudElementType.FPS, 8, 80, false, false));
          elements.add(new HudElementState(HudElementType.TPS, 80, 80, false, false));
          elements.add(new HudElementState(HudElementType.ARRAYLIST, 8, 113, false, false));
          elements.add(new HudElementState(HudElementType.TARGET_HUD, 218, 134, false, false));
          elements.add(new HudElementState(HudElementType.EFFECTS, 8, 182, false, false));
          elements.add(new HudElementState(HudElementType.ARMOR, 218, 188, false, false));
          elements.add(new HudElementState(HudElementType.KEYBINDS, 8, 238, false, false));
          elements.add(new HudElementState(HudElementType.COOLDOWNS, 218, 232, false, false));
          elements.add(new HudElementState(HudElementType.SPOTIFY, 218, 278, false, false));
         isSelecting = false;
     }

    public static HudManager getInstance() {
        return INSTANCE;
    }

    /** Call during client initialization. Repeated calls are harmless. */
    public synchronized void initialize() {
        if (initialized || closed) {
            return;
        }
        loadLayout();
        initialized = true;
        BetaSubscriptions.register(HudElementRegistry.class, LAYER_ID.toString(), HudElement.class, (context, tickCounter) -> {
            if (closed || !CelesteClient.active()) {
                return;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.currentScreen == null || client.currentScreen instanceof SilentInventoryScreen) {
                render(context, -1, -1, false, null, false);
            } else if (client.currentScreen instanceof ChatScreen) {
                int mouseX = Math.round((float) (client.mouse.getX()
                    * client.getWindow().getScaledWidth() / Math.max(1, client.getWindow().getWidth())));
                int mouseY = Math.round((float) (client.mouse.getY()
                    * client.getWindow().getScaledHeight() / Math.max(1, client.getWindow().getHeight())));
                render(context, mouseX, mouseY, false, null, true);
            }
        }, proxy -> HudElementRegistry.addLast(LAYER_ID, proxy));
    }

    public synchronized void close() {
        closed = true;
        isSelecting = false;
        for (HudElementState element : selectedElements) {
            element.setSelected(false);
        }
        selectedElements.clear();
        moduleEntrance.clear();
        frameModuleNames = List.of();
    }

    public List<HudElementState> elements() {
        return List.copyOf(elements);
    }

    public HudElementState state(HudElementType type) {
        return elements.stream().filter(element -> element.type() == type).findFirst().orElseThrow();
    }

    public TargetPlacement targetPlacement() {
        return targetPlacement;
    }

    public void setTargetPlacement(TargetPlacement placement) {
        TargetPlacement safePlacement = placement == null ? TargetPlacement.HUD : placement;
        if (targetPlacement == safePlacement) {
            return;
        }
        targetPlacement = safePlacement;
        saveLayout();
    }

    public void toggle(HudElementType type) {
        HudElementState state = state(type);
        boolean enabled = !state.enabled();
        state.setEnabled(enabled);
        if (!enabled) {
            state.setSelected(false);
        }
        GuiModule module = moduleFor(type);
        if (module != null) {
            module.setEnabled(enabled);
        }
        saveLayout();
    }

    /** Makes the module switch authoritative for the three module-backed widgets. */
    public void synchronizeModuleBackedElements() {
        for (HudElementType type : List.of(
            HudElementType.FPS, HudElementType.WATERMARK, HudElementType.ARRAYLIST)) {
            GuiModule module = moduleFor(type);
            if (module != null) {
                state(type).setEnabled(module.enabled());
            }
        }
        saveLayout();
    }

    private static GuiModule moduleFor(HudElementType type) {
        String name = switch (type) {
            case FPS -> "FPS Counter";
            case WATERMARK -> "Watermark";
            case ARRAYLIST -> "Array List";
            default -> null;
        };
        if (name == null) {
            return null;
        }
        return GuiCatalog.modules().stream()
            .filter(module -> module.name().equals(name))
            .findFirst().orElse(null);
    }

    /** Restarts the restrained entrance used when opening the editor. */
    public void beginEditorSession() {
        for (HudElementState state : elements) {
            state.restartEntrance();
        }
        Arrays.fill(mediaButtonHover, 0.0f);
        renderedMediaArtworkOpacity = 0.0f;
        renderedMediaPlaying = 0.0f;
        renderedMediaShuffle = 0.0f;
        lastPresentationUpdate = 0L;
    }

    public void releaseBlurCapture() {
        RenderUtil.releaseBlur();
    }

    public synchronized Properties exportLayout() {
        Properties values = new Properties();
        for (HudElementState state : elements) {
            String prefix = "hud." + state.type().name().toLowerCase(Locale.ROOT) + ".";
            values.setProperty(prefix + "enabled", Boolean.toString(state.enabled()));
            values.setProperty(prefix + "x", Float.toString(state.x()));
            values.setProperty(prefix + "y", Float.toString(state.y()));
            values.setProperty(prefix + "scale", Float.toString(state.scaleX()));
            values.setProperty(prefix + "scale_x", Float.toString(state.scaleX()));
            values.setProperty(prefix + "scale_y", Float.toString(state.scaleY()));
        }
        values.setProperty("hud.target_hud.placement", targetPlacement.name());
        return values;
    }

    public synchronized void importLayout(Properties values) {
        if (values == null) {
            return;
        }
        applyLayout(values);
        saveLayout();
    }

    public synchronized void saveLayout() {
        try {
            Files.createDirectories(layoutFile.getParent());
            try (OutputStream output = Files.newOutputStream(layoutFile)) {
                Properties stored = new Properties();
                for (HudElementState state : elements) {
                    String prefix = state.type().name().toLowerCase(Locale.ROOT) + ".";
                    stored.setProperty(prefix + "enabled", Boolean.toString(state.enabled()));
                    stored.setProperty(prefix + "x", Float.toString(state.x()));
                    stored.setProperty(prefix + "y", Float.toString(state.y()));
                    stored.setProperty(prefix + "scale", Float.toString(state.scaleX()));
                    stored.setProperty(prefix + "scale_x", Float.toString(state.scaleX()));
                    stored.setProperty(prefix + "scale_y", Float.toString(state.scaleY()));
                }
                stored.setProperty("target_hud.placement", targetPlacement.name());
                stored.store(output, "Celeste HUD layout");
            }
        } catch (IOException ignored) {
        }
    }

    private void loadLayout() {
        if (!Files.exists(layoutFile)) {
            return;
        }
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(layoutFile)) {
            values.load(input);
            applyLayout(values);
        } catch (IOException ignored) {
        }
    }

    private void applyLayout(Properties values) {
        targetPlacement = TargetPlacement.parse(
            values.getProperty("hud.target_hud.placement", values.getProperty("target_hud.placement")),
            TargetPlacement.HUD);
        for (HudElementState state : elements) {
            String prefixed = "hud." + state.type().name().toLowerCase(Locale.ROOT) + ".";
            String legacy = state.type().name().toLowerCase(Locale.ROOT) + ".";
            String prefix = values.getProperty(prefixed + "enabled") != null ? prefixed : legacy;
            state.setEnabled(Boolean.parseBoolean(values.getProperty(prefix + "enabled", Boolean.toString(state.enabled()))));
            float x = parseFloat(values.getProperty(prefix + "x"), state.x());
            float y = parseFloat(values.getProperty(prefix + "y"), state.y());
            float uniform = parseFloat(values.getProperty(prefix + "scale"), 1.0f);
            state.setScaleX(parseFloat(values.getProperty(prefix + "scale_x"), uniform));
            state.setScaleY(parseFloat(values.getProperty(prefix + "scale_y"), uniform));
            state.moveImmediately(x, y);
        }
    }

    public HudElementState hit(double mouseX, double mouseY) {
        syncDynamicSizes();
        for (int index = elements.size() - 1; index >= 0; index--) {
            HudElementState state = elements.get(index);
            if (state.enabled() && state.contains(mouseX, mouseY)) {
                return state;
            }
        }
        return null;
    }

    /** Runs a media transport action when a visible control is clicked. */
    public boolean handleMediaClick(double mouseX, double mouseY) {
        HudElementState media = state(HudElementType.SPOTIFY);
        if (!media.enabled() || !media.contains(mouseX, mouseY)) {
            return false;
        }
        float localX = ((float) mouseX - media.displayX()) / media.scaleX();
        float localY = ((float) mouseY - media.displayY()) / media.scaleY();
        if (localY < 57.0f || localY > 77.0f) {
            return false;
        }

        int button = -1;
        for (int index = 0; index < MEDIA_BUTTON_CENTERS.length; index++) {
            if (Math.abs(localX - MEDIA_BUTTON_CENTERS[index]) <= 14.0f) {
                button = index;
                break;
            }
        }
        if (button < 0) {
            return false;
        }

        SystemMediaService service = SystemMediaService.getInstance();
        MediaPlaybackSnapshot snapshot = service.snapshot();
        switch (button) {
            case 0 -> {
                if (snapshot.canPrevious()) service.previous();
            }
            case 1 -> {
                if (snapshot.canToggle()) service.togglePlayback();
            }
            case 2 -> {
                if (snapshot.canNext()) service.next();
            }
            case 3 -> {
                if (snapshot.canShuffle()) service.toggleShuffle();
            }
            default -> {
            }
        }
        return true;
    }

    /** Keeps persisted absolute positions reachable after GUI-scale or window-size changes. */
    public boolean clampToViewport(int viewportWidth, int viewportHeight) {
        syncDynamicSizes();
        boolean changed = false;
        for (HudElementState state : elements) {
            float fittingScaleX = viewportWidth / Math.max(1.0f, state.intrinsicWidth());
            float fittingScaleY = viewportHeight / Math.max(1.0f, state.intrinsicHeight());
            // Match the state's minimum: asking for a smaller scale is ignored
            // and would otherwise mark the layout dirty on every tiny-window frame.
            float safeScaleX = Math.max(HudElementState.MIN_SCALE, Math.min(state.scaleX(), fittingScaleX));
            float safeScaleY = Math.max(HudElementState.MIN_SCALE, Math.min(state.scaleY(), fittingScaleY));
            if (safeScaleX != state.scaleX() || safeScaleY != state.scaleY()) {
                state.setScaleX(safeScaleX);
                state.setScaleY(safeScaleY);
                changed = true;
            }
            float maximumX = Math.max(0.0f, viewportWidth - state.width());
            float maximumY = Math.max(0.0f, viewportHeight - state.height());
            float safeX = Float.isFinite(state.x()) ? Math.max(0.0f, Math.min(maximumX, state.x())) : 0.0f;
            float safeY = Float.isFinite(state.y()) ? Math.max(0.0f, Math.min(maximumY, state.y())) : 0.0f;
            if (safeX != state.x() || safeY != state.y()) {
                state.moveTo(safeX, safeY);
                changed = true;
            }
        }
        return changed;
    }

    public void renderEditor(DrawContext context, int mouseX, int mouseY, HudElementState dragging) {
        render(context, mouseX, mouseY, true, dragging, false);
    }

    private void render(DrawContext context, int mouseX, int mouseY, boolean editor, HudElementState dragging,
                        boolean chatMediaMode) {
        MinecraftClient client = MinecraftClient.getInstance();
        targetTracker.updateLifecycle(client);
        if (!editor && (client.options.hudHidden || client.world == null || client.player == null)) {
            return;
        }
        frameSeconds = presentationSeconds();
        if (clampToViewport(context.getScaledWindowWidth(), context.getScaledWindowHeight())) {
            // A GUI-scale/window change may otherwise leave the persisted copy
            // offscreen again on the next launch. This branch runs only when a
            // position actually changes, never once per frame.
            saveLayout();
        }
        MatrixStack matrices = RenderUtil.matrices();
        matrices.loadIdentity();
        GuiRenderer.getInstance().clearScissors();

        for (HudElementState state : elements) {
            boolean presentationVisible = !chatMediaMode || state.type() == HudElementType.SPOTIFY;
            boolean interactive = editor || (chatMediaMode && state.type() == HudElementType.SPOTIFY);
            boolean hovered = interactive && state.enabled() && state.contains(mouseX, mouseY);
            state.updateVisuals(hovered, state == dragging, presentationVisible);
        }

        HudElementState targetState = state(HudElementType.TARGET_HUD);
        boolean targetPresentationVisible = !chatMediaMode;
        HudTargetTracker.Presentation targetPresentation = targetState.visuallyPresent(targetPresentationVisible)
            ? targetTracker.presentation(MinecraftClient.getInstance(), editor)
            : null;
        boolean targetAboveEntity = !editor && !chatMediaMode
            && targetPlacement == TargetPlacement.ABOVE_TARGET;
        ProjectedTarget projectedTarget = targetAboveEntity
            ? projectTarget(context, targetPresentation)
            : null;

        // One explicit world capture is shared by every HUD surface this frame.
        // Do not run the full-screen blur chain for an enabled Target HUD that
        // currently has no target and therefore draws no panel.
        boolean hasVisibleSurface = editor || elements.stream().anyMatch(state -> {
            boolean presentationVisible = !chatMediaMode || state.type() == HudElementType.SPOTIFY;
            return state.visuallyPresent(presentationVisible)
                && (state.type() != HudElementType.TARGET_HUD
                    || (targetAboveEntity ? projectedTarget != null : targetPresentation != null));
        });
        if (hasVisibleSurface) {
            RenderUtil.captureBlur();
        }
        for (HudElementState state : elements) {
            boolean presentationVisible = !chatMediaMode || state.type() == HudElementType.SPOTIFY;
            boolean interactive = editor || (chatMediaMode && state.type() == HudElementType.SPOTIFY);
            boolean hovered = interactive && state.enabled() && state.contains(mouseX, mouseY);
            if (!state.visuallyPresent(presentationVisible)) {
                continue;
            }
            if (targetAboveEntity && state.type() == HudElementType.TARGET_HUD) {
                continue;
            }
            drawWidget(context, matrices, state, hovered, state == dragging, mouseX, mouseY, editor,
                interactive, targetPresentation);
        }
        if (projectedTarget != null) {
            drawProjectedTarget(matrices, targetState, targetPresentation, projectedTarget);
        }
    }

    private ProjectedTarget projectTarget(DrawContext context, HudTargetTracker.Presentation presentation) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (presentation == null || presentation.entity() == null || client.world == null
            || client.player == null || !client.gameRenderer.getCamera().isReady()) {
            return null;
        }

        var camera = client.gameRenderer.getCamera();
        float tickProgress = camera.getLastTickProgress();
        Vec3d targetBase = presentation.entity().getLerpedPos(tickProgress);
        Vec3d headAnchor = targetBase.add(0.0D, presentation.entity().getHeight() + 0.42D, 0.0D);
        Vec3d cameraPos = camera.getCameraPos();
        Vec3d relative = headAnchor.subtract(cameraPos);
        var forward = camera.getHorizontalPlane();
        double cameraDepth = relative.x * forward.x() + relative.y * forward.y() + relative.z * forward.z();
        if (cameraDepth <= 0.05D || occluded(client, cameraPos, headAnchor)) {
            return null;
        }

        Vec3d projected = client.gameRenderer.project(headAnchor);
        if (!Double.isFinite(projected.x) || !Double.isFinite(projected.y) || !Double.isFinite(projected.z)
            || projected.z < -1.01D || projected.z > 1.01D) {
            return null;
        }

        float viewportWidth = context.getScaledWindowWidth();
        float viewportHeight = context.getScaledWindowHeight();
        float screenX = (float) ((projected.x + 1.0D) * 0.5D * viewportWidth);
        float screenY = (float) ((1.0D - projected.y) * 0.5D * viewportHeight);
        HudElementState state = state(HudElementType.TARGET_HUD);
        float cardWidth = state.width();
        float cardHeight = state.height();
        float cardX = screenX - cardWidth * 0.5F;
        float cardY = screenY - cardHeight - 4.0F * state.scale();
        if (cardX + cardWidth <= 0.0F || cardX >= viewportWidth
            || cardY + cardHeight <= 0.0F || cardY >= viewportHeight) {
            return null;
        }
        return new ProjectedTarget(cardX, cardY);
    }

    private static boolean occluded(MinecraftClient client, Vec3d cameraPos, Vec3d targetPos) {
        HitResult hit = client.world.raycast(new RaycastContext(
            cameraPos,
            targetPos,
            RaycastContext.ShapeType.VISUAL,
            RaycastContext.FluidHandling.NONE,
            client.player
        ));
        return hit.getType() != HitResult.Type.MISS
            && hit.getPos().squaredDistanceTo(cameraPos) + 1.0E-4D
                < targetPos.squaredDistanceTo(cameraPos);
    }

    private void drawProjectedTarget(MatrixStack matrices, HudElementState state,
                                     HudTargetTracker.Presentation presentation,
                                     ProjectedTarget projected) {
        float visibility = state.visibility() * presentation.visibility();
        if (visibility <= 0.003F) {
            return;
        }

        float x = projected.x();
        float y = projected.y() + (1.0F - presentation.visibility()) * 3.0F;
        float scaleX = state.scaleX();
        float scaleY = state.scaleY();
        float width = state.width();
        float height = state.height();
        float baseWidth = state.intrinsicWidth();
        float baseHeight = state.intrinsicHeight();
        float motionScale = (0.965F + state.visibility() * 0.035F)
            * (0.96F + presentation.visibility() * 0.04F);
        float centerX = x + width * 0.5F;
        float centerY = y + height * 0.5F;

        matrices.push();
        matrices.translate(centerX, centerY, 0.0F);
        matrices.scale(motionScale, motionScale, 1.0F);
        matrices.translate(-centerX, -centerY, 0.0F);
        matrices.translate(x, y, 0.0F);
        matrices.scale(scaleX, scaleY, 1.0F);
        matrices.translate(-x, -y, 0.0F);
        RenderUtil.BLUR_RECT.draw(matrices, x, y, baseWidth, baseHeight, new Vector4f(9),
            withAlpha(BACKGROUND_TOP, visibility), withAlpha(BACKGROUND_TOP, visibility),
            withAlpha(BACKGROUND_BOTTOM, visibility), withAlpha(BACKGROUND_BOTTOM, visibility),
            InterfaceConfig.glassTintMix());
        drawTarget(matrices, x, y, baseWidth, visibility, presentation);
        matrices.pop();
    }

    private void drawWidget(DrawContext context, MatrixStack matrices, HudElementState state, boolean hovered,
                            boolean dragging, int mouseX, int mouseY, boolean editor, boolean interactive,
                            HudTargetTracker.Presentation frameTarget) {
        float visibility = state.visibility();
        HudTargetTracker.Presentation targetPresentation = null;
        float targetVisibility = 1.0f;
        if (state.type() == HudElementType.TARGET_HUD) {
            targetPresentation = frameTarget;
            if (targetPresentation == null) {
                return;
            }
            targetVisibility = editor ? 1.0f : targetPresentation.visibility();
            visibility *= targetVisibility;
            if (visibility <= 0.003f) {
                return;
            }
        }
        float x = state.displayX();
        float y = state.displayY() + (1.0f - state.visibility()) * 4.0f
            + (state.type() == HudElementType.TARGET_HUD ? (1.0f - targetVisibility) * 3.0f : 0.0f);
        float scaleX = state.scaleX();
        float scaleY = state.scaleY();
        float width = state.width();
        float height = state.height();
        float baseWidth = state.intrinsicWidth();
        float baseHeight = state.intrinsicHeight();
        float hover = state.hoverProgress();
        float drag = state.dragProgress();
        float selection = editor ? state.selectionProgress() : 0.0f;
        float motionScale = (0.965f + state.visibility() * 0.035f)
            * (state.type() == HudElementType.TARGET_HUD ? 0.96f + targetVisibility * 0.04f : 1.0f)
            * (1.0f + hover * 0.012f + drag * 0.012f);
        float centerX = x + width / 2.0f;
        float centerY = y + height / 2.0f;

        matrices.push();
        matrices.translate(centerX, centerY, 0.0f);
        matrices.scale(motionScale, motionScale, 1.0f);
        matrices.translate(-centerX, -centerY, 0.0f);
        matrices.translate(x, y, 0.0f);
        matrices.scale(scaleX, scaleY, 1.0f);
        matrices.translate(-x, -y, 0.0f);

        Color top = withAlpha(blend(BACKGROUND_TOP, BACKGROUND_HOVER_TOP, hover), visibility);
        Color bottom = withAlpha(blend(BACKGROUND_BOTTOM, BACKGROUND_HOVER_BOTTOM, hover), visibility);
        if (state.type() != HudElementType.ARRAYLIST) {
            RenderUtil.BLUR_RECT.draw(matrices, x, y, baseWidth, baseHeight, new Vector4f(9),
                top, top, bottom, bottom, InterfaceConfig.glassTintMix());
        }
        if (selection > 0.002f) {
            Color selectionTop = withAlpha(color(205, 205, 211, 30), visibility * selection);
            Color selectionBottom = withAlpha(color(92, 92, 100, 42), visibility * selection);
            RenderUtil.BLUR_RECT.draw(matrices, x, y, baseWidth, baseHeight, new Vector4f(9),
                selectionTop, selectionTop, selectionBottom, selectionBottom,
                InterfaceConfig.glassTintMix());
        }
        if (editor && state.type() != HudElementType.ARRAYLIST && (hover > 0.015f || dragging)) {
            float indicatorWidth = 18.0f + 10.0f * drag;
            RenderUtil.RECT.draw(matrices, centerX - indicatorWidth / 2.0f, y + 2.5f,
                indicatorWidth, 1.5f, 0.75f, withAlpha(ACCENT, visibility * Math.max(hover, drag) * 0.78f));
        }

         switch (state.type()) {
             case WATERMARK -> drawWatermark(matrices, x, y, baseWidth, visibility);
             case COORDINATES -> drawCoordinates(matrices, x, y, baseWidth, visibility);
             case TARGET_HUD -> drawTarget(matrices, x, y, baseWidth, visibility, targetPresentation);
             case EFFECTS -> drawEffects(matrices, x, y, baseWidth, visibility);
             case ARMOR -> {
                 var gui = context.getMatrices();
                 gui.pushMatrix();
                 gui.translate(centerX, centerY);
                 gui.scale(motionScale, motionScale);
                 gui.translate(-centerX, -centerY);
                 gui.translate(x, y);
                 gui.scale(scaleX, scaleY);
                 gui.translate(-x, -y);
                 drawArmor(context, matrices, x, y, visibility);
                 gui.popMatrix();
             }
             case KEYBINDS -> drawKeybinds(matrices, x, y, baseWidth, visibility);
             case COOLDOWNS -> drawCooldowns(matrices, x, y, baseWidth, visibility);
             case ARRAYLIST -> drawArraylist(matrices, x, y, baseWidth, visibility);
             case TPS -> drawMetric(matrices, x, y, baseWidth, "TPS",
                 ServerTickRateTracker.getInstance().formatted(MinecraftClient.getInstance().world), visibility);
             case FPS -> drawMetric(matrices, x, y, baseWidth, "FPS", String.valueOf(MinecraftClient.getInstance().getCurrentFps()), visibility);
             case SPOTIFY -> drawMedia(matrices, x, y, baseWidth, visibility,
                 Math.round(x + (mouseX - x) / scaleX),
                 Math.round(y + (mouseY - y) / scaleY), interactive);
         }
        if (selection > 0.002f) {
            RenderUtil.SELECTION_OUTLINE.draw(matrices, x, y, baseWidth, baseHeight,
                9.0f, 1.2f, selectionPhase(), visibility * selection);
        }
        matrices.pop();
    }

    private void drawWatermark(MatrixStack matrices, float x, float y, float width, float opacity) {
        String brand = "Celeste";
        String suffix = " Client";
        float brandWidth = Fonts.semibold().width(brand, 8.0f);
        float fullWidth = brandWidth + Fonts.semibold().width(suffix, 8.0f);
        float textX = x + (width - fullWidth) * 0.5f;
        Fonts.semibold().draw(matrices, brand, textX, y + 5, 8.0f,
            withAlpha(blend(TEXT, ACCENT, 0.20f), opacity));
        Fonts.semibold().draw(matrices, suffix, textX + brandWidth, y + 5, 8.0f,
            withAlpha(TEXT, opacity * 0.87f));
        Fonts.regular().drawCenteredText(matrices, "UID " + LicenseIdentity.uid(),
            x + width / 2.0f, y + 18, 5.6f, withAlpha(MUTED, opacity));
    }

    private void drawCoordinates(MatrixStack matrices, float x, float y, float width, float opacity) {
        MinecraftClient client = MinecraftClient.getInstance();
        String[] axes = {"X ", "Y ", "Z "};
        String[] values = client.player == null ? new String[] {"0", "0", "0"}
            : new String[] {Integer.toString((int) Math.floor(client.player.getX())),
                Integer.toString((int) Math.floor(client.player.getY())),
                Integer.toString((int) Math.floor(client.player.getZ()))};
        float size = 7.2f;
        String full = axes[0] + values[0] + "   " + axes[1] + values[1] + "   " + axes[2] + values[2];
        float measured = Fonts.semibold().width(full, size);
        size *= Math.min(1.0f, (width - 14.0f) / Math.max(1.0f, measured));
        float cursor = x + (width - Fonts.semibold().width(full, size)) * 0.5f;
        Fonts.regular().drawCenteredText(matrices, "XYZ", x + width / 2.0f, y + 4, 5.6f, withAlpha(MUTED, opacity));
        for (int axis = 0; axis < axes.length; axis++) {
            Fonts.semibold().draw(matrices, axes[axis], cursor, y + 15, size,
                withAlpha(blend(MUTED, ACCENT, axis == 1 ? 0.12f : 0.34f), opacity));
            cursor += Fonts.semibold().width(axes[axis], size);
            Fonts.semibold().draw(matrices, values[axis], cursor, y + 15, size, withAlpha(TEXT, opacity));
            cursor += Fonts.semibold().width(values[axis] + "   ", size);
        }
    }

    private void drawTarget(MatrixStack matrices, float x, float y, float width, float opacity,
                            HudTargetTracker.Presentation presentation) {
        float content = presentation.contentProgress();
        float headScale = 0.90f + content * 0.10f + presentation.hurtPulse() * 0.025f;
        float headX = x + 7.0f;
        float headY = y + 9.0f;
        float headSize = 26.0f;
        float headCenterX = headX + headSize * 0.5f;
        float headCenterY = headY + headSize * 0.5f;
        Color contentText = withAlpha(TEXT, opacity * content);
        Color contentMuted = withAlpha(MUTED, opacity * content);

        matrices.push();
        matrices.translate(headCenterX, headCenterY, 0.0f);
        matrices.scale(headScale, headScale, 1.0f);
        matrices.translate(-headCenterX, -headCenterY, 0.0f);
        RenderUtil.RECT.draw(matrices, headX, headY, headSize, headSize, 6.0f,
            color(53, 30, 50, Math.round(224 * opacity * content)));
        if (presentation.entity() instanceof AbstractClientPlayerEntity player) {
            Identifier skin = player.getSkin().body().texturePath();
            RenderUtil.TEXTURE_RECT.draw(matrices, headX + 1, headY + 1, 24, 24, new Vector4f(5),
                withAlpha(Color.WHITE, opacity * content), 8.0f / 64.0f, 8.0f / 64.0f,
                8.0f / 64.0f, 8.0f / 64.0f, GuiTextures.view(skin), GuiTextures.nearest(), true);
            RenderUtil.TEXTURE_RECT.draw(matrices, headX + 1, headY + 1, 24, 24, new Vector4f(5),
                withAlpha(Color.WHITE, opacity * content), 40.0f / 64.0f, 8.0f / 64.0f,
                8.0f / 64.0f, 8.0f / 64.0f, GuiTextures.view(skin), GuiTextures.nearest(), true);
        } else {
            String initial = presentation.name().isEmpty()
                ? "?" : presentation.name().substring(0, 1).toUpperCase(Locale.ROOT);
            float initialY = headY + (headSize - Fonts.bold().height(9.0f)) * 0.5f;
            Fonts.bold().drawCenteredText(matrices, initial, headCenterX, initialY, 9.0f,
                withAlpha(ACCENT, opacity * content));
        }
        matrices.pop();

        float textX = x + 40.0f;
        float right = x + width - 8.0f;
        String healthText = "EST " + formatHealth(presentation.health());
        float healthTextWidth = Fonts.bold().width(healthText, 6.4f);
        float availableNameWidth = Math.max(18.0f, right - healthTextWidth - 4.0f - textX);
        String name = trimToWidth(presentation.name(), availableNameWidth, 7.2f);
        Fonts.semibold().draw(matrices, name, textX, y + 7.0f, 7.2f, contentText);
        Fonts.bold().draw(matrices, healthText, right - healthTextWidth, y + 7.5f, 6.4f,
            withAlpha(ACCENT, opacity * content));

        String detail = presentation.preview()
            ? "Local estimate preview"
            : String.format(Locale.ROOT, "%.1fm away", presentation.distance());
        Fonts.regular().draw(matrices, detail, textX, y + 20.0f, 5.6f, contentMuted);
        if (presentation.absorption() > 0.05f) {
            String absorptionText = "+" + formatHealth(presentation.absorption());
            float absorptionTextWidth = Fonts.semibold().width(absorptionText, 5.6f);
            Fonts.semibold().draw(matrices, absorptionText, right - absorptionTextWidth, y + 20.0f, 5.6f,
                withAlpha(color(255, 218, 110, 255), opacity * content));
        }

        float trackY = y + 32.0f;
        float trackWidth = Math.max(1.0f, right - textX);
        RenderUtil.RECT.draw(matrices, textX, trackY, trackWidth, 3.0f, 1.5f,
            color(61, 48, 62, Math.round(205 * opacity * content)));
        float healthRatio = Math.max(0.0f, Math.min(1.0f, presentation.health() / presentation.maxHealth()));
        float healthWidth = trackWidth * healthRatio;
        Color baseHealth = blend(color(255, 111, 111, 255), ACCENT, Math.min(1.0f, healthRatio * 2.3f));
        Color healthColor = blend(baseHealth, Color.WHITE, presentation.hurtPulse() * 0.22f);
        if (healthWidth > 0.01f) {
            RenderUtil.RECT.draw(matrices, textX, trackY, healthWidth, 3.0f, 1.5f,
                withAlpha(healthColor, opacity * content));
        }
        float absorptionRatio = Math.max(0.0f, Math.min(1.0f, presentation.absorption() / presentation.maxHealth()));
        float absorptionWidth = trackWidth * absorptionRatio;
        if (absorptionWidth > 0.01f) {
            RenderUtil.RECT.draw(matrices, right - absorptionWidth, trackY + 4.4f, absorptionWidth, 1.5f, 0.75f,
                color(255, 220, 110, Math.round(230 * opacity * content)));
        }
    }

    private static String trimToWidth(String value, float maximumWidth, float size) {
        return trimToWidth(value, maximumWidth, size, Fonts.semibold());
    }

    private static String trimToWidth(String value, float maximumWidth, float size, Font font) {
        // Widget rows are single-line, even when external media or entity names are not.
        String text = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ')
            .replace('\t', ' ').replace('\u2028', ' ').replace('\u2029', ' ');
        if (font.width(text, size) <= maximumWidth) {
            return text;
        }
        String suffix = "...";
        if (font.width(suffix, size) > maximumWidth) {
            return "";
        }
        // Avoid quadratic re-measurement for a long media title, and never split
        // a supplementary Unicode character at the truncation boundary.
        int low = 0;
        int high = text.codePointCount(0, text.length());
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            int end = text.offsetByCodePoints(0, middle);
            if (font.width(text.substring(0, end) + suffix, size) <= maximumWidth) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return text.substring(0, text.offsetByCodePoints(0, low)) + suffix;
    }

    private static String formatHealth(float value) {
        return Math.abs(value - Math.round(value)) < 0.05f
            ? Integer.toString(Math.round(value))
            : String.format(Locale.ROOT, "%.1f", value);
    }

    private void drawEffects(MatrixStack matrices, float x, float y, float width, float opacity) {
        MinecraftClient client = MinecraftClient.getInstance();
        Fonts.semibold().drawCenteredText(matrices, "Effects", x + width / 2.0f, y + 6, 7.4f, withAlpha(TEXT, opacity));
        List<StatusEffectInstance> active = client.player == null ? List.of()
            : client.player.getStatusEffects().stream()
                .sorted(Comparator.comparingInt(effect -> effect.getDuration() < 0 ? Integer.MAX_VALUE : effect.getDuration()))
                .limit(2).toList();
        if (active.isEmpty()) {
            Fonts.regular().drawCenteredText(matrices, "No active effects", x + width / 2.0f, y + 24, 6.2f,
                withAlpha(MUTED, opacity * 0.84f));
            return;
        }
        for (int index = 0; index < active.size(); index++) {
            StatusEffectInstance effect = active.get(index);
            int remaining = (int) ((Math.max(0L, effect.getDuration()) + 19L) / 20L);
            String duration = effect.getDuration() < 0 ? "--:--" : remaining / 60 + ":" + String.format(Locale.ROOT, "%02d", remaining % 60);
            float durationWidth = Fonts.regular().width(duration, 5.6f);
            String name = effectName(effect) + (effect.getAmplifier() > 0 ? " " + (effect.getAmplifier() + 1) : "");
            float rowY = y + 21 + index * 11;
            Color timerColor = remaining <= 10 && effect.getDuration() >= 0 ? color(255, 190, 113, 255) : MUTED;
            Fonts.regular().draw(matrices, trimToWidth(name, width - 24 - durationWidth, 6.0f, Fonts.regular()),
                x + 9, rowY, 6.0f, withAlpha(TEXT, opacity * 0.91f));
            Fonts.regular().draw(matrices, duration, x + width - 9 - durationWidth, rowY + .3f,
                5.6f, withAlpha(timerColor, opacity));
        }
    }

    private String effectName(StatusEffectInstance effect) {
        return effect.getEffectType().value().getName().getString();
    }

    private void drawArmor(DrawContext context, MatrixStack matrices, float x, float y, float opacity) {
        MinecraftClient client = MinecraftClient.getInstance();
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        float itemSize = 18.0f;
        GuiRenderer.getInstance().flush();
        for (int index = 0; index < slots.length; index++) {
            float itemX = x + 10 + index * 34;
            float itemY = y + (34.0f - itemSize) * 0.5f;
            ItemStack stack = client.player == null ? ItemStack.EMPTY : client.player.getEquippedStack(slots[index]);
            if (stack.isEmpty()) {
                RenderUtil.RECT.draw(matrices, itemX, itemY, itemSize, itemSize, 4,
                    color(255, 255, 255, Math.round(18 * opacity)));
                continue;
            }
            float scale = itemSize / 16.0f;
            context.getMatrices().pushMatrix();
            context.getMatrices().translate(itemX, itemY);
            context.getMatrices().scale(scale, scale);
            context.drawItem(stack, 0, 0);
            context.getMatrices().popMatrix();
            if (!stack.isDamageable()) {
                continue;
            }
            int remaining = Math.max(0, stack.getMaxDamage() - stack.getDamage());
            float ratio = stack.getMaxDamage() <= 0 ? 1.0f
                : Math.max(0.0f, Math.min(1.0f, remaining / (float) stack.getMaxDamage()));
            Color value = ratio > 0.45f ? TEXT : ratio > 0.2f ? color(255, 196, 92, 255) : color(255, 92, 108, 255);
            Color durability = blend(color(255, 105, 119, 255), color(179, 226, 211, 255), Math.min(1.0f, ratio * 1.5f));
            RenderUtil.RECT.draw(matrices, itemX, y + 31.0f, itemSize, 1.2f, .6f,
                withAlpha(Color.WHITE, opacity * .10f));
            if (ratio > 0.001f) {
                RenderUtil.RECT.draw(matrices, itemX, y + 31.0f, itemSize * ratio, 1.2f, .6f,
                    withAlpha(durability, opacity * .88f));
            }
            float centerX = itemX + itemSize * 0.5f;
            float labelY = itemY + itemSize - 1.0f;
            Fonts.bold().drawCenteredText(matrices, Integer.toString(remaining), centerX + 0.6f, labelY + 0.6f, 6.2f,
                color(0, 0, 0, Math.round(170 * opacity)));
            Fonts.bold().drawCenteredText(matrices, Integer.toString(remaining), centerX, labelY, 6.2f,
                withAlpha(value, opacity));
        }
    }

    private void drawKeybinds(MatrixStack matrices, float x, float y, float width, float opacity) {
        Fonts.semibold().drawCenteredText(matrices, "Keybinds", x + width / 2.0f, y + 6, 7.4f, withAlpha(TEXT, opacity));
        List<HudBind> bound = GuiCatalog.modules().stream()
            .flatMap(module -> module.settings().stream()
                .filter(GuiSetting.Keybind.class::isInstance)
                .map(GuiSetting.Keybind.class::cast)
                .filter(keybind -> keybind.keyCode() != GLFW.GLFW_KEY_UNKNOWN)
                .map(keybind -> new HudBind(module.name(), keyName(keybind.keyCode()), module.enabled())))
            .sorted(Comparator.comparing(HudBind::active).reversed())
            .limit(2)
            .toList();
        if (bound.isEmpty()) {
            Fonts.regular().drawCenteredText(matrices, "No configured binds", x + width / 2.0f, y + 25, 6.2f,
                withAlpha(MUTED, opacity));
            return;
        }
        for (int index = 0; index < bound.size(); index++) {
            HudBind bind = bound.get(index);
            String key = trimToWidth(bind.key(), 31.0f, 5.5f);
            float chipWidth = Fonts.semibold().width(key, 5.5f) + 7.0f;
            float rowY = y + 21 + index * 11;
            float chipX = x + width - 9 - chipWidth;
            RenderUtil.RECT.draw(matrices, chipX, rowY - 0.5f, chipWidth, 9.0f, 2.5f,
                withAlpha(bind.active() ? ACCENT : Color.WHITE, opacity * (bind.active() ? .13f : .055f)));
            Fonts.semibold().drawCenteredText(matrices, key, chipX + chipWidth * .5f, rowY + .3f, 5.5f,
                withAlpha(bind.active() ? blend(TEXT, ACCENT, .28f) : MUTED, opacity));
            Fonts.regular().draw(matrices, trimToWidth(bind.module(), width - chipWidth - 25, 6.0f, Fonts.regular()),
                x + 9, rowY, 6.0f, withAlpha(bind.active() ? TEXT : MUTED, opacity));
        }
    }

    private void drawCooldowns(MatrixStack matrices, float x, float y, float width, float opacity) {
        MinecraftClient client = MinecraftClient.getInstance();
        float partialTick = client.gameRenderer.getCamera().getLastTickProgress();
        float attack = client.player == null ? 1.0f
            : Math.max(0.0f, Math.min(1.0f, client.player.getAttackCooldownProgress(partialTick)));
        boolean ready = attack >= .999f;
        String value = ready ? "Ready" : Math.round(attack * 100.0f) + "%";
        Color progressColor = blend(ACCENT, color(183, 228, 214, 255), Math.max(0, (attack - .85f) / .15f));
        Fonts.semibold().draw(matrices, "Attack cooldown", x + 12, y + 7, 6.2f, withAlpha(TEXT, opacity));
        Fonts.semibold().draw(matrices, value, x + width - 12 - Fonts.semibold().width(value, 5.8f),
            y + 7.5f, 5.8f, withAlpha(ready ? progressColor : MUTED, opacity));
        float trackWidth = width - 24;
        RenderUtil.RECT.draw(matrices, x + 12, y + 23, trackWidth, 3, 1.5f,
            color(62, 47, 62, Math.round(230 * opacity)));
        if (attack > .001f) {
            RenderUtil.RECT.draw(matrices, x + 12, y + 23, trackWidth * attack, 3, 1.5f,
                withAlpha(progressColor, opacity));
            float cap = Math.min(3.0f, trackWidth * attack);
            RenderUtil.RECT.draw(matrices, x + 12 + trackWidth * attack - cap, y + 23,
                cap, 3, 1.5f, withAlpha(Color.WHITE, opacity * .35f));
        }
    }

    private List<String> enabledModuleNames() {
        return GuiCatalog.modules().stream()
            .filter(GuiModule::enabled)
            .map(GuiModule::name)
            .sorted(Comparator.comparingDouble((String name) -> Fonts.semibold().width(name, ARRAYLIST_FONT))
                .reversed()
                .thenComparing(String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    private float arraylistContentWidth(List<String> names) {
        float max = Fonts.semibold().width("No modules", ARRAYLIST_FONT);
        for (String name : names) {
            max = Math.max(max, Fonts.semibold().width(name, ARRAYLIST_FONT));
        }
        return Math.max(36.0f, max + ARRAYLIST_PAD_X * 2.0f);
    }

    private void syncDynamicSizes() {
        frameModuleNames = enabledModuleNames();
        HudElementState arraylist = state(HudElementType.ARRAYLIST);
        float oldWidth = arraylist.width();
        float right = arraylist.x() + oldWidth;
        arraylist.setContentSize(arraylistContentWidth(frameModuleNames),
            Math.max(1, frameModuleNames.size()) * ARRAYLIST_ROW_HEIGHT);
        float newWidth = arraylist.width();
        if (Math.abs(newWidth - oldWidth) > 0.25f) {
            arraylist.moveImmediately(right - newWidth, arraylist.y());
        }
    }

    private static float arraylistRowWidth(String name) {
        return Fonts.semibold().width(name, ARRAYLIST_FONT) + ARRAYLIST_PAD_X * 2.0f;
    }

    private static Vector4f arraylistRowRadius(int index, int count, float rowWidth, float previousWidth,
                                               float nextWidth) {
        boolean first = index == 0;
        boolean last = index == count - 1;
        float slack = 1.25f;
        boolean previousDiffers = !first && Math.abs(previousWidth - rowWidth) > slack;
        boolean nextDiffers = !last && Math.abs(nextWidth - rowWidth) > slack;
        float topLeft = first || previousDiffers ? ARRAYLIST_CORNER : 0.0f;
        float topRight = first ? ARRAYLIST_CORNER : 0.0f;
        float bottomLeft = last || nextDiffers ? ARRAYLIST_CORNER : 0.0f;
        float bottomRight = last ? ARRAYLIST_CORNER : 0.0f;
        return new Vector4f(topLeft, topRight, bottomLeft, bottomRight);
    }

    private void drawArraylist(MatrixStack matrices, float x, float y, float width, float opacity) {
        List<String> enabled = frameModuleNames;
        moduleEntrance.keySet().retainAll(enabled);
        float right = x + width;
        if (enabled.isEmpty()) {
            String empty = "No modules";
            float rowWidth = arraylistRowWidth(empty);
            float rowX = right - rowWidth;
            RenderUtil.BLUR_RECT.draw(matrices, rowX, y, rowWidth, ARRAYLIST_ROW_HEIGHT, ARRAYLIST_CORNER,
                withAlpha(BACKGROUND_TOP, opacity), InterfaceConfig.glassTintMix());
            Fonts.semibold().drawText(matrices, empty, rowX + ARRAYLIST_PAD_X, y + 2.4f, ARRAYLIST_FONT,
                withAlpha(MUTED, opacity));
            return;
        }
        Color top = withAlpha(BACKGROUND_TOP, opacity);
        Color bottom = withAlpha(BACKGROUND_BOTTOM, opacity);
        int count = enabled.size();
        for (int index = 0; index < count; index++) {
            String name = enabled.get(index);
            float entrance = damp(moduleEntrance.getOrDefault(name, 0.0f), 1.0f, 16.0f, frameSeconds);
            moduleEntrance.put(name, entrance);
            float rowWidth = arraylistRowWidth(name);
            float previousWidth = index > 0 ? arraylistRowWidth(enabled.get(index - 1)) : rowWidth;
            float nextWidth = index + 1 < count ? arraylistRowWidth(enabled.get(index + 1)) : rowWidth;
            float rowX = right - rowWidth;
            float rowY = y + index * ARRAYLIST_ROW_HEIGHT;
            float rowHeight = ARRAYLIST_ROW_HEIGHT + (index + 1 < count ? ARRAYLIST_ROW_OVERLAP : 0.0f);
            RenderUtil.BLUR_RECT.draw(matrices, rowX, rowY, rowWidth, rowHeight,
                arraylistRowRadius(index, count, rowWidth, previousWidth, nextWidth),
                top, top, bottom, bottom, InterfaceConfig.glassTintMix());
            Color rowText = blend(TEXT, ACCENT, .07f + .11f * (1.0f - entrance));
            Fonts.semibold().drawText(matrices, name, rowX + ARRAYLIST_PAD_X + (1.0f - entrance) * 2.0f,
                rowY + 2.4f, ARRAYLIST_FONT, withAlpha(rowText, opacity * entrance));
        }
    }

    private void drawMetric(MatrixStack matrices, float x, float y, float width, String label, String value, float opacity) {
        Color metricColor = TEXT;
        try {
            float numeric = Float.parseFloat(value);
            if ("TPS".equals(label)) {
                metricColor = numeric >= 19.0f ? color(187, 229, 215, 255)
                    : numeric >= 16.0f ? color(255, 204, 132, 255) : color(255, 123, 143, 255);
            } else if (numeric < 60.0f) {
                metricColor = numeric >= 30.0f ? color(255, 218, 166, 255) : color(255, 144, 159, 255);
            }
        } catch (NumberFormatException ignored) {
            metricColor = MUTED;
        }
        String fittedValue = trimToWidth(value, width - 26.0f, 8.0f);
        float labelWidth = Fonts.regular().width(label, 5.8f);
        float valueWidth = Fonts.semibold().width(fittedValue, 8.0f);
        float start = x + (width - labelWidth - valueWidth - 5.0f) * .5f;
        Fonts.regular().draw(matrices, label, start, y + 9.1f, 5.8f, withAlpha(MUTED, opacity));
        Fonts.semibold().draw(matrices, fittedValue, start + labelWidth + 5.0f, y + 7.4f,
            8.0f, withAlpha(metricColor, opacity));
    }

    private void drawMedia(MatrixStack matrices, float x, float y, float width, float opacity,
                           int mouseX, int mouseY, boolean interactive) {
        MediaPlaybackSnapshot snapshot = SystemMediaService.getInstance().snapshot();
        float seconds = frameSeconds;
        String timelineKey = snapshot.sourceId() + '\u0000' + snapshot.title() + '\u0000'
            + snapshot.artist() + '\u0000' + snapshot.durationMs();
        float targetProgress = snapshot.durationMs() <= 0 ? 0
            : Math.min(1, snapshot.estimatedProgressMs() / (float) snapshot.durationMs());
        if (!timelineKey.equals(renderedMediaTimelineKey)) {
            renderedMediaTimelineKey = timelineKey;
            renderedMediaProgress = targetProgress;
            renderedMediaMetadataOpacity = 0.0f;
        } else {
            // Large seeks should not crawl through the old track position.
            renderedMediaProgress = Math.abs(targetProgress - renderedMediaProgress) > .08f
                ? targetProgress : damp(renderedMediaProgress, targetProgress, 12.0f, seconds);
        }
        renderedMediaMetadataOpacity = damp(renderedMediaMetadataOpacity, 1.0f, 13.0f, seconds);
        renderedMediaPlaying = damp(renderedMediaPlaying, snapshot.playing() ? 1.0f : 0.0f, 18.0f, seconds);
        renderedMediaShuffle = damp(renderedMediaShuffle, snapshot.shuffle() ? 1.0f : 0.0f, 18.0f, seconds);
        String title = snapshot.available() ? snapshot.title() : "Media";
        String artist = snapshot.available() ? snapshot.artist() : snapshot.message();
        if (artist.isBlank()) {
            artist = snapshot.available() ? snapshot.source() : "Start playback in a media app";
        }

        Identifier artwork = MediaArtwork.getInstance().texture(snapshot);
        if (!snapshot.artworkKey().equals(renderedArtworkKey)) {
            renderedArtworkKey = snapshot.artworkKey();
            renderedMediaArtworkOpacity = 0.0f;
        }
        renderedMediaArtworkOpacity = artwork == null
            ? 0.0f
            : damp(renderedMediaArtworkOpacity, 1.0f, 11.0f, seconds);
        float fallbackOpacity = opacity * (1.0f - renderedMediaArtworkOpacity);
        RenderUtil.RECT.draw(matrices, x + 9, y + 9, 35, 35, 7,
            color(55, 30, 50, Math.round(235 * Math.max(fallbackOpacity, artwork == null ? opacity : 0.0f))));
        String sourceGlyph = snapshot.source().isBlank()
            ? "M"
            : snapshot.source().substring(0, 1).toUpperCase(Locale.ROOT);
        Fonts.bold().drawCenteredText(matrices, sourceGlyph, x + 26.5f, y + 18, 10,
            color(255, 90, 169, Math.round(255 * Math.max(fallbackOpacity, artwork == null ? opacity : 0.0f))));
        if (artwork != null) {
            RenderUtil.TEXTURE_RECT.draw(matrices, x + 9, y + 9, 35, 35, new Vector4f(7),
                color(255, 255, 255, Math.round(255 * opacity * renderedMediaArtworkOpacity)),
                0, 0, 1, 1, GuiTextures.view(artwork), GuiTextures.linear(), true);
        }
        float metadataCenter = x + 49 + (width - 56) / 2.0f;
        float metadataOpacity = opacity * renderedMediaMetadataOpacity;
        Fonts.semibold().drawCenteredText(matrices, trimToWidth(title, width - 62.0f, 8.0f), metadataCenter, y + 10, 8.0f,
            withAlpha(TEXT, metadataOpacity));
        String subtitle = artist.isBlank() ? snapshot.source() : artist;
        // The native session is the only lyric source. Unsupported songs keep the normal artist row.
        if (!snapshot.lyrics().lines().isEmpty()) {
            String lyric = snapshot.lyrics().cue(snapshot.estimatedProgressMs(), snapshot.durationMs()).current();
            if (!lyric.isBlank()) subtitle = lyric;
        }
        Fonts.regular().drawCenteredText(matrices, trimToWidth(subtitle, width - 62.0f, 6.1f, Fonts.regular()), metadataCenter, y + 25, 6.1f,
            withAlpha(MUTED, metadataOpacity));
        if (snapshot.available() && snapshot.durationMs() > 0) {
            Fonts.regular().draw(matrices, mediaTime(snapshot.estimatedProgressMs()), x + 51, y + 39.5f,
                4.6f, withAlpha(MUTED, opacity * .84f));
            String duration = mediaTime(snapshot.durationMs());
            Fonts.regular().draw(matrices, duration, x + width - 10 - Fonts.regular().width(duration, 4.6f),
                y + 39.5f, 4.6f, withAlpha(MUTED, opacity * .84f));
        }
        float trackWidth = width - 22;
        RenderUtil.RECT.draw(matrices, x + 11, y + 51, trackWidth, 3, 1.5f,
            color(69, 70, 70, Math.round(220 * opacity)));
        RenderUtil.RECT.draw(matrices, x + 11, y + 51, trackWidth * renderedMediaProgress, 3, 1.5f,
            color(220, 226, 220, Math.round(255 * opacity)));
        if (renderedMediaProgress > 0.005f) {
            RenderUtil.RECT.draw(matrices, x + 9 + trackWidth * renderedMediaProgress, y + 50, 5, 5, 2.5f,
                color(244, 247, 244, Math.round(255 * opacity)));
        }

        boolean[] buttonEnabled = {
            snapshot.canPrevious(), snapshot.canToggle(), snapshot.canNext(), snapshot.canShuffle()
        };
        for (int index = 0; index < MEDIA_BUTTON_CENTERS.length; index++) {
            boolean buttonHovered = buttonEnabled[index] && interactive && inside(mouseX, mouseY,
                x + MEDIA_BUTTON_CENTERS[index] - 14, y + 57, 28, 20);
            mediaButtonHover[index] = damp(mediaButtonHover[index], buttonHovered ? 1.0f : 0.0f,
                buttonHovered ? 22.0f : 14.0f, seconds);
            float hover = mediaButtonHover[index];
            float selected = index == 3 ? renderedMediaShuffle : 0.0f;
            if (hover > 0.01f || selected > 0.01f) {
                Color buttonColor = blend(Color.WHITE, ACCENT, selected);
                float buttonOpacity = Math.max(hover * 0.078f, selected * 0.105f);
                RenderUtil.RECT.draw(matrices, x + MEDIA_BUTTON_CENTERS[index] - 13, y + 57.5f, 26, 18, 7,
                    withAlpha(buttonColor, opacity * buttonOpacity));
            }
        }

        float previousOpacity = opacity * controlOpacity(buttonEnabled[0], mediaButtonHover[0]);
        float toggleOpacity = opacity * controlOpacity(buttonEnabled[1], mediaButtonHover[1]);
        float nextOpacity = opacity * controlOpacity(buttonEnabled[2], mediaButtonHover[2]);
        float shuffleOpacity = opacity * controlOpacity(buttonEnabled[3], mediaButtonHover[3]);
        drawMediaIcon(matrices, MEDIA_PREVIOUS_ICON, x + MEDIA_BUTTON_CENTERS[0], y + 66.5f, 11.5f,
            withAlpha(TEXT, previousOpacity));
        drawMediaIcon(matrices, MEDIA_PLAY_ICON, x + MEDIA_BUTTON_CENTERS[1], y + 66.5f, 11.5f,
            withAlpha(TEXT, toggleOpacity * (1.0f - renderedMediaPlaying)));
        drawMediaIcon(matrices, MEDIA_PAUSE_ICON, x + MEDIA_BUTTON_CENTERS[1], y + 66.5f, 11.5f,
            withAlpha(TEXT, toggleOpacity * renderedMediaPlaying));
        drawMediaIcon(matrices, MEDIA_NEXT_ICON, x + MEDIA_BUTTON_CENTERS[2], y + 66.5f, 11.5f,
            withAlpha(TEXT, nextOpacity));
        drawMediaIcon(matrices, MEDIA_SHUFFLE_ICON, x + MEDIA_BUTTON_CENTERS[3], y + 66.5f, 11.0f,
            withAlpha(blend(MUTED, ACCENT, renderedMediaShuffle), shuffleOpacity));
     }

     private static void drawMediaIcon(MatrixStack matrices, Identifier texture, float centerX, float centerY,
                                       float size, Color tint) {
        RenderUtil.TEXTURE_RECT.draw(matrices, centerX - size / 2.0f, centerY - size / 2.0f,
            size, size, 0, tint, 0, 0, 1, 1, texture);
    }

    private static float controlOpacity(boolean enabled, float hover) {
        return enabled ? 0.82f + 0.18f * hover : 0.32f;
    }

    private static String mediaTime(long milliseconds) {
        long seconds = Math.max(0L, milliseconds / 1_000L);
        return seconds >= 3_600L
            ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3_600L, seconds / 60L % 60L, seconds % 60L)
            : String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L);
    }

    private static String trim(String value, int maximum) {
        if (value == null || value.length() <= maximum) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maximum - 3)) + "...";
    }

    private float presentationSeconds() {
        long now = System.nanoTime();
        float seconds = lastPresentationUpdate == 0L
            ? 1.0f / 60.0f
            : Math.min(0.05f, Math.max(0.0f, (now - lastPresentationUpdate) / 1_000_000_000.0f));
        lastPresentationUpdate = now;
        return seconds;
    }

    private static float selectionPhase() {
        final long cycleNanos = 2_800_000_000L;
        return Math.floorMod(System.nanoTime(), cycleNanos) / (float) cycleNanos;
    }

    private static float damp(float current, float target, float speed, float seconds) {
        return current + (target - current) * (1.0f - (float) Math.exp(-speed * seconds));
    }

    private static boolean inside(double mouseX, double mouseY, float x, float y, float width, float height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
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

    private static Color color(int red, int green, int blue, int alpha) {
        return new Color(red, green, blue, alpha);
    }

    private static float parseFloat(String value, float fallback) {
        try {
            float parsed = value == null ? fallback : Float.parseFloat(value);
            return Float.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String keyName(int keyCode) {
        return InputUtil.Type.KEYSYM.createFromCode(keyCode).getLocalizedText().getString();
    }

    public enum TargetPlacement {
        HUD("HUD"),
        ABOVE_TARGET("Above target");

        private final String label;

        TargetPlacement(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        private static TargetPlacement parse(String value, TargetPlacement fallback) {
            if (value == null) {
                return fallback;
            }
            try {
                return TargetPlacement.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return fallback;
            }
        }
    }

    private record ProjectedTarget(float x, float y) {
    }

    private record HudBind(String module, String key, boolean active) {
    }
}
