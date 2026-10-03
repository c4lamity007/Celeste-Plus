package dev.c4lam.celeste.hud;

import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;

import dev.c4lam.celeste.CelesteClient;
import dev.c4lam.celeste.gui.HudTransparentScreen;
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
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.network.AbstractClientPlayerEntity;

import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Atlases;
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
    private static final Identifier PERFORMANCE_GLYPH = Identifier.of(CelesteClient.MOD_ID,
        "textures/hud/glyphs/performance.png");
    private static final Identifier EFFECTS_GLYPH = Identifier.of(CelesteClient.MOD_ID,
        "textures/hud/glyphs/effects.png");
    private static final Identifier KEYBINDS_GLYPH = Identifier.of(CelesteClient.MOD_ID,
        "textures/hud/glyphs/keybinds.png");
    private static final int MEDIA_BUTTON_COUNT = 4;
    private static final HudManager INSTANCE = new HudManager();
    private static Color TEXT = color(247, 247, 250, 255);
    private static Color MUTED = color(164, 164, 174, 255);
    private static final Color ACCENT = color(255, 43, 143, 255);
    // The projected Target HUD intentionally keeps its original scene glass.
    // Movable HUD cards use HudSurface's current near-black material.
    private static final Color TARGET_GLASS_TOP = color(31, 18, 31, 244);
    private static final Color TARGET_GLASS_BOTTOM = color(14, 10, 17, 249);
    private static final Color DEFAULT_GRADIENT_START = color(255, 79, 163, 255);
    private static final Color DEFAULT_GRADIENT_END = color(169, 135, 255, 255);
    private static final float ARRAYLIST_FONT = 7.2f;
    private static final float ARRAYLIST_PAD_X = 4.5f;
    private static final float ARRAYLIST_ROW_HEIGHT = 11.5f;
    private static final dev.c4lam.celeste.render.ArrayListContourRender ARRAYLIST_CONTOUR =
        new dev.c4lam.celeste.render.ArrayListContourRender();

    private final List<HudElementState> elements = new ArrayList<>();
    private final HudTargetTracker targetTracker = HudTargetTracker.getInstance();
    private final Path layoutFile = FabricLoader.getInstance().getConfigDir().resolve("celeste").resolve("hud-layout.properties");
    private final float[] mediaButtonHover = new float[MEDIA_BUTTON_COUNT];
    private float renderedMediaProgress;
    private String renderedMediaTimelineKey = "";
    private float renderedMediaArtworkOpacity;
    private float renderedMediaPlaying;
    private float renderedMediaShuffle;
    private float renderedMediaMetadataOpacity = 1.0f;
    private final DynamicIslandRenderer island = new DynamicIslandRenderer();
    private final DynamicIslandClicks islandClicks = new DynamicIslandClicks();
    private final DynamicIslandPlaybackClock mediaPlaybackClock = new DynamicIslandPlaybackClock();
    private Object islandClickScreen;
    private final HudNetworkClock networkClock = new HudNetworkClock();
    private int lastViewportWidth;
    private int lastViewportHeight;
    private String renderedArtworkKey = "";
    private float frameSeconds = 1.0f / 60.0f;
    private final Map<String, Float> moduleEntrance = new HashMap<>();
    private List<String> frameModuleNames = List.of();
    private List<StatusEffectInstance> frameEffects = List.of();
    private final EffectsHudPresentation effectsPresentation = new EffectsHudPresentation();
    private EffectsHudPresentation.Layout frameEffectsLayout = effectsPresentation.update(List.of(), 0.0F);
    private Map<String, StatusEffectInstance> frameEffectsById = Map.of();
    private List<HudBind> frameBinds = List.of();
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
          elements.add(new HudElementState(HudElementType.ARRAYLIST, 8, 113, false, false));
          elements.add(new HudElementState(HudElementType.TARGET_HUD, 218, 134, false, false));
          elements.add(new HudElementState(HudElementType.EFFECTS, 8, 182, false, false));
          elements.add(new HudElementState(HudElementType.ARMOR, 218, 188, false, false));
          elements.add(new HudElementState(HudElementType.KEYBINDS, 8, 238, false, false));
          elements.add(new HudElementState(HudElementType.SPOTIFY, 116, 48, false, false));
          elements.add(new HudElementState(HudElementType.DYNAMIC_ISLAND, 0, 8, true, false));
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
        updateMediaLyricsDemand();
        initialized = true;
        BetaSubscriptions.register(ClientTickEvents.END_CLIENT_TICK, "celeste:hud/lifecycle",
            ClientTickEvents.EndTick.class, client -> {
                if (client.world == null || client.player == null) {
                    if (island.active()) island.reset();
                    effectsPresentation.reset();
                    networkClock.setEnabled(false);
                }
                updateIslandClicks(client);
                ModuleToggleSounds.tick(client, !closed && CelesteClient.active());
            }, ClientTickEvents.END_CLIENT_TICK::register);
        BetaSubscriptions.register(HudElementRegistry.class, LAYER_ID.toString(), HudElement.class, (context, tickCounter) -> {
            if (closed || !CelesteClient.active()) {
                return;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null || client.player == null) {
                island.reset();
                networkClock.setEnabled(false);
                return;
            }
            if (client.currentScreen == null || client.currentScreen instanceof HudTransparentScreen) {
                render(context, -1, -1, false, null, false);
            } else if (client.currentScreen instanceof ChatScreen) {
                int mouseX = Math.round((float) (client.mouse.getX()
                    * client.getWindow().getScaledWidth() / Math.max(1, client.getWindow().getWidth())));
                int mouseY = Math.round((float) (client.mouse.getY()
                    * client.getWindow().getScaledHeight() / Math.max(1, client.getWindow().getHeight())));
                render(context, mouseX, mouseY, false, null, true);
            } else if (!(client.currentScreen instanceof HudEditorScreen)
                    && !(client.currentScreen instanceof dev.c4lam.celeste.gui.CelesteScreen)) {
                renderIslandOverlay(context);
            }
        }, proxy -> HudElementRegistry.addLast(LAYER_ID, proxy));
    }

    public synchronized void close() {
        closed = true;
        island.reset();
        effectsPresentation.reset();
        frameEffectsById = Map.of();
        frameEffects = List.of();
        frameEffectsLayout = effectsPresentation.update(List.of(), 0.0F);
        cancelIslandClick();
        mediaPlaybackClock.reset();
        networkClock.close();
        ModuleToggleSounds.reset();
        isSelecting = false;
        for (HudElementState element : selectedElements) {
            element.setSelected(false);
        }
        selectedElements.clear();
        moduleEntrance.clear();
        frameModuleNames = List.of();
        frameEffects = List.of();
        frameBinds = List.of();
        SystemMediaService.getInstance().setMediaHudLyricsEnabled(false);
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
        if (type == HudElementType.DYNAMIC_ISLAND) {
            if (!enabled) {
                island.reset();
                cancelIslandClick();
            }
            networkClock.setEnabled(enabled);
            updateMediaLyricsDemand();
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
            state.setScale(HudLayoutPolicy.importedUniformScale(uniform,
                parseFloat(values.getProperty(prefix + "scale_x"), uniform),
                parseFloat(values.getProperty(prefix + "scale_y"), uniform)));
            state.moveImmediately(x, y);
        }
        // Keep existing classic cards, but migrate the old Media HUD island to its own widget.
        if (values.getProperty("hud.dynamic_island.enabled",
                values.getProperty("dynamic_island.enabled")) == null) {
            String legacyStyle = values.getProperty("hud.spotify.style",
                values.getProperty("spotify.style", "DYNAMIC_ISLAND"));
            if ("DYNAMIC_ISLAND".equalsIgnoreCase(legacyStyle)
                    && state(HudElementType.SPOTIFY).enabled()) {
                state(HudElementType.SPOTIFY).setEnabled(false);
                state(HudElementType.DYNAMIC_ISLAND).setEnabled(true);
            }
        }
        state(HudElementType.DYNAMIC_ISLAND).setScale(1.0f);
        updateMediaLyricsDemand();
    }

    public HudElementState hit(double mouseX, double mouseY) {
        for (int index = elements.size() - 1; index >= 0; index--) {
            HudElementState state = elements.get(index);
            if (!state.type().fixed() && state.enabled() && state.contains(mouseX, mouseY)) {
                return state;
            }
        }
        return null;
    }

    /** Runs a media transport action when a visible control is clicked. */
    public boolean handleMediaClick(double mouseX, double mouseY) {
        HudViewport viewport = HudViewport.current();
        double hudX = viewport.screenToHud(mouseX);
        double hudY = viewport.screenToHud(mouseY);
        if (handleIslandClickHud(hudX, hudY)) return true;
        cancelIslandClick();
        HudElementState media = state(HudElementType.SPOTIFY);
        if (!media.enabled() || !media.contains(hudX, hudY)) {
            return false;
        }
        SystemMediaService service = SystemMediaService.getInstance();
        MediaPlaybackSnapshot snapshot = service.snapshot();
        float localX = ((float) hudX - media.displayX()) / media.scaleX();
        float localY = ((float) hudY - media.displayY()) / media.scaleY();
        if (localY < HudPolishPolicy.MEDIA_CONTROL_HIT_TOP
                || localY > HudPolishPolicy.MEDIA_CONTROL_HIT_TOP + HudPolishPolicy.MEDIA_CONTROL_HIT_HEIGHT) {
            return false;
        }

        int button = -1;
        for (int index = 0; index < MEDIA_BUTTON_COUNT; index++) {
            if (Math.abs(localX - HudPolishPolicy.mediaButton(index, media.intrinsicWidth())) <= 14.0f) {
                button = index;
                break;
            }
        }
        if (button < 0) {
            return false;
        }

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

    /** The fixed island is clickable in chat and the editors, but never draggable. */
    public boolean handleIslandClick(double mouseX, double mouseY) {
        HudViewport viewport = HudViewport.current();
        return handleIslandClickHud(viewport.screenToHud(mouseX), viewport.screenToHud(mouseY));
    }

    private boolean handleIslandClickHud(double mouseX, double mouseY) {
        MinecraftClient client = MinecraftClient.getInstance();
        HudElementState fixed = state(HudElementType.DYNAMIC_ISLAND);
        if (!islandControlsAvailable(client) || !island.active()
                || !DynamicIslandClicks.contains(mouseX, mouseY, fixed.x(), fixed.y(),
                    fixed.width(), fixed.height())) {
            cancelIslandClick();
            return false;
        }
        MediaPlaybackSnapshot snapshot = SystemMediaService.getInstance().snapshot();
        if (!snapshot.available() || (!snapshot.canToggle() && !snapshot.canNext())) return false;
        if (islandClickScreen != client.currentScreen) cancelIslandClick();
        islandClickScreen = client.currentScreen;
        island.clicked();
        dispatchIslandAction(islandClicks.click(System.nanoTime(), islandTrack(snapshot)), snapshot);
        return true;
    }

    private void updateIslandClicks(MinecraftClient client) {
        if (!islandClicks.pending()) return;
        if (!islandControlsAvailable(client) || islandClickScreen != client.currentScreen) {
            cancelIslandClick();
            return;
        }
        MediaPlaybackSnapshot snapshot = SystemMediaService.getInstance().snapshot();
        dispatchIslandAction(islandClicks.poll(System.nanoTime(), islandTrack(snapshot)), snapshot);
    }

    private boolean islandControlsAvailable(MinecraftClient client) {
        return !closed && CelesteClient.active() && client.world != null && client.player != null
            && !client.options.hudHidden && state(HudElementType.DYNAMIC_ISLAND).enabled()
            && (client.currentScreen instanceof ChatScreen || client.currentScreen instanceof HudEditorScreen
                || client.currentScreen instanceof dev.c4lam.celeste.gui.CelesteScreen);
    }

    private static String islandTrack(MediaPlaybackSnapshot snapshot) {
        return snapshot.available() ? snapshot.sourceId() + '\u0000' + snapshot.title() + '\u0000'
            + snapshot.artist() + '\u0000' + snapshot.durationMs() : "";
    }

    private static void dispatchIslandAction(DynamicIslandClicks.Action action, MediaPlaybackSnapshot snapshot) {
        SystemMediaService service = SystemMediaService.getInstance();
        if (action == DynamicIslandClicks.Action.TOGGLE && snapshot.canToggle()) service.togglePlayback();
        else if (action == DynamicIslandClicks.Action.NEXT && snapshot.canNext()) service.next();
    }

    private void cancelIslandClick() {
        islandClicks.reset();
        islandClickScreen = null;
    }

    /** Keeps persisted absolute positions reachable after GUI-scale or window-size changes. */
    public boolean clampToViewport(int viewportWidth, int viewportHeight) {
        boolean changed = false;
        for (HudElementState state : elements) {
            if (state.type().fixed()) continue;
            float safeScale = HudLayoutPolicy.fittingUniformScale(state.scale(), state.intrinsicWidth(),
                state.intrinsicHeight(), viewportWidth, viewportHeight);
            if (safeScale != state.scale()) {
                state.setScale(safeScale);
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

    /** Screen overlays use only the fixed island, never the movable HUD/editor controls. */
    public void renderIslandOverlay(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (closed || !CelesteClient.active() || client.options.hudHidden
                || client.world == null || client.player == null) return;
        frameSeconds = presentationSeconds();
        TEXT = new Color(InterfaceConfig.hudTextRgb());
        HudViewport viewport = HudViewport.current();
        updateIsland(client, viewport);
        HudElementState fixed = state(HudElementType.DYNAMIC_ISLAND);
        fixed.updateVisuals(false, false, true);
        if (!fixed.visuallyPresent(true)) return;
        MatrixStack matrices = RenderUtil.matrices();
        matrices.loadIdentity();
        matrices.push();
        viewport.apply(matrices);
        try {
            drawWidget(context, matrices, fixed, false, false, -1, -1, false, false, null);
        } finally {
            matrices.pop();
        }
    }

    private void render(DrawContext context, int mouseX, int mouseY, boolean editor, HudElementState dragging,
                        boolean chatMediaMode) {
        MinecraftClient client = MinecraftClient.getInstance();
        targetTracker.updateLifecycle(client);
        if (!editor && (client.options.hudHidden || client.world == null || client.player == null)) {
            return;
        }
        frameSeconds = presentationSeconds();
        TEXT = new Color(InterfaceConfig.hudTextRgb());
        MUTED = blend(TEXT, color(30, 30, 36, 255), .36f);
        HudViewport viewport = HudViewport.current();
        syncDynamicSizes(viewport.width());
        int viewportWidth = Math.max(1, Math.round(viewport.width()));
        int viewportHeight = Math.max(1, Math.round(viewport.height()));
        int hudMouseX = mouseX < 0 ? mouseX : Math.round(viewport.screenToHud(mouseX));
        int hudMouseY = mouseY < 0 ? mouseY : Math.round(viewport.screenToHud(mouseY));
        boolean viewportChanged = viewportWidth != lastViewportWidth || viewportHeight != lastViewportHeight;
        lastViewportWidth = viewportWidth;
        lastViewportHeight = viewportHeight;
        boolean clamped = clampToViewport(viewportWidth, viewportHeight);
        if (clamped && viewportChanged) {
            // Content animation must not perform disk writes every frame.
            saveLayout();
        }
        MatrixStack matrices = RenderUtil.matrices();
        matrices.loadIdentity();
        GuiRenderer.getInstance().clearScissors();
        matrices.push();
        viewport.apply(matrices);
        var guiMatrices = context.getMatrices();
        guiMatrices.pushMatrix();
        guiMatrices.scale(viewport.renderScale(), viewport.renderScale());

        try {

        for (HudElementState state : elements) {
            boolean presentationVisible = presentationVisible(state, editor, chatMediaMode);
            boolean interactive = editor || (chatMediaMode && state.type() == HudElementType.SPOTIFY);
            boolean hovered = interactive && state.enabled() && state.contains(hudMouseX, hudMouseY);
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

        // HUD panels are flat surfaces and never capture the world for glass/blur.
        for (HudElementState state : elements) {
            boolean presentationVisible = presentationVisible(state, editor, chatMediaMode);
            boolean interactive = editor || (chatMediaMode && state.type() == HudElementType.SPOTIFY);
            boolean hovered = interactive && state.enabled() && state.contains(hudMouseX, hudMouseY);
            if (!state.visuallyPresent(presentationVisible)) {
                continue;
            }
            if (targetAboveEntity && state.type() == HudElementType.TARGET_HUD) {
                continue;
            }
            drawWidget(context, matrices, state, hovered, state == dragging, hudMouseX, hudMouseY, editor,
                interactive, targetPresentation);
        }
        if (projectedTarget != null) {
            RenderUtil.captureBlur();
            drawProjectedTarget(matrices, targetState, targetPresentation, projectedTarget);
        }
        } finally {
            guiMatrices.popMatrix();
            matrices.pop();
        }
    }

    private boolean presentationVisible(HudElementState state, boolean editor, boolean chatMediaMode) {
        if (editor) {
            return true;
        }
        if (chatMediaMode && !state.type().visibleInChat()) {
            return false;
        }
        return switch (state.type()) {
            case ARRAYLIST -> !frameModuleNames.isEmpty();
            case EFFECTS -> !frameEffectsLayout.items().isEmpty();
            case KEYBINDS -> !frameBinds.isEmpty();
            case SPOTIFY -> SystemMediaService.getInstance().snapshot().available();
            default -> true;
        };
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

        HudViewport viewport = HudViewport.current();
        float viewportWidth = viewport.width();
        float viewportHeight = viewport.height();
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
        RenderUtil.BLUR_RECT.draw(matrices, x, y, baseWidth, baseHeight, new Vector4f(9.0F),
            withAlpha(TARGET_GLASS_TOP, visibility), withAlpha(TARGET_GLASS_TOP, visibility),
            withAlpha(TARGET_GLASS_BOTTOM, visibility), withAlpha(TARGET_GLASS_BOTTOM, visibility),
            InterfaceConfig.glassTintMix());
        drawTarget(matrices, x, y, baseWidth, visibility, presentation);
        matrices.pop();
    }

    private void drawWidget(DrawContext context, MatrixStack matrices, HudElementState state, boolean hovered,
                            boolean dragging, int mouseX, int mouseY, boolean editor, boolean interactive,
                            HudTargetTracker.Presentation frameTarget) {
        float visibility = state.visibility();
        if (state.type() == HudElementType.DYNAMIC_ISLAND) {
            island.draw(matrices, state.x(), state.y(), TEXT, visibility);
            String time = networkClock.text();
            float clockWidth = Fonts.semibold().width(time, 6.7f);
            float clockX = state.x() - 7.0f - clockWidth;
            if (!clockSpaceAvailable(clockX, state.y(), clockWidth, state.height())) {
                clockX = state.x() + state.width() + 7.0f;
            }
            if (clockSpaceAvailable(clockX, state.y(), clockWidth, state.height())) {
                Fonts.semibold().draw(matrices, time, clockX,
                    state.y() + (state.height() - Fonts.semibold().height(6.7f)) * .5f,
                    6.7f, withAlpha(TEXT, visibility));
            }
            return;
        }
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

        float corner = HudSurface.radius(state.type(), baseWidth, baseHeight);
        if (state.type() != HudElementType.ARRAYLIST && state.type() != HudElementType.ARMOR
            && state.type() != HudElementType.EFFECTS && state.type() != HudElementType.KEYBINDS) {
            RenderUtil.RECT.draw(matrices, x, y, baseWidth, baseHeight, corner, HudSurface.color(visibility));
        }
        if (selection > 0.002f && state.type() != HudElementType.ARRAYLIST && state.type() != HudElementType.ARMOR
            && state.type() != HudElementType.EFFECTS && state.type() != HudElementType.KEYBINDS) {
            RenderUtil.RECT.draw(matrices, x, y, baseWidth, baseHeight, corner,
                withAlpha(color(255, 255, 255, 12), visibility * selection));
        }
         switch (state.type()) {
             case DYNAMIC_ISLAND -> { }
             case WATERMARK -> drawWatermark(matrices, x, y, baseWidth, visibility);
             case COORDINATES -> drawCoordinates(matrices, x, y, baseWidth, visibility);
             case TARGET_HUD -> drawTarget(matrices, x, y, baseWidth, visibility, targetPresentation);
             case EFFECTS -> drawEffects(matrices, x, y, visibility);
             case ARMOR -> {
                 var gui = context.getMatrices();
                 gui.pushMatrix();
                 gui.translate(centerX, centerY);
                 gui.scale(motionScale, motionScale);
                 gui.translate(-centerX, -centerY);
                 gui.translate(x, y);
                 gui.scale(scaleX, scaleY);
                 gui.translate(-x, -y);
                 dev.c4lam.celeste.render.VanillaItemSurface.drawSmooth(context, x, y, baseWidth, baseHeight,
                     corner, HudSurface.color(visibility));
                 drawArmor(context, matrices, x, y, visibility);
                 gui.popMatrix();
             }
             case KEYBINDS -> drawKeybinds(matrices, x, y, baseWidth, visibility);
             case ARRAYLIST -> drawArraylist(matrices, x, y, baseWidth, visibility);
             case FPS -> drawPerformanceMetric(matrices, x, y, baseWidth, visibility);
             case SPOTIFY -> drawMedia(matrices, x, y, baseWidth, visibility,
                 Math.round(x + (mouseX - x) / scaleX),
                 Math.round(y + (mouseY - y) / scaleY), interactive);
         }
        if (selection > 0.002f) {
            RenderUtil.SELECTION_OUTLINE.draw(matrices, x, y, baseWidth, baseHeight,
                corner, 1.2f, selectionPhase(), visibility * selection);
        }
        matrices.pop();
    }

    private boolean clockSpaceAvailable(float x, float y, float width, float height) {
        if (x < 2 || x + width > HudViewport.current().width() - 2) return false;
        for (HudElementState other : elements) {
            if (other.type().fixed() || !other.enabled() || !other.visuallyPresent(true)) continue;
            if (x < other.displayX() + other.width() + 3 && x + width + 3 > other.displayX()
                && y < other.displayY() + other.height() && y + height > other.displayY()) return false;
        }
        return true;
    }

    private void drawWatermark(MatrixStack matrices, float x, float y, float width, float opacity) {
        String brand = "Celeste";
        String suffix = "  UID " + LicenseIdentity.uid();
        float brandWidth = Fonts.semibold().width(brand, 7.2f);
        float fullWidth = brandWidth + Fonts.regular().width(suffix, 6.2f);
        float textX = x + (width - fullWidth) * 0.5f;
        Fonts.semibold().draw(matrices, brand, textX, y + (20 - Fonts.semibold().height(7.2f)) * .5f, 7.2f,
            withAlpha(TEXT, opacity));
        Fonts.regular().draw(matrices, suffix, textX + brandWidth, y + (20 - Fonts.regular().height(6.2f)) * .5f, 6.2f,
            withAlpha(MUTED, opacity));
    }

    private void drawCoordinates(MatrixStack matrices, float x, float y, float width, float opacity) {
        String text = coordinateText();
        float textWidth = Fonts.semibold().width(text, 7.0F);
        Fonts.semibold().draw(matrices, text,
            x + (width - textWidth) * 0.5F,
            y + (15.0F - Fonts.semibold().height(7.0F)) * 0.5F,
            7.0F, withAlpha(TEXT, opacity));
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
                ? "?" : presentation.name().substring(0, presentation.name().offsetByCodePoints(0, 1))
                    .toUpperCase(Locale.ROOT);
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
        String suffix = "…";
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

    private void drawEffects(MatrixStack matrices, float x, float y, float opacity) {
        EffectsHudPresentation.Layout layout = frameEffectsLayout;
        float surfaceWidth = layout.surfaceWidth();
        float surfaceHeight = layout.surfaceHeight();
        drawEffectsSurface(matrices, x, y, surfaceWidth, surfaceHeight, opacity);
        drawHudGlyph(matrices, EFFECTS_GLYPH, x + 5.0F,
            y + (EffectsHudPresentation.TITLE_HEIGHT - 9.0F) * 0.5F,
            9.0F, withAlpha(MUTED, opacity));
        Fonts.semibold().draw(matrices, "Effects", x + 18.0F,
            y + (EffectsHudPresentation.TITLE_HEIGHT - Fonts.semibold().height(7.0F)) * 0.5F,
            7.0F, withAlpha(TEXT, opacity));
        if (layout.stripOpacity() > 0.002F) {
            RenderUtil.RECT.draw(matrices, x + 5.0F, y + EffectsHudPresentation.TITLE_HEIGHT - 0.5F,
                surfaceWidth - 10.0F, 0.5F, 0.25F,
                withAlpha(color(255, 255, 255, 16), opacity * layout.stripOpacity()));
        }
        for (EffectsHudPresentation.Item item : layout.items()) {
            float cardAlpha = opacity * item.reveal() * item.opacity();
            if (cardAlpha <= 0.002F) continue;
            drawEffectsSurface(matrices, x + item.x(), y + item.y(), item.cardWidth(),
                EffectsHudPresentation.WARNING_HEIGHT, cardAlpha);
        }
        // The sprites share Celeste's queue with these surfaces. Vanilla's GUI
        // queue flushes first and would leave icons underneath the black cards.
        SpriteAtlasTexture atlas = MinecraftClient.getInstance().getAtlasManager().getAtlasTexture(Atlases.GUI);
        long nowNanos = System.nanoTime();
        float nameSize = 6.7F;
        float timerSize = 6.7F;
        for (EffectsHudPresentation.Item item : layout.items()) {
            StatusEffectInstance effect = frameEffectsById.get(item.key());
            if (effect == null) continue;
            float alpha = opacity * item.opacity();
            if (alpha <= 0.002F) continue;
            Sprite sprite = atlas.getSprite(InGameHud.getEffectTexture(effect.getEffectType()));
            float iconSize = EffectsHudPresentation.ICON_SIZE * item.iconScale();
            RenderUtil.TEXTURE_RECT.draw(matrices, x + item.iconX(), y + item.iconY(), iconSize, iconSize,
                new Vector4f(0.0F), withAlpha(Color.WHITE, alpha), sprite.getMinU(), sprite.getMinV(),
                sprite.getMaxU() - sprite.getMinU(), sprite.getMaxV() - sprite.getMinV(),
                atlas.getGlTextureView(), GuiTextures.nearest(), true);
            float contentAlpha = alpha * item.textAlpha();
            if (contentAlpha <= 0.002F) continue;
            String name = effectName(effect) + (effect.getAmplifier() > 0
                ? " " + (effect.getAmplifier() + 1) : "");
            String countdown = EffectsHudPresentation.warningCountdown(item.ticks());
            float countdownWidth = Fonts.semibold().width(countdown, timerSize);
            float cardX = x + item.x();
            float cardY = y + item.y();
            float countdownX = cardX + item.cardWidth() - EffectsHudPresentation.RIGHT_PADDING - countdownWidth;
            float availableName = Math.max(0.0F, countdownX - cardX
                - EffectsHudPresentation.WARNING_NAME_X - EffectsHudPresentation.WARNING_TIMER_GAP);
            if (availableName < 1.0F) continue;
            String visibleName = trimToWidth(name, availableName, nameSize, Fonts.semibold());
            float pulse = EffectsHudPresentation.pulse(item.ticks(), nowNanos);
            Color warningColor = blend(TEXT, color(255, 218, 110, 255), pulse);
            Fonts.semibold().draw(matrices, visibleName, cardX + EffectsHudPresentation.WARNING_NAME_X,
                cardY + (EffectsHudPresentation.WARNING_HEIGHT - Fonts.semibold().height(nameSize)) * 0.5F,
                nameSize, withAlpha(warningColor, contentAlpha));
            Fonts.semibold().draw(matrices, countdown, countdownX,
                cardY + (EffectsHudPresentation.WARNING_HEIGHT - Fonts.semibold().height(timerSize)) * 0.5F,
                timerSize, withAlpha(warningColor, contentAlpha));
        }
    }

    private static void drawEffectsSurface(MatrixStack matrices, float x, float y, float width,
                                           float height, float opacity) {
        RenderUtil.RECT.draw(matrices, x, y, width, height, 6.0F,
            withAlpha(color(255, 255, 255, 12), opacity));
        RenderUtil.RECT.draw(matrices, x + 0.5F, y + 0.5F, width - 1.0F, height - 1.0F, 5.5F,
            HudSurface.color(opacity));
    }

    private String effectName(StatusEffectInstance effect) {
        return effect.getEffectType().value().getName().getString();
    }

    private void drawArmor(DrawContext context, MatrixStack matrices, float x, float y, float opacity) {
        MinecraftClient client = MinecraftClient.getInstance();
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        float itemSize = HudGlyphLayout.ARMOR_ITEM_SIZE;
        float barWidth = 16.0f;
        float barHeight = 1.5f;
        float barGap = 1.5f;
        for (int index = 0; index < slots.length; index++) {
            float itemX = HudGlyphLayout.armorItemX(x, HudElementType.ARMOR.width(), index);
            ItemStack stack = client.player == null ? ItemStack.EMPTY : client.player.getEquippedStack(slots[index]);
            boolean showDurability = !stack.isEmpty() && stack.isDamageable();
            float groupHeight = itemSize + (showDurability ? barGap + barHeight : 0.0f);
            // Account for transparent padding above the item artwork, centering the visible row.
            float itemY = HudGlyphLayout.centeredY(y, HudElementType.ARMOR.height(), groupHeight)
                - (showDurability ? 1.0f : 0.0f);
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
            if (!showDurability) {
                continue;
            }
            int remaining = Math.max(0, stack.getMaxDamage() - stack.getDamage());
            float ratio = stack.getMaxDamage() <= 0 ? 1.0f
                : Math.max(0.0f, Math.min(1.0f, remaining / (float) stack.getMaxDamage()));
            Color value = new Color(stack.getItemBarColor());
            float barX = itemX + (itemSize - barWidth) * 0.5f;
            float barY = itemY + itemSize + barGap;
            RenderUtil.RECT.draw(matrices, barX, barY, barWidth, barHeight, barHeight * 0.5f,
                color(255, 255, 255, Math.round(24 * opacity)));
            float fillWidth = barWidth * ratio;
            if (fillWidth > 0.0f) {
                RenderUtil.RECT.draw(matrices, barX, barY, fillWidth, barHeight,
                    Math.min(barHeight, fillWidth) * 0.5f, withAlpha(value, opacity));
            }
        }
    }

    private void drawKeybinds(MatrixStack matrices, float x, float y, float width, float opacity) {
        List<HudBind> bound = frameBinds;
        KeybindsHudLayout.Layout layout = keybindLayout(HudViewport.current().width());
        RenderUtil.RECT.draw(matrices, x, y, layout.width(), layout.height(), 6.0F,
            color(255, 255, 255, Math.round(12 * opacity)));
        RenderUtil.RECT.draw(matrices, x + 0.5F, y + 0.5F, layout.width() - 1, layout.height() - 1,
            5.5F, HudSurface.color(opacity));
        drawHudGlyph(matrices, KEYBINDS_GLYPH, x + KeybindsHudLayout.GLYPH_LEFT,
            y + (KeybindsHudLayout.HEADER_HEIGHT - KeybindsHudLayout.GLYPH_SIZE) * 0.5F,
            KeybindsHudLayout.GLYPH_SIZE, withAlpha(MUTED, opacity * 0.82F));
        Fonts.semibold().draw(matrices, KeybindsHudLayout.TITLE, x + KeybindsHudLayout.TITLE_LEFT,
            y + (KeybindsHudLayout.HEADER_HEIGHT - Fonts.semibold().height(KeybindsHudLayout.TITLE_FONT)) * 0.5F,
            KeybindsHudLayout.TITLE_FONT, withAlpha(TEXT, opacity));
        if (!bound.isEmpty()) {
            RenderUtil.RECT.draw(matrices, x + KeybindsHudLayout.LABEL_LEFT,
                y + KeybindsHudLayout.HEADER_HEIGHT - 0.5F,
                layout.width() - KeybindsHudLayout.LABEL_LEFT - KeybindsHudLayout.RIGHT_PADDING, 0.5F,
                0.25F, color(255, 255, 255, Math.round(16 * opacity)));
        }
        for (int index = 0; index < bound.size(); index++) {
            HudBind bind = bound.get(index);
            String key = trimToWidth(bind.key(), 31.0F, KeybindsHudLayout.KEY_FONT);
            float keyWidth = Fonts.semibold().width(key, KeybindsHudLayout.KEY_FONT);
            float keycapX = layout.keycapX(x, index, keyWidth);
            float keycapY = layout.keycapY(y, index);
            float keycapWidth = KeybindsHudLayout.keycapWidth(keyWidth);
            RenderUtil.RECT.draw(matrices, keycapX, keycapY, keycapWidth, KeybindsHudLayout.KEYCAP_HEIGHT, 3.0F,
                color(255, 255, 255, Math.round(17 * opacity)));
            Fonts.regular().draw(matrices, trimToWidth(KeybindsHudLayout.compactLabel(bind.module()),
                    layout.availableLabelWidth(index, keyWidth), KeybindsHudLayout.LABEL_FONT, Fonts.regular()),
                layout.labelX(x), layout.rowTextY(y, index, Fonts.regular().height(KeybindsHudLayout.LABEL_FONT)),
                KeybindsHudLayout.LABEL_FONT, withAlpha(TEXT, opacity * 0.9F));
            Fonts.semibold().draw(matrices, key, layout.keyX(x, index, keyWidth),
                layout.rowTextY(y, index, Fonts.semibold().height(KeybindsHudLayout.KEY_FONT)),
                KeybindsHudLayout.KEY_FONT, withAlpha(MUTED, opacity));
        }
    }

    private KeybindsHudLayout.Layout keybindLayout(float viewportWidth) {
        float[] labelWidths = new float[frameBinds.size()];
        float[] keyWidths = new float[frameBinds.size()];
        for (int index = 0; index < frameBinds.size(); index++) {
            HudBind bind = frameBinds.get(index);
            labelWidths[index] = Fonts.regular().width(KeybindsHudLayout.compactLabel(bind.module()), KeybindsHudLayout.LABEL_FONT);
            keyWidths[index] = Fonts.semibold().width(trimToWidth(bind.key(), 31.0F, KeybindsHudLayout.KEY_FONT), KeybindsHudLayout.KEY_FONT);
        }
        return KeybindsHudLayout.measure(labelWidths, keyWidths, viewportWidth,
            Fonts.semibold().width(KeybindsHudLayout.TITLE, KeybindsHudLayout.TITLE_FONT));
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

    private List<StatusEffectInstance> activeEffects() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player == null ? List.of()
            : EffectsHudOrder.all(client.player.getStatusEffects(), StatusEffectInstance::getDuration);
    }

    private List<HudBind> configuredBinds() {
        return GuiCatalog.modules().stream()
            .flatMap(module -> module.settings().stream()
                .filter(GuiSetting.Keybind.class::isInstance)
                .map(GuiSetting.Keybind.class::cast)
                .filter(keybind -> keybind.keyCode() != GLFW.GLFW_KEY_UNKNOWN)
                .map(keybind -> new HudBind(bindingName(module.name(), keybind.label()),
                    keyName(keybind.keyCode()))))
            .sorted(Comparator.comparing(HudBind::module, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(HudBind::key, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    private static String bindingName(String module, String setting) {
        return "Bind".equalsIgnoreCase(setting) || "Keybind".equalsIgnoreCase(setting)
            || "Action".equalsIgnoreCase(setting) ? module : module + " · " + setting;
    }

    private float arraylistContentWidth(List<String> names) {
        float max = 27.0f;
        for (String name : names) {
            max = Math.max(max, Fonts.semibold().width(name, ARRAYLIST_FONT));
        }
        return Math.max(36.0f, max + ARRAYLIST_PAD_X * 2.0f);
    }

    private void syncDynamicSizes(float viewportWidth) {
        frameModuleNames = enabledModuleNames();
        frameEffects = activeEffects();
        frameBinds = configuredBinds();
        MinecraftClient client = MinecraftClient.getInstance();
        viewportWidth = Float.isFinite(viewportWidth) ? Math.max(1.0F, viewportWidth) : 1.0F;

        resizeSideAware(state(HudElementType.ARRAYLIST), arraylistContentWidth(frameModuleNames),
            Math.max(1, frameModuleNames.size()) * ARRAYLIST_ROW_HEIGHT, viewportWidth);
        resizeSideAware(state(HudElementType.COORDINATES),
            Fonts.semibold().width(coordinateText(), 7.0f) + 8.0f, 15.0f, viewportWidth);
        float watermarkWidth = Fonts.semibold().width("Celeste", 7.2f)
            + Fonts.regular().width("  UID " + LicenseIdentity.uid(), 6.2f);
        resizeSideAware(state(HudElementType.WATERMARK),
            watermarkWidth + 12.0f, 20.0f, viewportWidth);

        List<EffectsHudPresentation.Effect> effectSamples = new ArrayList<>();
        Map<String, StatusEffectInstance> effectLookup = new HashMap<>();
        for (StatusEffectInstance effect : frameEffects) {
            String key = Registries.STATUS_EFFECT.getId(effect.getEffectType().value()).toString();
            effectLookup.put(key, effect);
            String name = effectName(effect) + (effect.getAmplifier() > 0 ? " " + (effect.getAmplifier() + 1) : "");
            effectSamples.add(new EffectsHudPresentation.Effect(key, effect.getDuration(),
                Fonts.semibold().width(name, 6.7F), Fonts.semibold().width("10s", 6.7F)));
        }
        frameEffectsLayout = effectsPresentation.update(effectSamples, frameSeconds,
            18.0F + Fonts.semibold().width("Effects", 7.0F) + 5.0F);
        // Keep a departing sprite/name only until its row has finished fading.
        for (EffectsHudPresentation.Item item : frameEffectsLayout.items()) {
            if (!effectLookup.containsKey(item.key())) {
                StatusEffectInstance departing = frameEffectsById.get(item.key());
                if (departing != null) effectLookup.put(item.key(), departing);
            }
        }
        frameEffectsById = effectLookup;
        resizeSideAware(state(HudElementType.EFFECTS), frameEffectsLayout.width(),
            frameEffectsLayout.height(), viewportWidth);
        KeybindsHudLayout.Layout bindLayout = keybindLayout(viewportWidth);
        resizeSideAware(state(HudElementType.KEYBINDS), bindLayout.width(),
            bindLayout.height(), viewportWidth);
        resizeSideAware(state(HudElementType.FPS),
            HudGlyphLayout.inlineWidth(Fonts.semibold().width(performanceMetricText(client), 6.7f)),
            17.0f, viewportWidth);
        MediaPlaybackSnapshot media = SystemMediaService.getInstance().snapshot();
        float mediaWidth = HudPolishPolicy.mediaWidth(Fonts.semibold().width(mediaTitle(media), 8.4f),
            Fonts.regular().width(media.artist(), 7.0f));
        resizeSideAware(state(HudElementType.SPOTIFY), mediaWidth, HudPolishPolicy.MEDIA_HEIGHT, viewportWidth);

        updateIsland(client, HudViewport.current());
    }

    private void updateIsland(MinecraftClient client, HudViewport viewport) {
        float viewportWidth = viewport.width();
        HudElementState fixed = state(HudElementType.DYNAMIC_ISLAND);
        island.update(client, fixed.enabled(), SystemMediaService.getInstance().snapshot(),
            frameSeconds, viewportWidth);
        networkClock.setEnabled(fixed.enabled());
        fixed.setScale(1.0f);
        fixed.setContentSize(island.width(), island.height());
        fixed.moveImmediately((viewportWidth - fixed.width()) * .5f, 8.0f);
        double mouseX = viewport.rawMouseToHud(client.mouse.getX(), client.getWindow().getWidth(),
            client.getWindow().getFramebufferWidth());
        double mouseY = viewport.rawMouseToHud(client.mouse.getY(), client.getWindow().getHeight(),
            client.getWindow().getFramebufferHeight());
        island.hovered(islandControlsAvailable(client) && DynamicIslandClicks.contains(mouseX, mouseY,
            fixed.x(), fixed.y(), fixed.width(), fixed.height()));
    }

    private static void resizeSideAware(HudElementState state, float width, float height, float viewportWidth) {
        float oldWidth = state.width();
        boolean right = HudLayoutPolicy.alignsRight(state.x(), oldWidth, viewportWidth);
        state.setContentSize(width, height);
        float newX = HudLayoutPolicy.anchoredX(state.x(), oldWidth, state.width(), right);
        if (Math.abs(newX - state.x()) > 0.01f) {
            state.shiftForContentResize(newX - state.x());
        }
    }

    private static String coordinateText() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return "0, 0, 0";
        return HudLayoutPolicy.coordinates((int) Math.floor(client.player.getX()),
            (int) Math.floor(client.player.getY()), (int) Math.floor(client.player.getZ()));
    }

    private static float arraylistRowWidth(String name) {
        return Fonts.semibold().width(name, ARRAYLIST_FONT) + ARRAYLIST_PAD_X * 2.0f;
    }

    private void drawArraylist(MatrixStack matrices, float x, float y, float width, float opacity) {
        List<String> enabled = frameModuleNames;
        moduleEntrance.keySet().retainAll(enabled);
        if (enabled.isEmpty()) {
            return;
        }
        float viewportWidth = HudViewport.current().width();
        HudElementState arrayState = state(HudElementType.ARRAYLIST);
        boolean rightAligned = HudLayoutPolicy.alignsRight(arrayState.displayX(), arrayState.width(), viewportWidth);
        Color start = withAlpha(arraylistColor("Gradient start", DEFAULT_GRADIENT_START), opacity);
        Color end = withAlpha(arraylistColor("Gradient end", DEFAULT_GRADIENT_END), opacity);
        Color glint = withAlpha(color(244, 238, 255, 255), opacity);
        long animationTime = System.nanoTime();
        int count = enabled.size();
        float[] rowWidths = new float[count];
        for (int index = 0; index < count; index++) rowWidths[index] = arraylistRowWidth(enabled.get(index));
        ARRAYLIST_CONTOUR.draw(matrices, rightAligned ? x + width - rowWidths[0] : x, y,
            rowWidths, ARRAYLIST_ROW_HEIGHT, rightAligned, HudSurface.color(opacity));
        for (int index = 0; index < count; index++) {
            String name = enabled.get(index);
            float entrance = damp(moduleEntrance.getOrDefault(name, 0.0f), 1.0f, 16.0f, frameSeconds);
            moduleEntrance.put(name, entrance);
            float rowWidth = rowWidths[index];
            float rowX = HudLayoutPolicy.rowX(x, width, rowWidth, rightAligned);
            float rowY = y + index * ARRAYLIST_ROW_HEIGHT;
            float slide = (1.0f - entrance) * (rightAligned ? 2.0f : -2.0f);
            Fonts.semibold().drawGradient(matrices, name, rowX + ARRAYLIST_PAD_X + slide,
                rowY + 1.7f, ARRAYLIST_FONT, withAlpha(start, entrance), withAlpha(end, entrance),
                withAlpha(glint, entrance), HudLayoutPolicy.glintPhase(animationTime, index), .72f);
        }
    }

    private void drawPerformanceMetric(MatrixStack matrices, float x, float y, float width, float opacity) {
        String text = performanceMetricText(MinecraftClient.getInstance());
        float textWidth = Fonts.semibold().width(text, 6.7F);
        float contentX = HudGlyphLayout.inlineX(x, width, textWidth);
        drawHudGlyph(matrices, PERFORMANCE_GLYPH, contentX,
            HudGlyphLayout.centeredY(y, 17.0F, HudGlyphLayout.INLINE_SIZE),
            HudGlyphLayout.INLINE_SIZE, withAlpha(MUTED, opacity));
        Fonts.semibold().draw(matrices, text,
            contentX + HudGlyphLayout.INLINE_SIZE + HudGlyphLayout.INLINE_GAP,
            y + (17.0F - Fonts.semibold().height(6.7F)) * 0.5F,
            6.7F, withAlpha(TEXT, opacity));
    }

    private static void drawHudGlyph(MatrixStack matrices, Identifier texture, float x, float y,
                                     float size, Color tint) {
        HudGlyphLayout.GlyphQuad quad = HudGlyphLayout.glyphQuad(x, y, size);
        RenderUtil.TEXTURE_RECT.draw(matrices, quad.x(), quad.y(), quad.size(), quad.size(), 0.0F, tint,
            0.0F, 0.0F, 1.0F, 1.0F, texture);
    }

    private static String performanceMetricText(MinecraftClient client) {
        double blocksPerSecond = 0.0D;
        if (client.player != null) {
            Vec3d velocity = client.player.getVelocity();
            blocksPerSecond = Math.hypot(velocity.x, velocity.z) * 20.0D;
        }
        return HudLayoutPolicy.performance(client.getCurrentFps(), blocksPerSecond,
            ServerTickRateTracker.getInstance().formatted(client.world));
    }

    private void drawMedia(MatrixStack matrices, float x, float y, float width, float opacity,
                           int mouseX, int mouseY, boolean interactive) {
        MediaPlaybackSnapshot snapshot = SystemMediaService.getInstance().snapshot();
        updateMediaPresentation(snapshot);
        drawClassicMedia(matrices, snapshot, x, y, width, opacity, mouseX, mouseY, interactive);
    }

    private void updateMediaPresentation(MediaPlaybackSnapshot snapshot) {
        float seconds = frameSeconds;
        String timelineKey = snapshot.sourceId() + '\u0000' + snapshot.title() + '\u0000'
            + snapshot.artist() + '\u0000' + snapshot.durationMs();
        long positionMs = mediaPlaybackClock.positionMs(snapshot);
        float targetProgress = snapshot.durationMs() <= 0 ? 0
            : Math.min(1, positionMs / (float) snapshot.durationMs());
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
    }

    private void drawClassicMedia(MatrixStack matrices, MediaPlaybackSnapshot snapshot,
                                  float x, float y, float width, float opacity,
                                  int mouseX, int mouseY, boolean interactive) {
        float seconds = frameSeconds;
        String title = snapshot.available() ? snapshot.title() : "Nothing playing";
        String artist = snapshot.available() ? snapshot.artist() : "Play a song";
        if (artist.isBlank()) artist = snapshot.source();

        Identifier artwork = MediaArtwork.getInstance().texture(snapshot);
        if (!snapshot.artworkKey().equals(renderedArtworkKey)) {
            renderedArtworkKey = snapshot.artworkKey();
            renderedMediaArtworkOpacity = 0.0f;
        }
        renderedMediaArtworkOpacity = artwork == null ? 0.0f
            : damp(renderedMediaArtworkOpacity, 1.0f, 11.0f, seconds);
        RenderUtil.RECT.draw(matrices, x + 8, y + 8, 30, 30, 6,
            withAlpha(color(24, 24, 29, 255), opacity));
        if (artwork != null) {
            var view = GuiTextures.view(artwork);
            if (view != null && !view.isClosed() && view.getWidth(0) > 0 && view.getHeight(0) > 0) {
                float aspect = view.getWidth(0) / (float) view.getHeight(0);
                float u = aspect > 1.0f ? (1.0f - 1.0f / aspect) * 0.5f : 0.0f;
                float v = aspect < 1.0f ? (1.0f - aspect) * 0.5f : 0.0f;
                RenderUtil.TEXTURE_RECT.draw(matrices, x + 8, y + 8, 30, 30, new Vector4f(6),
                    color(255, 255, 255, Math.round(255 * opacity * renderedMediaArtworkOpacity)),
                    u, v, 1.0f - 2.0f * u, 1.0f - 2.0f * v, view, GuiTextures.linear(), true);
            }
        }
        float metadataOpacity = opacity * renderedMediaMetadataOpacity;
        float textWidth = width - 54;
        float titleSize = HudPolishPolicy.fontSize(Fonts.semibold().width(title, 8.4f), textWidth, 7.4f, 8.4f);
        float artistSize = HudPolishPolicy.fontSize(Fonts.regular().width(artist, 7.0f), textWidth, 6.1f, 7.0f);
        Fonts.semibold().draw(matrices, trimToWidth(title, textWidth, titleSize), x + 46, y + 10, titleSize,
            withAlpha(TEXT, metadataOpacity));
        Fonts.regular().draw(matrices, trimToWidth(artist, textWidth, artistSize, Fonts.regular()),
            x + 46, y + 23, artistSize, withAlpha(MUTED, metadataOpacity));

        long progress = snapshot.durationMs() > 0 ? Math.round(renderedMediaProgress * snapshot.durationMs()) : 0L;
        String elapsed = mediaTime(progress);
        String remaining = "-" + mediaTime(Math.max(0L, snapshot.durationMs() - progress));
        float elapsedWidth = Fonts.regular().width(elapsed, 4.8f);
        float remainingWidth = Fonts.regular().width(remaining, 4.8f);
        Fonts.regular().draw(matrices, elapsed, x + 8, y + 43, 4.8f, withAlpha(MUTED, opacity));
        Fonts.regular().draw(matrices, remaining, x + width - 8 - remainingWidth, y + 43, 4.8f,
            withAlpha(MUTED, opacity));
        float trackX = x + 12 + elapsedWidth;
        float trackWidth = Math.max(1, width - 24 - elapsedWidth - remainingWidth);
        RenderUtil.RECT.draw(matrices, trackX, y + 45, trackWidth, 2.5f, 1.25f,
            color(57, 57, 63, Math.round(235 * opacity)));
        RenderUtil.RECT.draw(matrices, trackX, y + 45, trackWidth * renderedMediaProgress, 2.5f, 1.25f,
            withAlpha(TEXT, opacity));

        boolean[] buttonEnabled = {
            snapshot.canPrevious(), snapshot.canToggle(), snapshot.canNext(), snapshot.canShuffle()
        };
        float controlCell = HudPolishPolicy.mediaButtonCell(width);
        for (int index = 0; index < MEDIA_BUTTON_COUNT; index++) {
            boolean buttonHovered = buttonEnabled[index] && interactive && inside(mouseX, mouseY,
                x + HudPolishPolicy.mediaButton(index, width) - controlCell * 0.5F,
                y + HudPolishPolicy.MEDIA_CONTROL_HIT_TOP,
                controlCell, HudPolishPolicy.MEDIA_CONTROL_HIT_HEIGHT);
            mediaButtonHover[index] = damp(mediaButtonHover[index], buttonHovered ? 1.0f : 0.0f,
                buttonHovered ? 22.0f : 14.0f, seconds);
            float hover = mediaButtonHover[index];
            if (hover > 0.01f) {
                RenderUtil.RECT.draw(matrices,
                    x + HudPolishPolicy.mediaButton(index, width) - controlCell * 0.5F + 1.0F,
                    y + HudPolishPolicy.MEDIA_CONTROL_HIT_TOP + 1.0F,
                    Math.max(1.0F, controlCell - 2.0F),
                    HudPolishPolicy.MEDIA_CONTROL_HIT_HEIGHT - 2.0F, 6,
                    withAlpha(Color.WHITE, opacity * hover * 0.08f));
            }
        }

        float previousOpacity = opacity * controlOpacity(buttonEnabled[0], mediaButtonHover[0]);
        float toggleOpacity = opacity * controlOpacity(buttonEnabled[1], mediaButtonHover[1]);
        float nextOpacity = opacity * controlOpacity(buttonEnabled[2], mediaButtonHover[2]);
        float shuffleOpacity = opacity * controlOpacity(buttonEnabled[3], mediaButtonHover[3]);
        drawWeightedMediaIcon(matrices, MEDIA_PREVIOUS_ICON,
            x + HudPolishPolicy.mediaButton(0, width), y + HudPolishPolicy.MEDIA_CONTROL_Y,
            HudPolishPolicy.mediaIconSize(0),
            withAlpha(TEXT, previousOpacity));
        drawMediaIcon(matrices, MEDIA_PLAY_ICON, x + HudPolishPolicy.mediaButton(1, width),
            y + HudPolishPolicy.MEDIA_CONTROL_Y,
            HudPolishPolicy.mediaIconSize(1),
            withAlpha(TEXT, toggleOpacity * (1.0f - renderedMediaPlaying)));
        drawMediaIcon(matrices, MEDIA_PAUSE_ICON, x + HudPolishPolicy.mediaButton(1, width),
            y + HudPolishPolicy.MEDIA_CONTROL_Y,
            HudPolishPolicy.mediaIconSize(1),
            withAlpha(TEXT, toggleOpacity * renderedMediaPlaying));
        drawWeightedMediaIcon(matrices, MEDIA_NEXT_ICON,
            x + HudPolishPolicy.mediaButton(2, width), y + HudPolishPolicy.MEDIA_CONTROL_Y,
            HudPolishPolicy.mediaIconSize(2),
            withAlpha(TEXT, nextOpacity));
        drawMediaIcon(matrices, MEDIA_SHUFFLE_ICON, x + HudPolishPolicy.mediaButton(3, width),
            y + HudPolishPolicy.MEDIA_CONTROL_Y,
            HudPolishPolicy.mediaIconSize(3),
            withAlpha(blend(MUTED, TEXT, renderedMediaShuffle), shuffleOpacity));
    }

     private static void drawMediaIcon(MatrixStack matrices, Identifier texture, float centerX, float centerY,
                                       float size, Color tint) {
        RenderUtil.TEXTURE_RECT.draw(matrices, centerX - size / 2.0f, centerY - size / 2.0f,
            size, size, 0, tint, 0, 0, 1, 1, texture);
    }

    private static void drawWeightedMediaIcon(MatrixStack matrices, Identifier texture,
                                              float centerX, float centerY, float size, Color tint) {
        Color shoulder = withAlpha(tint, 0.62F);
        drawMediaIcon(matrices, texture, centerX - 0.34F, centerY, size, shoulder);
        drawMediaIcon(matrices, texture, centerX + 0.34F, centerY, size, shoulder);
        drawMediaIcon(matrices, texture, centerX, centerY, size, tint);
    }

    private static float controlOpacity(boolean enabled, float hover) {
        return enabled ? 0.82f + 0.18f * hover : 0.32f;
    }

    private void updateMediaLyricsDemand() {
        SystemMediaService.getInstance().setMediaHudLyricsEnabled(
            state(HudElementType.DYNAMIC_ISLAND).enabled());
    }

    private static String mediaLyric(MediaPlaybackSnapshot snapshot) {
        if (snapshot == null || snapshot.lyrics().lines().isEmpty()) {
            return "";
        }
        return snapshot.lyrics().cue(snapshot.estimatedProgressMs(), snapshot.durationMs()).current();
    }

    private static String mediaTitle(MediaPlaybackSnapshot snapshot) {
        if (snapshot == null || !snapshot.available()) {
            return "Media";
        }
        if (!snapshot.title().isBlank()) {
            return snapshot.title();
        }
        return snapshot.source().isBlank() ? "Now playing" : snapshot.source();
    }

    private static String mediaTrackLine(MediaPlaybackSnapshot snapshot) {
        String artist = snapshot.artist().strip();
        return artist.isEmpty() ? mediaTitle(snapshot) : mediaTitle(snapshot) + " - " + artist;
    }

    private static Color arraylistColor(String label, Color fallback) {
        GuiModule module = moduleFor(HudElementType.ARRAYLIST);
        if (module == null) {
            return fallback;
        }
        return module.settings().stream()
            .filter(GuiSetting.ColorPick.class::isInstance)
            .map(GuiSetting.ColorPick.class::cast)
            .filter(setting -> setting.label().equals(label))
            .findFirst()
            .map(setting -> new Color(setting.rgb()))
            .orElse(fallback);
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

    private record HudBind(String module, String key) {
    }
}
