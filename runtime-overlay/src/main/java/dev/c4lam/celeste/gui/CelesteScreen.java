package dev.c4lam.celeste.gui;

import dev.c4lam.celeste.config.ConfigLibrary;
import dev.c4lam.celeste.config.ConfigProfileManager;
import dev.c4lam.celeste.config.InterfaceConfig;
import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.gui.model.CelestePage;
import dev.c4lam.celeste.gui.model.GuiCatalog;
import dev.c4lam.celeste.gui.model.GuiModule;
import dev.c4lam.celeste.gui.model.GuiSetting;
import dev.c4lam.celeste.hud.HudEditorScreen;
import dev.c4lam.celeste.render.GuiRenderer;
import dev.c4lam.celeste.render.GuiTextures;
import dev.c4lam.celeste.render.RenderUtil;
import dev.c4lam.celeste.render.font.Font;
import dev.c4lam.celeste.render.font.Fonts;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Celeste 1.0 Click GUI. Runtime behavior is attached through ModuleManager;
 * this screen owns presentation and edits the shared module/settings model.
 */
public final class CelesteScreen extends Screen {
    private static final Identifier LOGO = Identifier.of("celeste", "textures/gui/celeste-logo.png");
    private static final Identifier SEARCH_ICON = Identifier.of("celeste", "textures/gui/icons/search.png");
    private static final Identifier SETTINGS_ICON = Identifier.of("celeste", "textures/gui/icons/settings.png");
    private static final Identifier STAR_ICON = Identifier.of("celeste", "textures/gui/icons/star.png");
    private static final Identifier STAR_FILL_ICON = Identifier.of("celeste", "textures/gui/icons/star-fill.png");
    private static final Identifier TRASH_ICON = Identifier.of("celeste", "textures/gui/icons/trash.png");
    private static final Identifier CLOUD_ICON = Identifier.of("celeste", "textures/gui/icons/cloud-config.png");
    private static final Identifier PENCIL_ICON = Identifier.of("celeste", "textures/gui/icons/pencil.png");
    private static final Color STAR_YELLOW = color(255, 210, 86, 255);
    private static final float STAR_SMOOTH_TIME_SECONDS = 0.18f;
    private static final Color TRASH_RED = color(232, 74, 82, 255);
    private static final Color CLOUD_TAN = color(232, 204, 142, 255);
    private static final long TOGGLE_ANIMATION_NANOS = 125_000_000L;
    private static final long EXPANSION_ANIMATION_NANOS = 180_000_000L;
    private static final long NAVIGATION_ANIMATION_NANOS = 210_000_000L;
    private static final long HOVER_ANIMATION_NANOS = 135_000_000L;
    private static final long PAGE_TRANSITION_NANOS = 225_000_000L;
    private static final long PAGE_STAGGER_NANOS = 18_000_000L;
    private static final float SLIDER_SMOOTH_TIME_SECONDS = 0.10f;
    private static final long SEARCH_REVEAL_DELAY_NANOS = 38_000_000L;
    private static final long SEARCH_FADE_NANOS = 105_000_000L;
    private static final float COMPACT_SCALE = 2.0f / 3.0f;
    private static final float REFERENCE_MINECRAFT_GUI_SCALE = 4.0f;

    private static final Color TEXT = color(250, 243, 250, 255);
    private static final Color MUTED = color(184, 169, 184, 255);
    private static final Color DIM = color(135, 119, 135, 255);
    private static final Color ACCENT = color(255, 43, 143, 255);
    private static final Color ACCENT_SOFT = color(191, 33, 112, 175);
    private static final Color PANEL_TOP = color(25, 15, 25, 246);
    private static final Color PANEL_BOTTOM = color(25, 15, 25, 246);
    private static final Color SIDEBAR = color(9, 6, 12, 222);
    private static final Color RAIL_CARD = color(5, 3, 7, 240);
    private static final Color CARD_TOP = color(20, 12, 22, 210);
    private static final Color CARD_BOTTOM = color(8, 5, 10, 232);
    private static final Color CARD = color(41, 27, 40, 170);
    private static final Color CARD_HOVER = color(53, 34, 50, 192);
    private static final Color CARD_OPEN = color(48, 29, 46, 218);
    private static final Color CONTROL = color(19, 13, 22, 205);
    private static final Object SEARCH_HOVER_KEY = new Object();
    private static final Object MENU_KEY_HOVER_KEY = new Object();

    private CelestePage selectedPage = CelestePage.SWORD;
    private GuiModule expandedModule;
    private GuiSetting.Slider draggingSlider;
    private GuiSetting.Range draggingRange;
    private boolean draggingRangeHigh;
    private ConfigHit draggingConfig;
    private GuiSetting.Keybind listeningKeybind;
    private final ColorPickerControl colorPicker = new ColorPickerControl();

    private final List<NavHit> navHits = new ArrayList<>();
    private final List<ModuleHit> moduleHits = new ArrayList<>();
    private final List<SettingHit> settingHits = new ArrayList<>();
    private final List<ConfigHit> configHits = new ArrayList<>();
    private final List<FriendHit> friendHits = new ArrayList<>();
    private final List<SuggestionHit> suggestionHits = new ArrayList<>();
    private final List<ProfileHit> profileHits = new ArrayList<>();
    private final List<ActionHit> actionHits = new ArrayList<>();
    private int friendSuggestionIndex;
    private String configInput = "";
    private boolean configFieldFocused;
    private String configStatus = "";
    private long configStatusUntil;
    private FieldHit configFieldHit;
    private FieldHit menuKeyHit;
    private boolean listeningMenuKey;
    private String friendInput = "";
    private boolean friendFieldFocused;
    private String friendStatus = "";
    private long friendStatusUntil;
    private FieldHit friendFieldHit;
    private ConfigLibrary.Tab configTab = ConfigLibrary.Tab.MY;
    private String inspectConfigId = "";
    private final List<TabHit> configTabHits = new ArrayList<>();
    private final List<IconHit> configIconHits = new ArrayList<>();
    private String pendingTooltip = "";
    private float pendingTooltipX;
    private float pendingTooltipY;
    private final Map<Object, TimedAnimation> toggleAnimations = new IdentityHashMap<>();
    private final Map<Object, SpringAnimation> sliderAnimations = new IdentityHashMap<>();
    private final Map<GuiModule, TimedAnimation> expansionAnimations = new IdentityHashMap<>();
    private final Map<CelestePage, TimedAnimation> navigationAnimations = new IdentityHashMap<>();
    private final Map<Object, TimedAnimation> hoverAnimations = new IdentityHashMap<>();
    private final Map<String, TimedAnimation> namedHovers = new HashMap<>();
    private final Map<String, SpringAnimation> starFills = new HashMap<>();
    private final List<Long> searchRevealTimes = new ArrayList<>();

    private float contentScroll;
    private float targetScroll;
    private float configFade = 1.0f;
    private float configScrollMin;
    private String search = "";
    private boolean searchFocused;
    private long openedAt;
    private long pageChangedAt;
    private float renderedInterfaceScale = InterfaceConfig.DEFAULT_SCALE;
    private float interfaceScaleVelocity;
    private long lastScaleUpdateAt;

    private float panelX;
    private float panelY;
    private float panelWidth;
    private float panelHeight;
    private float sidebarWidth;
    private float contentX;
    private float contentY;
    private float contentWidth;
    private float contentHeight;
    private float uiScale = 1.0f;
    private boolean pointerHot;

    public CelesteScreen() {
        super(Text.literal("Celeste Client"));
    }

    @Override
    protected void init() {
        openedAt = System.nanoTime();
        pageChangedAt = openedAt;
        renderedInterfaceScale = InterfaceConfig.scale();
        interfaceScaleVelocity = 0.0f;
        lastScaleUpdateAt = openedAt;
        layout();
        GuiCursors.hideNative(client);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        updateInterfaceScaleAnimation();
        layout();
        float animation = openAnimation();
        contentScroll += (targetScroll - contentScroll) * Math.min(1.0f, delta * 0.22f + 0.12f);
        if (selectedPage == CelestePage.CONFIGS) {
            boolean atBottom = contentScroll <= configScrollMin + 1.5f;
            float fadeTarget = atBottom ? 0.0f : 1.0f;
            configFade += (fadeTarget - configFade) * Math.min(1.0f, delta * 0.08f + 0.03f);
        } else {
            configFade = 1.0f;
        }

        MatrixStack matrices = RenderUtil.matrices();
        matrices.loadIdentity();
        matrices.scale(uiScale, uiScale, 1.0f);
        GuiRenderer.getInstance().clearScissors();
        RenderUtil.captureBlur();

        int virtualMouseX = Math.round(mouseX / uiScale);
        int virtualMouseY = Math.round(mouseY / uiScale);

        pointerHot = false;
        navHits.clear();
        moduleHits.clear();
        settingHits.clear();
        configHits.clear();
        friendHits.clear();
        suggestionHits.clear();
        profileHits.clear();
        actionHits.clear();
        configTabHits.clear();
        configIconHits.clear();
        configFieldHit = null;
        menuKeyHit = null;
        friendFieldHit = null;
        pendingTooltip = "";

        float animatedY = panelY + (1.0f - animation) * 18.0f;
        Color glassTop = withAlpha(PANEL_TOP, Math.round(PANEL_TOP.getAlpha() * animation));
        Color glassBottom = withAlpha(PANEL_BOTTOM, Math.round(PANEL_BOTTOM.getAlpha() * animation));

        RenderUtil.BLUR_RECT.draw(
            matrices,
            panelX,
            animatedY,
            panelWidth,
            panelHeight,
            new Vector4f(18.0f),
            glassTop,
            glassTop,
            glassBottom,
            glassBottom,
            InterfaceConfig.glassTintMix()
        );
        Color sidebarGlass = withAlpha(SIDEBAR, Math.round(SIDEBAR.getAlpha() * animation));
        RenderUtil.BLUR_RECT.draw(
            matrices,
            panelX + 1,
            animatedY + 1,
            sidebarWidth,
            panelHeight - 2,
            new Vector4f(17, 0, 17, 0),
            sidebarGlass,
            sidebarGlass,
            sidebarGlass,
            sidebarGlass,
            InterfaceConfig.glassTintMix()
        );

        drawSidebar(matrices, virtualMouseX, virtualMouseY, animatedY, animation);
        drawHeader(matrices, virtualMouseX, virtualMouseY, animatedY, animation);

        contentY = animatedY + 72;
        contentHeight = panelHeight - 91;
        GuiRenderer.getInstance().pushScissor(contentX * uiScale, contentY * uiScale, contentWidth * uiScale, contentHeight * uiScale);
        if (selectedPage.feature()) {
            drawModulePage(matrices, virtualMouseX, virtualMouseY, animation);
        } else {
            drawUtilityPage(matrices, virtualMouseX, virtualMouseY, animation);
        }
        GuiRenderer.getInstance().popScissor();
        colorPicker.drawPopup(matrices);
        if (colorPicker.isDragging()) {
            colorPicker.drag(virtualMouseX, virtualMouseY);
        }
        if (selectedPage == CelestePage.CONFIGS) {
            drawContentFade(matrices, animation);
        }
        if (!pendingTooltip.isEmpty()) {
            drawHoverTooltip(matrices, pendingTooltipX, pendingTooltipY, pendingTooltip, animation);
        }

        if (draggingSlider != null) {
            updateDraggedSlider(virtualMouseX);
        }
        if (draggingRange != null) {
            updateDraggedRange(virtualMouseX);
        }
        if (draggingConfig != null) {
            updateDraggedConfig(virtualMouseX);
        }

        boolean grab = pointerHot
            || draggingSlider != null
            || draggingRange != null
            || draggingConfig != null
            || colorPicker.isDragging()
            || overInteractive(virtualMouseX, virtualMouseY);
        GuiCursors.hideNative(client);
        matrices.push();
        matrices.loadIdentity();
        GuiCursors.draw(matrices, mouseX, mouseY, grab);
        matrices.pop();
    }

    private void drawSidebar(MatrixStack matrices, int mouseX, int mouseY, float y, float animation) {
        float logoSize = 48;
        RenderUtil.TEXTURE_RECT.draw(matrices, panelX + 15, y + 14, logoSize, logoSize, 12,
            withAlpha(Color.WHITE, Math.round(255 * animation)), 0, 0, 1, 1, LOGO);

        Font bold = Fonts.bold();
        Font regular = Fonts.regular();
        bold.drawText(matrices, "Celeste Client", panelX + 69, y + 24, 12.5f, withAlpha(TEXT, Math.round(255 * animation)));
        regular.drawText(matrices, "Version 1.0", panelX + 69, y + 42, 8.5f, withAlpha(MUTED, Math.round(255 * animation)));

        float itemX = panelX + 14;
        float itemWidth = sidebarWidth - 28;
        float itemHeight = 29;
        float cursorY = y + 91;

        regular.drawText(matrices, "FEATURES", itemX + 4, cursorY - 14, 7.1f, withAlpha(DIM, Math.round(255 * animation)));
        for (CelestePage page : CelestePage.values()) {
            if (!page.feature()) {
                continue;
            }
            drawNavItem(matrices, page, itemX, cursorY, itemWidth, itemHeight, mouseX, mouseY, animation);
            cursorY += itemHeight + 4;
        }

        cursorY += 16;
        regular.drawText(matrices, "OTHER", itemX + 4, cursorY - 14, 7.1f, withAlpha(DIM, Math.round(255 * animation)));
        for (CelestePage page : CelestePage.values()) {
            if (page.feature()) {
                continue;
            }
            drawNavItem(matrices, page, itemX, cursorY, itemWidth, itemHeight, mouseX, mouseY, animation);
            cursorY += itemHeight + 4;
        }
    }

    private void drawNavItem(
        MatrixStack matrices,
        CelestePage page,
        float x,
        float y,
        float width,
        float height,
        int mouseX,
        int mouseY,
        float animation
    ) {
        boolean selected = page == selectedPage;
        boolean hovered = hovered(mouseX, mouseY, x, y, width, height);
        float hover = hoverAnimation(page, hovered);
        float selection = navigationAnimations
            .computeIfAbsent(page, ignored -> new TimedAnimation())
            .update(selected ? 1.0f : 0.0f, NAVIGATION_ANIMATION_NANOS);
        if (hover > 0.001f && selection < 0.98f) {
            RenderUtil.RECT.draw(matrices, x, y, width, height, 8,
                withAlpha(color(69, 42, 65, 135), Math.round(135 * animation * hover * (1.0f - selection))));
        }
        if (selection > 0.001f) {
            RenderUtil.GRADIENT_RECT.draw(matrices, x, y, width, height, 8,
                withAlpha(color(255, 46, 150, 230), Math.round(230 * animation * selection)),
                withAlpha(color(183, 34, 146, 225), Math.round(225 * animation * selection)),
                withAlpha(color(221, 36, 126, 225), Math.round(225 * animation * selection)),
                withAlpha(color(133, 29, 132, 220), Math.round(220 * animation * selection)));
        }

        float iconX = x + 8;
        float iconY = y + 6;
        float iconSize = 17;
        if (page == CelestePage.MOVEMENT && (hovered || selected)) {
            double phase = System.nanoTime() / 100_000_000.0;
            float step = (float) Math.sin(phase);
            float stretch = 1.0f + (float) Math.sin(phase * 2.0) * 0.035f;
            iconX += step * 0.8f;
            iconY -= Math.abs((float) Math.cos(phase)) * 0.7f;
            iconSize *= stretch;
            iconX += (17 - iconSize) * 0.5f;
            iconY += (17 - iconSize) * 0.5f;
        }
        Color iconColor = blend(MUTED, Color.WHITE, Math.max(selection, hover * 0.35f));
        Color labelColor = blend(TEXT, Color.WHITE, Math.max(selection, hover * 0.22f));
        if (page.icon() != null) {
            RenderUtil.TEXTURE_RECT.draw(matrices, iconX, iconY, iconSize, iconSize, 0,
                withAlpha(iconColor, Math.round((215 + 40 * selection) * animation)), 0, 0, 1, 1, page.icon());
        } else {
            Fonts.bold().drawCenteredText(matrices, page.fallbackGlyph(), iconX + 8.5f, iconY + 1, 10.5f,
                withAlpha(iconColor, Math.round((215 + 40 * selection) * animation)));
        }
        Fonts.semibold().drawText(matrices, page.label(), x + 34, centeredTextY(y, height, 9.4f), 9.4f,
            withAlpha(labelColor, Math.round(255 * animation)));
        navHits.add(new NavHit(page, x, y, width, height));
    }

    private void drawHeader(MatrixStack matrices, int mouseX, int mouseY, float y, float animation) {
        float headerX = panelX + sidebarWidth + 23;
        contentX = headerX;
        contentWidth = panelX + panelWidth - 22 - contentX;

        Font bold = Fonts.bold();
        Font regular = Fonts.regular();
        float pageEntrance = pageEntrance(0);
        float headerOffset = (1.0f - pageEntrance) * 5.0f;
        float headerAnimation = animation * pageEntrance;
        if (selectedPage == CelestePage.CONFIGS) {
            ConfigLibrary library = ConfigLibrary.getInstance();
            library.initialize();
            int total = library.totalVisible(configTab);
            RenderUtil.TEXTURE_RECT.draw(matrices, headerX, y + 22 + headerOffset, 18, 18, 0,
                withAlpha(TEXT, Math.round(255 * headerAnimation)), 0, 0, 1, 1, CelestePage.CONFIGS.icon());
            bold.drawText(matrices, "Configs  •  Total configs  " + total, headerX + 24, y + 23 + headerOffset, 14.5f,
                withAlpha(TEXT, Math.round(255 * headerAnimation)));
            regular.drawText(matrices, selectedPage.description(), headerX + 24, y + 43 + headerOffset, 8.7f,
                withAlpha(MUTED, Math.round(255 * headerAnimation)));
        } else {
            bold.drawText(matrices, selectedPage.label(), headerX, y + 23 + headerOffset, 14.5f,
                withAlpha(TEXT, Math.round(255 * headerAnimation)));
            regular.drawText(matrices, selectedPage.description(), headerX, y + 43 + headerOffset, 8.7f,
                withAlpha(MUTED, Math.round(255 * headerAnimation)));
        }

        if (!selectedPage.feature() && selectedPage != CelestePage.CONFIGS) {
            return;
        }

        float searchWidth = Math.min(176, contentWidth * 0.32f);
        float searchX = panelX + panelWidth - 22 - searchWidth;
        float searchY = y + 23;
        boolean hovered = hovered(mouseX, mouseY, searchX, searchY, searchWidth, 29);
        float focus = hoverAnimation(SEARCH_HOVER_KEY, hovered || searchFocused);
        drawRailCard(matrices, searchX, searchY, searchWidth, 29, 9, focus, animation);
        RenderUtil.TEXTURE_RECT.draw(matrices, searchX + 9 - focus * 0.5f, searchY + 8, 13, 13, 0,
            withAlpha(blend(MUTED, TEXT, focus * 0.45f), Math.round(235 * animation)), 0, 0, 1, 1, SEARCH_ICON);
        float textX = searchX + 28;
        long now = System.nanoTime();
        int visibleLength = visibleSearchLength(now);
        String visibleSearch = search.substring(0, visibleLength);
        float textWidth = regular.getWidth(visibleSearch, 8.5f);
        float textViewportWidth = searchWidth - 35;
        float textOffset = Math.max(0, textWidth - textViewportWidth + 2);
        GuiRenderer.getInstance().pushScissor(
            textX * uiScale,
            (searchY + 4) * uiScale,
            textViewportWidth * uiScale,
            21 * uiScale
        );
        if (!search.isEmpty()) {
            float glyphX = textX - textOffset;
            for (int index = 0; index < visibleLength; index++) {
                String glyph = visibleSearch.substring(index, index + 1);
                float fade = searchCharacterFade(index, now);
                regular.drawText(matrices, glyph, glyphX, centeredTextY(searchY, 29, 8.5f), 8.5f,
                    withAlpha(TEXT, Math.round(255 * animation * fade)));
                glyphX += regular.getWidth(glyph, 8.5f);
            }
        } else if (!searchFocused) {
            regular.drawText(matrices, selectedPage == CelestePage.CONFIGS ? "Search configs" : "Search modules", textX, centeredTextY(searchY, 29, 8.5f), 8.5f,
                withAlpha(DIM, Math.round(255 * animation)));
        }
        if (searchFocused && pulse()) {
            float caretX = textX + textWidth - textOffset + 1;
            RenderUtil.RECT.draw(matrices, caretX, searchY + 7, 1, 15, 0.5f,
                withAlpha(TEXT, Math.round(245 * animation)));
        }
        GuiRenderer.getInstance().popScissor();
    }

    private void drawModulePage(MatrixStack matrices, int mouseX, int mouseY, float animation) {
        List<GuiModule> modules = filteredModules();
        float gap = 10;
        float columnWidth = (contentWidth - gap) / 2;
        float leftY = contentY + contentScroll;
        float rightY = contentY + contentScroll;

        for (int index = 0; index < modules.size(); index++) {
            GuiModule module = modules.get(index);
            boolean left = index % 2 == 0;
            float x = contentX + (left ? 0 : columnWidth + gap);
            float y = left ? leftY : rightY;
            float entrance = pageEntrance(index + 1);
            float cardAnimation = animation * entrance;
            float drawY = y + (1.0f - entrance) * 7.0f;
            boolean open = module == expandedModule;
            float expansion = expansionAnimations
                .computeIfAbsent(module, ignored -> new TimedAnimation())
                .update(open ? 1.0f : 0.0f, EXPANSION_ANIMATION_NANOS);
            float settingsHeight = module.settings().isEmpty() ? 0 : module.settings().size() * 39 + 11;
            float cardHeight = 54 + settingsHeight * expansion;
            boolean hovered = hovered(mouseX, mouseY, x, drawY, columnWidth, 54);
            float hover = hoverAnimation(module, hovered);

            float cardAlpha = Math.round((100 + 15 * hover + 18 * expansion) * cardAnimation);

            Fonts.semibold().drawText(matrices, module.name(), x + 15, drawY + 11.5f, 10.2f,
                withAlpha(TEXT, Math.round(255 * cardAnimation)));
            Fonts.regular().drawText(matrices, trim(module.description(), 43), x + 15, drawY + 30.5f, 7.8f,
                withAlpha(MUTED, Math.round(255 * cardAnimation)));
            drawToggle(matrices, module, x + columnWidth - 43, drawY + 19, module.enabled(), cardAnimation);

            moduleHits.add(new ModuleHit(module, x, drawY, columnWidth, 54));
            if (expansion > 0.001f && settingsHeight > 0) {
                float settingsTop = drawY + 54;
                GuiRenderer.getInstance().pushScissor(
                    (x + 1) * uiScale,
                    settingsTop * uiScale,
                    (columnWidth - 2) * uiScale,
                    settingsHeight * expansion * uiScale
                );
                drawSettings(
                    matrices,
                    module,
                    x + 10,
                    drawY + 57 - (1.0f - expansion) * 7.0f,
                    columnWidth - 20,
                    mouseX,
                    mouseY,
                    cardAnimation * expansion,
                    expansion > 0.92f
                );
                GuiRenderer.getInstance().popScissor();
            }

            if (left) {
                leftY += cardHeight + gap;
            } else {
                rightY += cardHeight + gap;
              }
          }

        float minimumY = Math.min(leftY, rightY);
        float maximumY = Math.max(leftY, rightY);
        float required = Math.max(0, maximumY - (contentY + contentScroll) - contentHeight + 8);
        targetScroll = clamp(targetScroll, -required, 0);

        if (modules.isEmpty()) {
            float entrance = pageEntrance(1);
            Fonts.semibold().drawCenteredText(matrices, "No matching modules", contentX + contentWidth / 2,
                contentY + 66 + (1.0f - entrance) * 7.0f, 11,
                withAlpha(MUTED, Math.round(255 * animation * entrance)));
        }
    }

    private void drawSettings(
        MatrixStack matrices,
        GuiModule module,
        float x,
        float y,
        float width,
        int mouseX,
        int mouseY,
        float animation,
        boolean interactive
    ) {
        int settingCount = module.settings().size();
        if (settingCount == 0) {
            return;
        }

        float settingsCardHeight = settingCount * 39.0f - 5.0f;
        RenderUtil.BLUR_RECT.draw(matrices, x, y, width, settingsCardHeight, 7,
            withAlpha(SIDEBAR, Math.round(SIDEBAR.getAlpha() * animation)), InterfaceConfig.glassTintMix());

        int settingIndex = 0;
        for (GuiSetting setting : module.settings()) {
            boolean hovered = hovered(mouseX, mouseY, x, y, width, 34);
            float hover = hoverAnimation(setting, hovered && interactive);
            if (hover > 0.001f) {
                RenderUtil.RECT.draw(matrices, x + 2, y + 2, width - 4, 30, 5,
                    withAlpha(color(45, 28, 45, 155), Math.round(76 * hover * animation)));
            }
            if (setting instanceof GuiSetting.Action action) {
                boolean buttonHovered = interactive && hovered(mouseX, mouseY, x + 9, y + 3, width - 18, 18);
                RenderUtil.RECT.draw(matrices, x + 9, y + 3, width - 18, 18, 5,
                    withAlpha(buttonHovered ? ACCENT : ACCENT_SOFT, Math.round(230 * animation)));
                Fonts.semibold().drawCenteredText(matrices, action.label(), x + width / 2,
                    centeredTextY(y + 3, 18, 7.6f), 7.6f, withAlpha(TEXT, Math.round(255 * animation)));
                Fonts.regular().drawText(matrices, trim(action.description(), 34), x + 9, y + 24, 6.5f,
                    withAlpha(DIM, Math.round(255 * animation)));
            } else {
                Fonts.regular().drawText(matrices, setting.label(), x + 9, y + 7.4f, 8.2f,
                    withAlpha(TEXT, Math.round(255 * animation)));
                Fonts.regular().drawText(matrices, trim(setting.description(), 34), x + 9, y + 20.6f, 6.5f,
                    withAlpha(DIM, Math.round(255 * animation)));
            }

            if (setting instanceof GuiSetting.Toggle toggle) {
                drawToggle(matrices, toggle, x + width - 37, y + 9, toggle.value(), animation);
            } else if (setting instanceof GuiSetting.Slider slider) {
                float sliderX = x + width - 93;
                float sliderY = y + 22;
                float sliderWidth = 76;
                float visualProgress = sliderAnimations
                    .computeIfAbsent(slider, ignored -> new SpringAnimation())
                    .update((float) slider.progress(), SLIDER_SMOOTH_TIME_SECONDS);
                RenderUtil.RECT.draw(matrices, sliderX, sliderY, sliderWidth, 3, 1.5f, withAlpha(color(86, 59, 82, 230), Math.round(230 * animation)));
                RenderUtil.RECT.draw(matrices, sliderX, sliderY, sliderWidth * visualProgress, 3, 1.5f, withAlpha(ACCENT, Math.round(255 * animation)));
                String value = sliderValue(slider.value());
                Fonts.semibold().drawCenteredText(matrices, value, sliderX + sliderWidth / 2, y + 7, 7.2f,
                    withAlpha(TEXT, Math.round(255 * animation)));
            } else if (setting instanceof GuiSetting.Range range) {
                float sliderX = x + width - 93;
                float sliderY = y + 22;
                float sliderWidth = 76;
                float low = (float) range.lowProgress();
                float high = (float) range.highProgress();
                RenderUtil.RECT.draw(matrices, sliderX, sliderY, sliderWidth, 3, 1.5f, withAlpha(color(86, 59, 82, 230), Math.round(230 * animation)));
                RenderUtil.RECT.draw(matrices, sliderX + sliderWidth * low, sliderY, sliderWidth * Math.max(0.02f, high - low), 3, 1.5f,
                    withAlpha(ACCENT, Math.round(255 * animation)));
                float knob = 8;
                RenderUtil.RECT.draw(matrices, sliderX + sliderWidth * low - knob / 2.0f, sliderY - 2.5f, knob, knob, knob / 2.0f,
                    withAlpha(Color.WHITE, Math.round(250 * animation)));
                RenderUtil.RECT.draw(matrices, sliderX + sliderWidth * high - knob / 2.0f, sliderY - 2.5f, knob, knob, knob / 2.0f,
                    withAlpha(Color.WHITE, Math.round(250 * animation)));
                String value = sliderValue(range.low()) + " - " + sliderValue(range.high());
                Fonts.semibold().drawCenteredText(matrices, value, sliderX + sliderWidth / 2, y + 7, 7.2f,
                    withAlpha(TEXT, Math.round(255 * animation)));
            } else if (setting instanceof GuiSetting.Mode mode) {
                String value = mode.value();
                float valueWidth = Math.max(46, Fonts.semibold().getWidth(value, 7.2f) + 17);
                float valueX = x + width - valueWidth - 9;
                RenderUtil.RECT.draw(matrices, valueX, y + 8, valueWidth, 18, 5, withAlpha(CONTROL, Math.round(205 * animation)));
                Fonts.semibold().drawCenteredText(matrices, value, valueX + valueWidth / 2,
                    centeredTextY(y + 8, 18, 7.2f), 7.2f, withAlpha(TEXT, Math.round(255 * animation)));
            } else if (setting instanceof GuiSetting.ItemPick itemPick) {
                String value = itemPickName(itemPick);
                float valueWidth = Math.max(46, Fonts.semibold().getWidth(value, 7.2f) + 17);
                float valueX = x + width - valueWidth - 9;
                RenderUtil.RECT.draw(matrices, valueX, y + 8, valueWidth, 18, 5, withAlpha(CONTROL, Math.round(205 * animation)));
                Fonts.semibold().drawCenteredText(matrices, value, valueX + valueWidth / 2,
                    centeredTextY(y + 8, 18, 7.2f), 7.2f, withAlpha(TEXT, Math.round(255 * animation)));
            } else if (setting instanceof GuiSetting.Keybind keybind) {
                String value = listeningKeybind == keybind ? "Press key" : keyName(keybind.keyCode());
                float valueWidth = Math.max(51, Fonts.semibold().getWidth(value, 7.2f) + 17);
                float valueX = x + width - valueWidth - 9;
                RenderUtil.RECT.draw(matrices, valueX, y + 8, valueWidth, 18, 5,
                    withAlpha(listeningKeybind == keybind ? ACCENT_SOFT : CONTROL, Math.round(205 * animation)));
                Fonts.semibold().drawCenteredText(matrices, value, valueX + valueWidth / 2,
                    centeredTextY(y + 8, 18, 7.2f), 7.2f, withAlpha(TEXT, Math.round(255 * animation)));
            } else if (setting instanceof GuiSetting.ColorPick colorPick) {
                colorPicker.drawSwatch(matrices, colorPick, x + width - 37, y + 9, animation);
            }

            if (interactive) {
                if (setting instanceof GuiSetting.Action) {
                    settingHits.add(new SettingHit(setting, x + 9, y + 3, width - 18, 18));
                } else if (setting instanceof GuiSetting.Slider || setting instanceof GuiSetting.Range) {
                    settingHits.add(new SettingHit(setting, x + width - 93, y + 14, 76, 17));
                } else if (setting instanceof GuiSetting.ColorPick) {
                    settingHits.add(new SettingHit(setting, x + width - 37, y + 9, 16, 16));
                } else {
                    settingHits.add(new SettingHit(setting, x, y, width, 34));
                }
            }

            if (settingIndex < settingCount - 1) {
                RenderUtil.RECT.draw(matrices, x + 9, y + 36.5f, width - 18, 0.75f, 0.375f,
                    withAlpha(color(92, 67, 88, 255), Math.round(42 * animation)));
            }

            settingIndex++;
            y += 39;
        }
    }

    private void drawUtilityPage(MatrixStack matrices, int mouseX, int mouseY, float animation) {
        switch (selectedPage) {
            case FRIENDS -> drawFriendsPage(matrices, mouseX, mouseY, animation);
            case CONFIGS -> drawConfigsPage(matrices, mouseX, mouseY, animation);
            case HUD -> drawPlaceholder(matrices, "H", "HUD editor", "Arrange interface widgets on screen.", "Open editor", animation);
            case SETTINGS -> drawSettingsPage(matrices, mouseX, mouseY, animation);
            default -> {
            }
        }
    }

    private void drawFriendsPage(MatrixStack matrices, int mouseX, int mouseY, float animation) {
        List<FriendManager.Friend> friends = FriendManager.getInstance().getFriends();
        float x = contentX;
        float width = contentWidth;
        float cursor = contentY + contentScroll + 8;
        float fieldY = cursor;
        boolean fieldHovered = hovered(mouseX, mouseY, x, fieldY, width, 32);
        drawRailCard(matrices, x, fieldY, width, 32, 8, fieldHovered || friendFieldFocused ? 1.0f : 0.0f, animation);
        String placeholder = friendFieldFocused ? "" : "Type a username";
        String fieldText = friendInput.isEmpty() ? placeholder : friendInput;
        if (friendFieldFocused && pulse()) {
            fieldText += "|";
        }
        Fonts.regular().drawText(matrices, trim(fieldText, 48), x + 12, centeredTextY(fieldY, 32, 7.4f), 7.4f,
            withAlpha(friendInput.isEmpty() && !friendFieldFocused ? DIM : TEXT, Math.round(255 * animation)));
        friendFieldHit = new FieldHit(x, fieldY, width, 32);
        List<PlayerSuggestion> suggestions = friendFieldFocused ? friendSuggestions(friendInput) : List.of();
        boolean hideFriends = !suggestions.isEmpty();
        if (!friendStatus.isEmpty() && System.nanoTime() < friendStatusUntil && !hideFriends) {
            Fonts.regular().drawText(matrices, friendStatus, x, fieldY + 38, 6.2f,
                withAlpha(ACCENT, Math.round(255 * animation)));
            cursor += 56;
        } else {
            cursor += 44;
        }

        if (hideFriends) {
            drawFriendSuggestions(matrices, mouseX, mouseY, x, fieldY + 36, width, animation, suggestions);
        } else if (friends.isEmpty()) {
            float entrance = pageEntrance(1);
            Fonts.semibold().drawCenteredText(matrices, "No friends yet", x + width / 2,
                cursor + 28 + (1.0f - entrance) * 8.0f, 11.0f,
                withAlpha(TEXT, Math.round(255 * animation * entrance)));
            Fonts.regular().drawCenteredText(matrices, "Type a name to add someone nearby, or middle-click a player.",
                x + width / 2, cursor + 50 + (1.0f - entrance) * 8.0f, 7.2f,
                withAlpha(MUTED, Math.round(255 * animation * entrance)));
        } else {
            float gap = 9;
            float cardWidth = (width - gap * 2) / 3.0f;
            float cardHeight = 52;
            for (int index = 0; index < friends.size(); index++) {
                FriendManager.Friend friend = friends.get(index);
                int column = index % 3;
                int row = index / 3;
                float entrance = pageEntrance(index);
                float cardX = x + column * (cardWidth + gap);
                float cardY = cursor + row * (cardHeight + gap) + (1.0f - entrance) * 7.0f;
                drawPanelCard(matrices, cardX, cardY, cardWidth, cardHeight, 10, 0.0f, animation * entrance);
                float avatarX = cardX + 9;
                float avatarY = cardY + 11;
                drawPlayerHead(matrices, skinFor(friend.uuid()), friend.lastKnownName(), avatarX, avatarY, 30, animation * entrance);
                Fonts.semibold().drawText(matrices, trim(friend.lastKnownName(), 12), cardX + 46, cardY + 12, 7.6f,
                    withAlpha(TEXT, Math.round(255 * animation * entrance)));
                Fonts.regular().drawText(matrices, "Friend", cardX + 46, cardY + 30, 5.8f,
                    withAlpha(MUTED, Math.round(255 * animation * entrance)));
                float deleteX = cardX + cardWidth - 22;
                float deleteY = cardY + 6;
                boolean deleteHovered = hovered(mouseX, mouseY, deleteX, deleteY, 16, 16);
                Fonts.semibold().drawCenteredText(matrices, "×", deleteX + 8, centeredTextY(deleteY, 16, 9.0f), 9.0f,
                    withAlpha(deleteHovered ? MUTED : color(135, 119, 135, 70), Math.round(255 * animation * entrance)));
                if (deleteHovered) {
                    setPendingTooltip(mouseX, mouseY, "Remove this friend.");
                }
                friendHits.add(new FriendHit(friend, cardX, cardY, cardWidth, cardHeight, deleteX, deleteY, 16, 16));
            }
            int rows = (friends.size() + 2) / 3;
            cursor += rows * cardHeight + Math.max(0, rows - 1) * gap + 12;
        }

        float totalHeight = cursor - (contentY + contentScroll) + 12;
        float minimum = Math.min(0, contentHeight - totalHeight);
        targetScroll = Math.max(minimum, Math.min(0, targetScroll));
        configScrollMin = minimum;
    }

    private void drawFriendSuggestions(
        MatrixStack matrices,
        int mouseX,
        int mouseY,
        float x,
        float y,
        float width,
        float animation,
        List<PlayerSuggestion> suggestions
    ) {
        if (suggestions.isEmpty()) {
            return;
        }
        friendSuggestionIndex = Math.max(0, Math.min(friendSuggestionIndex, suggestions.size() - 1));
        float rowHeight = 30;
        float height = 8 + suggestions.size() * rowHeight;
        drawRailCard(matrices, x, y, width, height, 10, 0.0f, animation);
        for (int index = 0; index < suggestions.size(); index++) {
            PlayerSuggestion suggestion = suggestions.get(index);
            float rowY = y + 4 + index * rowHeight;
            boolean hovered = hovered(mouseX, mouseY, x + 4, rowY, width - 8, rowHeight);
            if (hovered) {
                friendSuggestionIndex = index;
            }
            boolean selected = index == friendSuggestionIndex;
            if (selected) {
                RenderUtil.RECT.draw(matrices, x + 4, rowY, width - 8, rowHeight - 2, 7,
                    withAlpha(color(255, 255, 255, 14), Math.round(14 * animation)));
            }
            drawPlayerHead(matrices, suggestion.skin(), suggestion.name(), x + 10, rowY + 2, 24, animation);
            Fonts.semibold().drawText(matrices, trim(suggestion.name(), 22), x + 42, centeredTextY(rowY, rowHeight - 2, 8.0f), 8.0f,
                withAlpha(TEXT, Math.round(255 * animation)));
            suggestionHits.add(new SuggestionHit(suggestion, x + 4, rowY, width - 8, rowHeight));
        }
    }

    private void drawRailCard(
        MatrixStack matrices,
        float x,
        float y,
        float width,
        float height,
        float radius,
        float hover,
        float animation
    ) {
        Color tint = blend(RAIL_CARD, color(12, 8, 16, RAIL_CARD.getAlpha()), hover * 0.35f);
        RenderUtil.BLUR_RECT.draw(
            matrices,
            x,
            y,
            width,
            height,
            radius,
            withAlpha(tint, Math.round(tint.getAlpha() * animation)),
            InterfaceConfig.glassTintMix()
        );
    }

    private void drawPanelCard(
        MatrixStack matrices,
        float x,
        float y,
        float width,
        float height,
        float radius,
        float hover,
        float animation
    ) {
        Color top = blend(CARD_TOP, color(32, 20, 34, 220), hover * 0.45f);
        Color bottom = blend(CARD_BOTTOM, color(14, 9, 16, 240), hover * 0.35f);
        RenderUtil.BLUR_RECT.draw(
            matrices,
            x,
            y,
            width,
            height,
            new Vector4f(radius),
            withAlpha(top, Math.round(top.getAlpha() * animation)),
            withAlpha(top, Math.round(top.getAlpha() * animation)),
            withAlpha(bottom, Math.round(bottom.getAlpha() * animation)),
            withAlpha(bottom, Math.round(bottom.getAlpha() * animation)),
            InterfaceConfig.glassTintMix()
        );
    }

    private void drawPlayerHead(
        MatrixStack matrices,
        Identifier skin,
        String name,
        float x,
        float y,
        float size,
        float animation
    ) {
        float radius = Math.max(5.0f, size * 0.28f);
        RenderUtil.RECT.draw(matrices, x, y, size, size, radius,
            color(255, 78, 162, Math.round(210 * animation)));
        if (skin != null) {
            float inset = Math.max(1.0f, size * 0.06f);
            Vector4f corners = new Vector4f(Math.max(3.0f, radius - 1.0f));
            RenderUtil.TEXTURE_RECT.draw(
                matrices, x + inset, y + inset, size - inset * 2, size - inset * 2, corners,
                withAlpha(Color.WHITE, Math.round(255 * animation)),
                8.0f / 64.0f, 8.0f / 64.0f, 8.0f / 64.0f, 8.0f / 64.0f,
                GuiTextures.view(skin), GuiTextures.nearest(), true
            );
            RenderUtil.TEXTURE_RECT.draw(
                matrices, x + inset, y + inset, size - inset * 2, size - inset * 2, corners,
                withAlpha(Color.WHITE, Math.round(255 * animation)),
                40.0f / 64.0f, 8.0f / 64.0f, 8.0f / 64.0f, 8.0f / 64.0f,
                GuiTextures.view(skin), GuiTextures.nearest(), true
            );
            return;
        }
        String initial = name == null || name.isBlank() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
        Fonts.bold().drawCenteredText(matrices, initial, x + size / 2.0f,
            centeredTextY(y, size, size * 0.38f), size * 0.38f,
            withAlpha(TEXT, Math.round(255 * animation)));
    }

    private Identifier skinFor(UUID uuid) {
        if (client == null || uuid == null) {
            return null;
        }
        if (client.world != null) {
            for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
                if (uuid.equals(player.getUuid())) {
                    return player.getSkin().body().texturePath();
                }
            }
        }
        if (client.getNetworkHandler() != null) {
            PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(uuid);
            if (entry != null) {
                return entry.getSkinTextures().body().texturePath();
            }
        }
        return null;
    }

    private List<PlayerSuggestion> friendSuggestions(String query) {
        List<PlayerSuggestion> suggestions = new ArrayList<>();
        if (client == null || client.player == null) {
            return suggestions;
        }
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        FriendManager friends = FriendManager.getInstance();
        UUID self = client.player.getUuid();
        Set<UUID> seen = new HashSet<>();

        if (client.world != null) {
            for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
                UUID uuid = player.getUuid();
                if (uuid.equals(self) || friends.isFriend(uuid) || !seen.add(uuid)) {
                    continue;
                }
                String name = player.getName().getString();
                if (!needle.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(needle)) {
                    continue;
                }
                suggestions.add(new PlayerSuggestion(uuid, name, player.getSkin().body().texturePath()));
            }
        }
        if (!needle.isEmpty() && client.getNetworkHandler() != null) {
            for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
                if (entry.getProfile() == null) {
                    continue;
                }
                UUID uuid = entry.getProfile().id();
                String name = entry.getProfile().name();
                if (uuid == null || name == null || uuid.equals(self) || friends.isFriend(uuid) || !seen.add(uuid)) {
                    continue;
                }
                if (!name.toLowerCase(Locale.ROOT).contains(needle)) {
                    continue;
                }
                suggestions.add(new PlayerSuggestion(uuid, name, entry.getSkinTextures().body().texturePath()));
            }
        }
        suggestions.sort(Comparator.comparing(PlayerSuggestion::name, String.CASE_INSENSITIVE_ORDER));
        if (suggestions.size() > 6) {
            return List.copyOf(suggestions.subList(0, 6));
        }
        return suggestions;
    }

    private void addSuggestedFriend(PlayerSuggestion suggestion) {
        boolean added = FriendManager.getInstance().addFriend(suggestion.uuid(), suggestion.name());
        friendStatus = added ? "Added " + suggestion.name() : suggestion.name() + " is already a friend";
        friendStatusUntil = System.nanoTime() + 4_000_000_000L;
        if (added) {
            friendInput = "";
            friendSuggestionIndex = 0;
        }
    }

    private void drawConfigsPage(MatrixStack matrices, int mouseX, int mouseY, float animation) {
        ConfigLibrary library = ConfigLibrary.getInstance();
        library.initialize();
        float x = contentX;
        float width = contentWidth;
        float cursor = contentY + contentScroll + 6;
        int card = 0;

        float tabEntrance = pageEntrance(card++);
        float tabY = cursor + (1.0f - tabEntrance) * 5.0f;
        float tabHeight = 22;
        String[] labels = {
            ConfigLibrary.Tab.MY.label(),
            ConfigLibrary.Tab.FAVOURITE.label(),
            ConfigLibrary.Tab.PUBLIC.label()
        };
        ConfigLibrary.Tab[] tabs = {
            ConfigLibrary.Tab.MY, ConfigLibrary.Tab.FAVOURITE, ConfigLibrary.Tab.PUBLIC
        };
        float tabX = x;
        for (int index = 0; index < tabs.length; index++) {
            boolean active = configTab == tabs[index];
            float tabWidth = Fonts.semibold().getWidth(labels[index], 8.2f) + 14;
            Fonts.semibold().drawText(matrices, labels[index], tabX, centeredTextY(tabY, tabHeight, 8.2f), 8.2f,
                withAlpha(active ? TEXT : DIM, Math.round(255 * animation * tabEntrance)));
            if (active) {
                RenderUtil.RECT.draw(matrices, tabX, tabY + tabHeight - 2, tabWidth - 8, 1.4f, 0.7f,
                    withAlpha(TEXT, Math.round(230 * animation * tabEntrance)));
            }
            configTabHits.add(new TabHit(tabs[index], tabX, tabY, tabWidth, tabHeight));
            tabX += tabWidth + 10;
        }
        cursor += 28;

        if (configTab == ConfigLibrary.Tab.MY) {
            float fieldEntrance = pageEntrance(card++);
            float fieldY = cursor + (1.0f - fieldEntrance) * 6.0f;
            float fieldOpacity = animation * fieldEntrance;
            ConfigAction[] actions = {
                new ConfigAction("Create", "Create", "Create a blank config"),
                new ConfigAction("Save", "Save", "Save the current setup"),
                new ConfigAction("Import", "Import", "Import a PNG path")
            };
            float fieldWidth = width - (actions.length * 42 + 8);
            boolean fieldHovered = hovered(mouseX, mouseY, x, fieldY, fieldWidth, 28);
            drawRailCard(matrices, x, fieldY, fieldWidth, 28, 8,
                fieldHovered || configFieldFocused ? 1.0f : 0.0f, fieldOpacity);
            String fieldText = configInput.isEmpty()
                ? (configFieldFocused ? (pulse() ? "|" : "") : "Name, path, or folder")
                : configInput + (configFieldFocused && pulse() ? "|" : "");
            Fonts.regular().drawText(matrices, trim(fieldText, 42), x + 10, centeredTextY(fieldY, 28, 7.2f), 7.2f,
                withAlpha(configInput.isEmpty() && !configFieldFocused ? DIM : TEXT, Math.round(255 * fieldOpacity)));
            configFieldHit = new FieldHit(x, fieldY, fieldWidth, 28);

            float buttonX = x + fieldWidth + 8;
            for (ConfigAction action : actions) {
                boolean hovered = hovered(mouseX, mouseY, buttonX, fieldY, 38, 28);
                drawRailCard(matrices, buttonX, fieldY, 38, 28, 7, hovered ? 1.0f : 0.0f, fieldOpacity);
                Fonts.semibold().drawCenteredText(matrices, action.label, buttonX + 19,
                    centeredTextY(fieldY, 28, 5.8f), 5.8f, withAlpha(TEXT, Math.round(255 * fieldOpacity)));
                actionHits.add(new ActionHit(action.id, action.help, false, buttonX, fieldY, 38, 28));
                if (hovered) {
                    setPendingTooltip(mouseX, mouseY, action.help);
                }
                buttonX += 42;
            }
            cursor += 36;
        }

        String status = !configStatus.isEmpty() && System.nanoTime() < configStatusUntil
            ? configStatus
            : (configTab == ConfigLibrary.Tab.MY
                ? "Using  " + ConfigProfileManager.getInstance().active()
                : configTab.label());
        Fonts.regular().drawText(matrices, status, x, cursor + (1.0f - pageEntrance(card)) * 3.0f, 6.4f,
            withAlpha(!configStatus.isEmpty() && System.nanoTime() < configStatusUntil ? ACCENT : DIM,
                Math.round(255 * animation)));
        cursor += 18;

        List<ConfigLibrary.Card> cards = library.list(configTab, search);
        if (cards.isEmpty()) {
            float entrance = pageEntrance(card);
            String empty = configTab == ConfigLibrary.Tab.MY
                ? "No configs yet. Name one, then Save."
                : configTab == ConfigLibrary.Tab.FAVOURITE
                    ? "Star a config to keep it here."
                    : library.cloudBusy()
                        ? "Loading cloud configs..."
                        : library.publicError().isEmpty()
                            ? "No public configs yet. Publish one from My Configs."
                            : "Cloud unavailable: " + trim(library.publicError(), 46);
            Fonts.regular().drawText(matrices, empty, x, cursor + (1.0f - entrance) * 4.0f, 7.2f,
                withAlpha(MUTED, Math.round(255 * animation * entrance)));
            cursor += 28;
        } else {
            float gap = 10;
            float cardWidth = (width - gap) / 2.0f;
            float cardHeight = 74;
            for (int index = 0; index < cards.size(); index++) {
                ConfigLibrary.Card entry = cards.get(index);
                int column = index % 2;
                int row = index / 2;
                float cardX = x + column * (cardWidth + gap);
                float cardY = cursor + row * (cardHeight + gap) + (1.0f - pageEntrance(card + index)) * 6.0f;
                float opacity = animation * pageEntrance(card + index);
                boolean active = entry.origin() == ConfigLibrary.Tab.MY
                    && entry.name().equals(ConfigProfileManager.getInstance().active());
                boolean hovered = hovered(mouseX, mouseY, cardX, cardY, cardWidth, cardHeight);
                boolean inspect = entry.id().equals(inspectConfigId);
                drawPanelCard(matrices, cardX, cardY, cardWidth, cardHeight, 11,
                    inspect || active ? 0.7f : hovered ? 0.45f : 0.0f, opacity);

                float thumb = 42;
                drawRailCard(matrices, cardX + 10, cardY + 16, thumb, thumb, 9, 0.0f, opacity);
                Fonts.bold().drawCenteredText(matrices, entry.initial(), cardX + 10 + thumb / 2,
                    centeredTextY(cardY + 16, thumb, 13.0f), 13.0f,
                    withAlpha(TEXT, Math.round(255 * opacity)));

                float textX = cardX + 62;
                Fonts.semibold().drawText(matrices, trim(entry.name(), 18), textX, cardY + 12, 8.4f,
                    withAlpha(TEXT, Math.round(255 * opacity)));
                Fonts.regular().drawText(matrices, entry.dateLabel(), textX, cardY + 29, 6.3f,
                    withAlpha(MUTED, Math.round(255 * opacity)));

                float iconY = cardY + 47;
                float icon = 18;
                float iconX = textX;
                boolean favourite = library.isFavourite(entry.id());
                boolean starHovered = hovered(mouseX, mouseY, iconX, iconY, icon, icon);
                drawStarIcon(matrices, entry.id(), favourite, starHovered, iconX, iconY, icon, opacity);
                configIconHits.add(new IconHit("star", entry.id(), iconX, iconY, icon, icon));
                if (starHovered) {
                    setPendingTooltip(mouseX, mouseY, favourite ? "Remove favourite" : "Favourite");
                }
                iconX += 24;
                boolean gearHovered = hovered(mouseX, mouseY, iconX, iconY, icon, icon);
                drawTintedIcon(matrices, SETTINGS_ICON, iconX, iconY, icon,
                    blend(MUTED, Color.WHITE, namedHover("gear-" + entry.id(), gearHovered) * 0.35f), opacity);
                configIconHits.add(new IconHit("gear", entry.id(), iconX, iconY, icon, icon));
                if (gearHovered) {
                    setPendingTooltip(mouseX, mouseY, "Modules in this config");
                }
                iconX += 24;
                if (entry.origin() == ConfigLibrary.Tab.MY) {
                    boolean publishHovered = hovered(mouseX, mouseY, iconX, iconY, icon, icon);
                    drawTintedIcon(matrices, PENCIL_ICON, iconX, iconY, icon,
                        blend(MUTED, ACCENT, namedHover("publish-" + entry.id(), publishHovered)), opacity);
                    configIconHits.add(new IconHit("publish", entry.id(), iconX, iconY, icon, icon));
                    if (publishHovered) {
                        setPendingTooltip(mouseX, mouseY, "Publish or update this cloud config");
                    }
                    iconX += 24;
                } else {
                    boolean cloudHovered = hovered(mouseX, mouseY, iconX, iconY, icon, icon);
                    drawTintedIcon(matrices, CLOUD_ICON, iconX, iconY, icon,
                        blend(MUTED, CLOUD_TAN, namedHover("cloud-" + entry.id(), cloudHovered)), opacity);
                    configIconHits.add(new IconHit("download", entry.id(), iconX, iconY, icon, icon));
                    if (cloudHovered) {
                        setPendingTooltip(mouseX, mouseY, "Copy into My Configs");
                    }
                    iconX += 24;
                }
                boolean trashHovered = hovered(mouseX, mouseY, iconX, iconY, icon, icon);
                drawTintedIcon(matrices, TRASH_ICON, iconX, iconY, icon,
                    blend(MUTED, TRASH_RED, namedHover("trash-" + entry.id(), trashHovered)), opacity);
                configIconHits.add(new IconHit("trash", entry.id(), iconX, iconY, icon, icon));
                if (trashHovered) {
                    String trash = entry.origin() == ConfigLibrary.Tab.MY && configTab != ConfigLibrary.Tab.FAVOURITE
                        ? "Delete this config"
                        : configTab == ConfigLibrary.Tab.FAVOURITE
                            ? "Remove favourite"
                            : "Hide from Public";
                    setPendingTooltip(mouseX, mouseY, trash);
                }

                profileHits.add(new ProfileHit(entry.id(), cardX, cardY, cardWidth, cardHeight, 0, 0, 0, 0));
            }
            int rows = (cards.size() + 1) / 2;
            cursor += rows * cardHeight + Math.max(0, rows - 1) * gap + 12;
        }

        if (!inspectConfigId.isEmpty()) {
            ConfigLibrary.Card inspect = cards.stream()
                .filter(entry -> entry.id().equals(inspectConfigId))
                .findFirst().orElse(null);
            if (inspect != null) {
                List<String> modules = library.enabledModules(inspect);
                float panelWidth = Math.min(220, width * 0.46f);
                float panelHeight = 28 + Math.max(1, modules.size()) * 13 + 12;
                float panelX = x + width - panelWidth;
                float panelY = contentY + 8;
                drawRailCard(matrices, panelX, panelY, panelWidth, panelHeight, 10, 0.0f, animation);
                Fonts.semibold().drawText(matrices, "Enabled", panelX + 10, panelY + 8, 7.4f,
                    withAlpha(TEXT, Math.round(255 * animation)));
                if (modules.isEmpty()) {
                    Fonts.regular().drawText(matrices, "Nothing enabled", panelX + 10, panelY + 26, 6.4f,
                        withAlpha(DIM, Math.round(255 * animation)));
                } else {
                    for (int index = 0; index < modules.size(); index++) {
                        Fonts.regular().drawText(matrices, modules.get(index), panelX + 10, panelY + 26 + index * 13, 6.4f,
                            withAlpha(MUTED, Math.round(255 * animation)));
                    }
                }
            }
        }

        float totalHeight = cursor - (contentY + contentScroll) + 18;
        float minimum = Math.min(0, contentHeight - totalHeight);
        targetScroll = Math.max(minimum, Math.min(0, targetScroll));
        configScrollMin = minimum;
    }

    private void drawStarIcon(
        MatrixStack matrices,
        String id,
        boolean favourite,
        boolean hovered,
        float x,
        float y,
        float size,
        float opacity
    ) {
        float fill = starFills.computeIfAbsent(id, ignored -> new SpringAnimation())
            .update(favourite ? 1.0f : 0.0f, STAR_SMOOTH_TIME_SECONDS);
        Color color = blend(MUTED, STAR_YELLOW, fill);
        if (fill < 0.995f) {
            drawTintedIcon(matrices, STAR_ICON, x, y, size, color, opacity);
        }
        if (fill > 0.004f) {
            drawTintedIcon(matrices, STAR_FILL_ICON, x, y, size, withAlpha(color, Math.round(fill * 255)), opacity);
        }
    }

    private void drawTintedIcon(
        MatrixStack matrices,
        Identifier texture,
        float x,
        float y,
        float size,
        Color color,
        float opacity
    ) {
        RenderUtil.TEXTURE_RECT.draw(
            matrices,
            x,
            y,
            size,
            size,
            0,
            withAlpha(color, Math.round(color.getAlpha() * opacity)),
            0,
            0,
            1,
            1,
            texture
        );
    }

    private float namedHover(String key, boolean hovered) {
        return namedHovers.computeIfAbsent(key, ignored -> new TimedAnimation())
            .update(hovered ? 1.0f : 0.0f, HOVER_ANIMATION_NANOS);
    }

    private void drawContentFade(MatrixStack matrices, float animation) {
        float height = 28;
        Color clear = withAlpha(PANEL_BOTTOM, 0);
        Color solid = withAlpha(PANEL_BOTTOM, Math.round(PANEL_BOTTOM.getAlpha() * 0.55f * animation * Math.max(0.0f, Math.min(1.0f, configFade))));
        RenderUtil.GRADIENT_RECT.draw(
            matrices,
            contentX - 2,
            contentY + contentHeight - height,
            contentWidth + 4,
            height,
            0,
            clear, clear, solid, solid
        );
    }

    private void drawHoverTooltip(MatrixStack matrices, float x, float y, String text, float animation) {
        float paddingX = 8;
        float paddingY = 6;
        float font = 5.9f;
        float maxWidth = Math.min(220, contentWidth - 16);
        List<String> lines = wrapTooltip(text, font, maxWidth);
        float width = paddingX * 2;
        for (String line : lines) {
            width = Math.max(width, Fonts.regular().getWidth(line, font) + paddingX * 2);
        }
        float height = paddingY * 2 + lines.size() * 8.2f;
        float drawX = clamp(x, panelX + 8, panelX + panelWidth - width - 8);
        float drawY = clamp(y, panelY + 8, panelY + panelHeight - height - 8);
        RenderUtil.RECT.draw(matrices, drawX, drawY, width, height, 7,
            withAlpha(color(16, 10, 18, 240), Math.round(240 * animation)));
        for (int index = 0; index < lines.size(); index++) {
            Fonts.regular().drawText(matrices, lines.get(index), drawX + paddingX, drawY + paddingY + index * 8.2f, font,
                withAlpha(TEXT, Math.round(255 * animation)));
        }
    }

    private static List<String> wrapTooltip(String text, float font, float maxWidth) {
        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (Fonts.regular().getWidth(next, font) > maxWidth && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    private record ConfigAction(String id, String label, String help) {
    }

    private void drawConfigRow(MatrixStack matrices, float x, float y, float width,
                               String label, String value, float opacity) {
        RenderUtil.RECT.draw(matrices, x, y, width, 25, 7,
            withAlpha(CONTROL, Math.round(190 * opacity)));
        Fonts.regular().drawText(matrices, label, x + 10,
            centeredTextY(y, 25, 7.1f), 7.1f,
            withAlpha(MUTED, Math.round(255 * opacity)));
        float valueWidth = Fonts.semibold().getWidth(value, 7.1f);
        Fonts.semibold().drawText(matrices, value, x + width - 10 - valueWidth,
            centeredTextY(y, 25, 7.1f), 7.1f,
            withAlpha(TEXT, Math.round(255 * opacity)));
    }

    private void drawPlaceholder(MatrixStack matrices, String glyph, String title, String description, String action, float animation) {
        float entrance = pageEntrance(1);
        animation *= entrance;
        float cardWidth = Math.min(390, contentWidth - 30);
        float x = contentX + (contentWidth - cardWidth) / 2;
        float y = contentY + 34 + (1.0f - entrance) * 8.0f;
        RenderUtil.RECT.draw(matrices, x, y, cardWidth, 174, 15, withAlpha(CARD, Math.round(185 * animation)));
        RenderUtil.GRADIENT_RECT.draw(matrices, x + cardWidth / 2 - 25, y + 24, 50, 50, 15,
            withAlpha(color(255, 58, 155, 220), Math.round(220 * animation)),
            withAlpha(color(157, 33, 153, 220), Math.round(220 * animation)),
            withAlpha(color(215, 37, 132, 220), Math.round(220 * animation)),
            withAlpha(color(112, 25, 128, 220), Math.round(220 * animation)));
        Fonts.bold().drawCenteredText(matrices, glyph, x + cardWidth / 2,
            centeredTextY(y + 24, 50, 15), 15, withAlpha(Color.WHITE, Math.round(255 * animation)));
        Fonts.bold().drawCenteredText(matrices, title, x + cardWidth / 2, y + 91, 13, withAlpha(TEXT, Math.round(255 * animation)));
        Fonts.regular().drawCenteredText(matrices, description, x + cardWidth / 2, y + 113, 8, withAlpha(MUTED, Math.round(255 * animation)));
        RenderUtil.RECT.draw(matrices, x + cardWidth / 2 - 55, y + 139, 110, 25, 7, withAlpha(ACCENT_SOFT, Math.round(195 * animation)));
        Fonts.semibold().drawCenteredText(matrices, action, x + cardWidth / 2, y + 147, 8.2f, withAlpha(TEXT, Math.round(255 * animation)));
    }

    private void drawSettingsPage(MatrixStack matrices, int mouseX, int mouseY, float animation) {
        float gap = 10;
        float cardWidth = (contentWidth - gap) / 2;
        float cardHeight = 82;
        FrostedOption[] options = FrostedOption.values();
        for (int index = 0; index < options.length; index++) {
            boolean rightColumn = options[index] == FrostedOption.PASSES;
            int row = options[index] == FrostedOption.OFFSET ? 1 : 0;
            float optionX = contentX + (rightColumn ? cardWidth + gap : 0);
            float entrance = pageEntrance(index + 1);
            drawConfigCard(
                matrices,
                options[index],
                optionX,
                contentY + 5 + row * (cardHeight + gap) + (1.0f - entrance) * 7.0f,
                cardWidth,
                cardHeight,
                mouseX,
                mouseY,
                animation * entrance
            );
        }

        drawMenuKeyCard(matrices, contentX + cardWidth + gap, contentY + 5 + cardHeight + gap,
            cardWidth, cardHeight, mouseX, mouseY, animation * pageEntrance(options.length + 1));
    }

    private void drawMenuKeyCard(
        MatrixStack matrices,
        float x,
        float y,
        float width,
        float height,
        int mouseX,
        int mouseY,
        float animation
    ) {
        boolean hovered = hovered(mouseX, mouseY, x, y, width, height);
        float hover = hoverAnimation(MENU_KEY_HOVER_KEY, hovered);
        drawPanelCard(matrices, x, y, width, height, 11, hover, animation);
        Fonts.semibold().drawText(matrices, "Menu key", x + 14, y + 13, 9.5f,
            withAlpha(TEXT, Math.round(255 * animation)));
        String value = listeningMenuKey ? "Press a key" : keyName(InterfaceConfig.menuKey());
        float valueWidth = Math.max(55, Fonts.semibold().getWidth(value, 7.5f) + 14);
        drawRailCard(matrices, x + width - valueWidth - 12, y + 9, valueWidth, 19, 6, hover, animation);
        Fonts.semibold().drawCenteredText(matrices, value, x + width - valueWidth / 2 - 12,
            centeredTextY(y + 9, 19, 7.5f), 7.5f,
            withAlpha(listeningMenuKey ? ACCENT : TEXT, Math.round(255 * animation)));
        Fonts.regular().drawText(matrices, "Click, then press a key. Delete resets to Right Shift.",
            x + 14, y + 35, 6.7f, withAlpha(MUTED, Math.round(255 * animation)));
        menuKeyHit = new FieldHit(x, y, width, height);
    }

    private void drawConfigCard(
        MatrixStack matrices,
        FrostedOption option,
        float x,
        float y,
        float width,
        float height,
        int mouseX,
        int mouseY,
        float animation
    ) {
        boolean hovered = hovered(mouseX, mouseY, x, y, width, height);
        float hover = hoverAnimation(option, hovered);
        drawPanelCard(matrices, x, y, width, height, 11, hover, animation);
        Fonts.semibold().drawText(matrices, option.label, x + 14, y + 13, 9.5f,
            withAlpha(TEXT, Math.round(255 * animation)));
        String value = sliderValue(option.value());
        float valueWidth = Math.max(35, Fonts.semibold().getWidth(value, 7.5f) + 14);
        drawRailCard(matrices, x + width - valueWidth - 12, y + 9, valueWidth, 19, 6, 0.0f, animation);
        Fonts.semibold().drawCenteredText(matrices, value, x + width - valueWidth / 2 - 12,
            centeredTextY(y + 9, 19, 7.5f), 7.5f,
            withAlpha(TEXT, Math.round(255 * animation)));
        Fonts.regular().drawText(matrices, option.description, x + 14, y + 35, 7.1f,
            withAlpha(MUTED, Math.round(255 * animation)));

        float sliderX = x + 14;
        float sliderY = y + height - 17;
        float sliderWidth = width - 28;
        float targetProgress = (option.value() - option.minimum) / (option.maximum - option.minimum);
        float progress = sliderAnimations
            .computeIfAbsent(option, ignored -> new SpringAnimation())
            .update(targetProgress, SLIDER_SMOOTH_TIME_SECONDS);
        RenderUtil.RECT.draw(matrices, sliderX, sliderY, sliderWidth, 4, 2,
            withAlpha(color(86, 59, 82, 230), Math.round(230 * animation)));
        RenderUtil.RECT.draw(matrices, sliderX, sliderY, sliderWidth * progress, 4, 2,
            withAlpha(ACCENT, Math.round(255 * animation)));
        float knobX = sliderX + sliderWidth * progress - 3;
        RenderUtil.RECT.draw(matrices, knobX, sliderY - 1, 6, 6, 3,
            withAlpha(Color.WHITE, Math.round(250 * animation)));
        configHits.add(new ConfigHit(option, sliderX, sliderY - 8, sliderWidth, 20));
    }

    private void drawToggle(MatrixStack matrices, Object key, float x, float y, boolean enabled, float animation) {
        float progress = toggleAnimations
            .computeIfAbsent(key, ignored -> new TimedAnimation())
            .update(enabled ? 1.0f : 0.0f, TOGGLE_ANIMATION_NANOS);
        Color track = blend(color(82, 62, 80, 225), ACCENT, progress);
        RenderUtil.RECT.draw(matrices, x, y, 28, 16, 8,
            withAlpha(track, Math.round(track.getAlpha() * animation)));
        float knobX = x + 2 + 12 * progress;
        RenderUtil.RECT.draw(matrices, knobX, y + 2, 12, 12, 6, withAlpha(Color.WHITE, Math.round(245 * animation)));
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mouseX = click.x() / uiScale;
        double mouseY = click.y() / uiScale;
        if (selectedPage == CelestePage.FRIENDS && click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            boolean insideContent = hovered(mouseX, mouseY, contentX, contentY, contentWidth, contentHeight);
            if (insideContent) {
                friendFieldFocused = true;
                searchFocused = false;
                configFieldFocused = false;
                return true;
            }
        }
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE && selectedPage == CelestePage.FRIENDS) {
            for (FriendHit hit : friendHits) {
                if (hit.contains(mouseX, mouseY)) {
                    FriendManager.getInstance().removeFriend(hit.friend().uuid());
                    return true;
                }
            }
            return false;
        }
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT && click.button() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            return false;
        }
        if (colorPicker.mouseClicked(mouseX, mouseY)) {
            return true;
        }
        if (colorPicker.isOpen() && !colorPicker.popupContains(mouseX, mouseY)) {
            colorPicker.close();
            return true;
        }

        if (selectedPage == CelestePage.FRIENDS && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            for (SuggestionHit hit : suggestionHits) {
                if (hit.contains(mouseX, mouseY)) {
                    addSuggestedFriend(hit.suggestion());
                    friendFieldFocused = true;
                    searchFocused = false;
                    configFieldFocused = false;
                    return true;
                }
            }
            if (friendFieldHit != null && friendFieldHit.contains(mouseX, mouseY)) {
                friendFieldFocused = true;
                friendSuggestionIndex = 0;
                searchFocused = false;
                configFieldFocused = false;
                return true;
            }
            friendFieldFocused = false;
        } else if (selectedPage != CelestePage.FRIENDS) {
            friendFieldFocused = false;
        }

        if (selectedPage.feature() || selectedPage == CelestePage.CONFIGS) {
            float searchWidth = Math.min(176, contentWidth * 0.32f);
            float searchX = panelX + panelWidth - 22 - searchWidth;
            float searchY = panelY + (1.0f - openAnimation()) * 18.0f + 23;
            boolean onSearch = hovered(mouseX, mouseY, searchX, searchY, searchWidth, 29);
            if (onSearch && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                searchFocused = true;
                configFieldFocused = false;
                friendFieldFocused = false;
                return true;
            }
            if (searchFocused && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && !onSearch) {
                searchFocused = false;
                float offWidth = searchX - contentX - 8;
                if (offWidth > 0 && hovered(mouseX, mouseY, contentX, searchY, offWidth, 29)) {
                    return true;
                }
            }
        } else {
            searchFocused = false;
        }

        for (NavHit hit : navHits) {
            if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && hit.contains(mouseX, mouseY)) {
                if (hit.page() == CelestePage.HUD) {
                    client.setScreen(new HudEditorScreen(this));
                    return true;
                }
                if (selectedPage != hit.page()) {
                    pageChangedAt = System.nanoTime();
                }
                selectedPage = hit.page();
                collapseAllModules();
                listeningKeybind = null;
                contentScroll = targetScroll = 0;
                return true;
            }
        }

        boolean insideContent = hovered(mouseX, mouseY, contentX, contentY, contentWidth, contentHeight);
        if (insideContent && selectedPage == CelestePage.FRIENDS && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            for (FriendHit hit : friendHits) {
                if (hit.containsDelete(mouseX, mouseY)) {
                    FriendManager.getInstance().removeFriend(hit.friend().uuid());
                    return true;
                }
            }
        }
        if (insideContent && selectedPage == CelestePage.CONFIGS && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            for (TabHit hit : configTabHits) {
                if (hit.contains(mouseX, mouseY)) {
                    if (configTab != hit.tab()) {
                        configTab = hit.tab();
                        inspectConfigId = "";
                        contentScroll = targetScroll = 0;
                        if (configTab == ConfigLibrary.Tab.PUBLIC) {
                            runCloudConfigAction(
                                ConfigLibrary.getInstance().refreshPublicAsync(),
                                "Refreshing cloud configs..."
                            );
                        }
                    }
                    return true;
                }
            }
            for (IconHit hit : configIconHits) {
                if (hit.contains(mouseX, mouseY)) {
                    runConfigIcon(hit.action(), hit.id());
                    return true;
                }
            }
            if (configFieldHit != null && configFieldHit.contains(mouseX, mouseY)) {
                configFieldFocused = true;
                searchFocused = false;
                return true;
            }
            configFieldFocused = false;
            for (ActionHit hit : actionHits) {
                if (hit.contains(mouseX, mouseY)) {
                    runConfigAction(hit.action());
                    return true;
                }
            }
            for (ProfileHit hit : profileHits) {
                if (hit.contains(mouseX, mouseY) && hit.name().startsWith("my:")) {
                    runConfigLoad(hit.name().substring(3));
                    return true;
                }
            }
            if (!inspectConfigId.isEmpty()) {
                inspectConfigId = "";
                return true;
            }
        } else if (selectedPage != CelestePage.CONFIGS) {
            configFieldFocused = false;
        }

        for (ConfigHit hit : configHits) {
            if (!insideContent || !hit.contains(mouseX, mouseY) || click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                continue;
            }
            draggingConfig = hit;
            updateDraggedConfig(mouseX);
            return true;
        }

        if (insideContent && selectedPage == CelestePage.SETTINGS
            && menuKeyHit != null && menuKeyHit.contains(mouseX, mouseY)) {
            listeningMenuKey = true;
            return true;
        }

        for (SettingHit hit : settingHits) {
            if (!insideContent || !hit.contains(mouseX, mouseY)) {
                continue;
            }
            if (hit.setting() instanceof GuiSetting.Action action) {
                if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                    draggingSlider = null;
                    draggingRange = null;
                    action.invoke();
                }
            } else if (hit.setting() instanceof GuiSetting.Toggle toggle) {
                toggle.toggle();
            } else if (hit.setting() instanceof GuiSetting.Mode mode) {
                if (click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    mode.previous();
                } else {
                    mode.next();
                }
            } else if (hit.setting() instanceof GuiSetting.ItemPick itemPick) {
                ItemStack held = client.player == null ? ItemStack.EMPTY : client.player.getMainHandStack();
                if (!held.isEmpty()) {
                    itemPick.setItemId(Registries.ITEM.getId(held.getItem()).toString());
                } else if (click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    itemPick.previous();
                } else {
                    itemPick.next();
                }
            } else if (hit.setting() instanceof GuiSetting.Slider slider) {
                draggingSlider = slider;
                updateDraggedSlider(mouseX);
            } else if (hit.setting() instanceof GuiSetting.Range range) {
                draggingRange = range;
                float lowX = hit.x() + hit.width() * (float) range.lowProgress();
                float highX = hit.x() + hit.width() * (float) range.highProgress();
                draggingRangeHigh = Math.abs(mouseX - highX) <= Math.abs(mouseX - lowX);
                updateDraggedRange(mouseX);
            } else if (hit.setting() instanceof GuiSetting.Keybind keybind) {
                listeningKeybind = keybind;
            } else if (hit.setting() instanceof GuiSetting.ColorPick pick) {
                colorPicker.toggle(pick, hit.x(), hit.y());
            }
            return true;
        }

        for (ModuleHit hit : moduleHits) {
            if (!insideContent || !hit.contains(mouseX, mouseY)) {
                continue;
            }
            if (click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && !hit.module().settings().isEmpty()) {
                expandedModule = expandedModule == hit.module() ? null : hit.module();
            } else if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                hit.module().toggle();
            }
            return true;
        }

        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseReleased(Click click) {
        double mouseX = click.x() / uiScale;
        double mouseY = click.y() / uiScale;
        colorPicker.mouseReleased(mouseX, mouseY);
        updateDraggedSlider(mouseX);
        updateDraggedRange(mouseX);
        updateDraggedConfig(mouseX);
        draggingSlider = null;
        draggingRange = null;
        draggingConfig = null;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        colorPicker.drag(click.x() / uiScale, click.y() / uiScale);
        if (draggingSlider != null) {
            updateDraggedSlider(click.x() / uiScale);
            return true;
        }
        if (draggingRange != null) {
            updateDraggedRange(click.x() / uiScale);
            return true;
        }
        if (draggingConfig != null) {
            updateDraggedConfig(click.x() / uiScale);
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (isControlDown()) {
            InterfaceConfig.setScale(InterfaceConfig.scale() + verticalAmount * 0.06);
            return true;
        }
        if (hovered(mouseX / uiScale, mouseY / uiScale, contentX, contentY, contentWidth, contentHeight)
            && (selectedPage.feature() || selectedPage == CelestePage.CONFIGS || selectedPage == CelestePage.FRIENDS)) {
            targetScroll += (float) verticalAmount * 24;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (listeningKeybind != null) {
            listeningKeybind.setKeyCode(input.isEscape() || input.key() == GLFW.GLFW_KEY_DELETE || input.key() == GLFW.GLFW_KEY_BACKSPACE
                ? GLFW.GLFW_KEY_UNKNOWN
                : input.key());
            listeningKeybind = null;
            return true;
        }
        if (listeningMenuKey) {
            if (input.isEscape()) {
                listeningMenuKey = false;
                return true;
            }
            InterfaceConfig.setMenuKey(input.key() == GLFW.GLFW_KEY_DELETE || input.key() == GLFW.GLFW_KEY_BACKSPACE
                ? InterfaceConfig.DEFAULT_MENU_KEY
                : input.key());
            listeningMenuKey = false;
            return true;
        }
        if (input.key() == InterfaceConfig.menuKey()) {
            client.setScreen(null);
            return true;
        }
        if (friendFieldFocused) {
            List<PlayerSuggestion> suggestions = friendSuggestions(friendInput);
            if (!suggestions.isEmpty()) {
                if (input.key() == GLFW.GLFW_KEY_DOWN) {
                    friendSuggestionIndex = Math.min(suggestions.size() - 1, friendSuggestionIndex + 1);
                    return true;
                }
                if (input.key() == GLFW.GLFW_KEY_UP) {
                    friendSuggestionIndex = Math.max(0, friendSuggestionIndex - 1);
                    return true;
                }
            }
            if ((input.key() == GLFW.GLFW_KEY_DELETE || input.key() == GLFW.GLFW_KEY_BACKSPACE) && !friendInput.isEmpty()) {
                friendInput = friendInput.substring(0, friendInput.length() - 1);
                friendSuggestionIndex = 0;
                return true;
            }
            if (input.key() == GLFW.GLFW_KEY_TAB && !suggestions.isEmpty()) {
                friendInput = suggestions.get(Math.max(0, Math.min(friendSuggestionIndex, suggestions.size() - 1))).name();
                return true;
            }
            if (input.isEnter()) {
                if (!suggestions.isEmpty()) {
                    int index = Math.max(0, Math.min(friendSuggestionIndex, suggestions.size() - 1));
                    addSuggestedFriend(suggestions.get(index));
                } else {
                    friendStatus = FriendManager.getInstance().addFriendByName(client, friendInput);
                    friendStatusUntil = System.nanoTime() + 4_000_000_000L;
                    if (friendStatus.startsWith("Added")) {
                        friendInput = "";
                    }
                }
                return true;
            }
            if (input.isEscape()) {
                friendFieldFocused = false;
                return true;
            }
        }
        if (configFieldFocused) {
            if ((input.key() == GLFW.GLFW_KEY_DELETE || input.key() == GLFW.GLFW_KEY_BACKSPACE)
                && input.hasCtrl() && !configInput.isEmpty()) {
                configInput = "";
                return true;
            }
            if ((input.key() == GLFW.GLFW_KEY_DELETE || input.key() == GLFW.GLFW_KEY_BACKSPACE) && !configInput.isEmpty()) {
                configInput = configInput.substring(0, configInput.length() - 1);
                return true;
            }
            if (input.isEnter()) {
                runConfigAction(configInput.contains("\\") || configInput.contains("/") || configInput.contains(".")
                    ? "Import" : "Create");
                return true;
            }
            if (input.isEscape()) {
                configFieldFocused = false;
                return true;
            }
        }
        if (searchFocused) {
            if ((input.key() == GLFW.GLFW_KEY_DELETE || input.key() == GLFW.GLFW_KEY_BACKSPACE)
                && input.hasCtrl() && !search.isEmpty()) {
                deletePreviousSearchWord();
                collapseAllModules();
                contentScroll = targetScroll = 0;
                return true;
            }
            if ((input.key() == GLFW.GLFW_KEY_DELETE || input.key() == GLFW.GLFW_KEY_BACKSPACE) && !search.isEmpty()) {
                setSearchLength(search.length() - 1);
                collapseAllModules();
                contentScroll = targetScroll = 0;
                return true;
            }
            if (input.isEscape() || input.isEnter()) {
                searchFocused = false;
                return true;
            }
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (friendFieldFocused && input.isValidChar() && friendInput.length() < 16) {
            friendInput += input.asString();
            friendSuggestionIndex = 0;
            return true;
        }
        if (configFieldFocused && input.isValidChar() && configInput.length() < 120) {
            configInput += input.asString();
            return true;
        }
        if (searchFocused && input.isValidChar() && search.length() < 32) {
            appendSearch(input.asString());
            collapseAllModules();
            contentScroll = targetScroll = 0;
            return true;
        }
        return super.charTyped(input);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
    }

    @Override
    protected void applyBlur(DrawContext context) {
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void removed() {
        GuiRenderer.getInstance().clearScissors();
        RenderUtil.releaseBlur();
        draggingSlider = null;
        draggingRange = null;
        draggingConfig = null;
        listeningKeybind = null;
        GuiCursors.restoreNative(client);
        super.removed();
    }

    private void layout() {
        float minecraftScale = client == null
            ? REFERENCE_MINECRAFT_GUI_SCALE
            : (float) client.getWindow().getScaleFactor();
        float normalization = REFERENCE_MINECRAFT_GUI_SCALE / Math.max(0.1f, minecraftScale);
        float referenceWidth = width / normalization;
        float referenceHeight = height / normalization;

        float fitScale = Math.min(1.0f, Math.min(
            Math.max(0.1f, (referenceWidth - 20.0f) / 690.0f),
            Math.max(0.1f, (referenceHeight - 20.0f) / 430.0f)
        ));
        float designWidth = referenceWidth / fitScale;
        float designHeight = referenceHeight / fitScale;
        float margin = 22;
        panelWidth = Math.min(1040, Math.max(690, designWidth - margin * 2));
        panelHeight = Math.min(600, Math.max(430, designHeight - margin * 2));

        float requestedReferenceScale = fitScale * COMPACT_SCALE
            * (renderedInterfaceScale / InterfaceConfig.DEFAULT_SCALE);
        float maximumReferenceScale = Math.min(
            Math.max(0.1f, (referenceWidth - 12.0f) / panelWidth),
            Math.max(0.1f, (referenceHeight - 12.0f) / panelHeight)
        );
        uiScale = normalization * Math.min(requestedReferenceScale, maximumReferenceScale);

        float virtualWidth = width / uiScale;
        float virtualHeight = height / uiScale;
        panelX = (virtualWidth - panelWidth) / 2;
        panelY = (virtualHeight - panelHeight) / 2;
        sidebarWidth = Math.min(205, Math.max(174, panelWidth * 0.205f));
        contentX = panelX + sidebarWidth + 23;
        contentWidth = panelX + panelWidth - 22 - contentX;
        contentY = panelY + 72;
        contentHeight = panelHeight - 91;
    }

    private List<GuiModule> filteredModules() {
        int visibleLength = visibleSearchLength(System.nanoTime());
        String query = search.substring(0, visibleLength).toLowerCase(Locale.ROOT).trim();
        return GuiCatalog.modules().stream()
            .filter(module -> module.page() == selectedPage)
            .filter(module -> query.isEmpty()
                || module.name().toLowerCase(Locale.ROOT).contains(query)
                || module.description().toLowerCase(Locale.ROOT).contains(query))
            .toList();
    }

    private void updateDraggedSlider(double mouseX) {
        if (draggingSlider == null) {
            return;
        }
        for (SettingHit hit : settingHits) {
            if (hit.setting() != draggingSlider) {
                continue;
            }
            float sliderX = hit.x();
            double progress = clamp((float) ((mouseX - sliderX) / hit.width()), 0, 1);
            draggingSlider.setValue(draggingSlider.minimum() + (draggingSlider.maximum() - draggingSlider.minimum()) * progress);
            break;
        }
    }

    private void updateDraggedRange(double mouseX) {
        if (draggingRange == null) {
            return;
        }
        for (SettingHit hit : settingHits) {
            if (hit.setting() != draggingRange) {
                continue;
            }
            double progress = clamp((float) ((mouseX - hit.x()) / hit.width()), 0, 1);
            double value = draggingRange.minimum() + (draggingRange.maximum() - draggingRange.minimum()) * progress;
            if (draggingRangeHigh) {
                draggingRange.setHigh(value);
            } else {
                draggingRange.setLow(value);
            }
            break;
        }
    }

    private void updateDraggedConfig(double mouseX) {
        if (draggingConfig == null) {
            return;
        }
        double progress = clamp((float) ((mouseX - draggingConfig.x()) / draggingConfig.width()), 0, 1);
        FrostedOption option = draggingConfig.option();
        option.set(option.minimum + (option.maximum - option.minimum) * progress);
    }

    private void deletePreviousSearchWord() {
        int end = search.length();
        while (end > 0 && search.charAt(end - 1) == ' ') {
            end--;
        }
        int start = end;
        while (start > 0 && search.charAt(start - 1) != ' ') {
            start--;
        }
        setSearchLength(start);
    }

    private void appendSearch(String value) {
        if (value.isEmpty()) {
            return;
        }
        int available = 32 - search.length();
        String addition = value.substring(0, Math.min(available, value.length()));
        long now = System.nanoTime();
        search += addition;
        for (int index = 0; index < addition.length(); index++) {
            searchRevealTimes.add(now + SEARCH_REVEAL_DELAY_NANOS + index * 12_000_000L);
        }
    }

    private void setSearchLength(int length) {
        int clampedLength = Math.max(0, Math.min(length, search.length()));
        search = search.substring(0, clampedLength);
        while (searchRevealTimes.size() > clampedLength) {
            searchRevealTimes.remove(searchRevealTimes.size() - 1);
        }
    }

    private int visibleSearchLength(long now) {
        while (searchRevealTimes.size() < search.length()) {
            searchRevealTimes.add(now);
        }
        int visible = 0;
        while (visible < search.length() && searchRevealTimes.get(visible) <= now) {
            visible++;
        }
        return visible;
    }

    private float searchCharacterFade(int index, long now) {
        if (index < 0 || index >= searchRevealTimes.size()) {
            return 1.0f;
        }
        float elapsed = clamp((now - searchRevealTimes.get(index)) / (float) SEARCH_FADE_NANOS, 0, 1);
        return 1.0f - (float) Math.pow(1.0f - elapsed, 3);
    }

    private void collapseAllModules() {
        expandedModule = null;
        expansionAnimations.clear();
        colorPicker.close();
    }

    private boolean isControlDown() {
        if (client == null || client.getWindow() == null) {
            return false;
        }
        long handle = client.getWindow().getHandle();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
            || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
    }

    private void updateInterfaceScaleAnimation() {
        long now = System.nanoTime();
        float deltaSeconds = clamp((now - lastScaleUpdateAt) / 1_000_000_000.0f, 0.0f, 0.05f);
        lastScaleUpdateAt = now;
        if (deltaSeconds <= 0.0f) {
            return;
        }

        // Critically damped motion keeps wheel steps fluid without overshoot
        // and remains consistent at different frame rates.
        float target = InterfaceConfig.scale();
        float smoothTime = 0.14f;
        float omega = 2.0f / smoothTime;
        float x = omega * deltaSeconds;
        float decay = 1.0f / (1.0f + x + 0.48f * x * x + 0.235f * x * x * x);
        float change = renderedInterfaceScale - target;
        float temporary = (interfaceScaleVelocity + omega * change) * deltaSeconds;
        interfaceScaleVelocity = (interfaceScaleVelocity - omega * temporary) * decay;
        renderedInterfaceScale = target + (change + temporary) * decay;

        if (Math.abs(renderedInterfaceScale - target) < 0.0001f
            && Math.abs(interfaceScaleVelocity) < 0.0001f) {
            renderedInterfaceScale = target;
            interfaceScaleVelocity = 0.0f;
        }
    }

    private float hoverAnimation(Object key, boolean hovered) {
        return hoverAnimations
            .computeIfAbsent(key, ignored -> new TimedAnimation(0.0f))
            .update(hovered ? 1.0f : 0.0f, HOVER_ANIMATION_NANOS);
    }

    private float pageEntrance(int staggerIndex) {
        long elapsed = System.nanoTime() - pageChangedAt - Math.max(0, staggerIndex) * PAGE_STAGGER_NANOS;
        float t = clamp(elapsed / (float) PAGE_TRANSITION_NANOS, 0.0f, 1.0f);
        return 1.0f - (float) Math.pow(1.0f - t, 4);
    }

    /**
     * The custom font renderer accepts a top coordinate rather than a baseline.
     * Keeping this calculation in one place gives pills, rows and buttons the
     * same optical center instead of accumulating one-off offsets.
     */
    private static float centeredTextY(float y, float height, float fontSize) {
        return y + (height - fontSize) * 0.5f - 0.2f;
    }

    private float openAnimation() {
        float elapsed = (System.nanoTime() - openedAt) / 260_000_000.0f;
        float t = clamp(elapsed, 0, 1);
        return 1 - (float) Math.pow(1 - t, 3);
    }

    private static boolean pulse() {
        return (System.currentTimeMillis() / 500) % 2 == 0;
    }

    private static String itemPickName(GuiSetting.ItemPick pick) {
        Identifier id = Identifier.tryParse(pick.itemId());
        if (id == null) {
            return pick.itemId();
        }
        Item item = Registries.ITEM.get(id);
        if (item == Items.AIR) {
            return pick.itemId();
        }
        return item.getName().getString();
    }

    private static String keyName(int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_UNKNOWN) {
            return "Unbound";
        }
        return InputUtil.Type.KEYSYM.createFromCode(keyCode).getLocalizedText().getString();
    }

    private static String sliderValue(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return Integer.toString((int) Math.rint(value));
        }
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String trim(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, Math.max(0, maximum - 1)) + "...";
    }

    private boolean hovered(double mouseX, double mouseY, float x, float y, float width, float height) {
        boolean inside = inside(mouseX, mouseY, x, y, width, height);
        if (inside) {
            pointerHot = true;
        }
        return inside;
    }

    private static boolean inside(double mouseX, double mouseY, float x, float y, float width, float height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private boolean overInteractive(double mouseX, double mouseY) {
        for (NavHit hit : navHits) {
            if (hit.contains(mouseX, mouseY)) {
                return true;
            }
        }
        for (ModuleHit hit : moduleHits) {
            if (hit.contains(mouseX, mouseY)) {
                return true;
            }
        }
        for (SettingHit hit : settingHits) {
            if (hit.contains(mouseX, mouseY)) {
                return true;
            }
        }
        for (ConfigHit hit : configHits) {
            if (hit.contains(mouseX, mouseY)) {
                return true;
            }
        }
        for (FriendHit hit : friendHits) {
            if (hit.contains(mouseX, mouseY)) {
                return true;
            }
        }
        for (SuggestionHit hit : suggestionHits) {
            if (hit.contains(mouseX, mouseY)) {
                return true;
            }
        }
        for (ProfileHit hit : profileHits) {
            if (hit.contains(mouseX, mouseY)) {
                return true;
            }
        }
        for (ActionHit hit : actionHits) {
            if (hit.contains(mouseX, mouseY)) {
                return true;
            }
        }
        for (TabHit hit : configTabHits) {
            if (hit.contains(mouseX, mouseY)) {
                return true;
            }
        }
        for (IconHit hit : configIconHits) {
            if (hit.contains(mouseX, mouseY)) {
                return true;
            }
        }
        return configFieldHit != null && configFieldHit.contains(mouseX, mouseY)
            || friendFieldHit != null && friendFieldHit.contains(mouseX, mouseY)
            || menuKeyHit != null && menuKeyHit.contains(mouseX, mouseY);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static Color color(int red, int green, int blue, int alpha) {
        return new Color(red, green, blue, alpha);
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    private static Color blend(Color from, Color to, float progress) {
        float t = clamp(progress, 0, 1);
        return new Color(
            Math.round(from.getRed() + (to.getRed() - from.getRed()) * t),
            Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * t),
            Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * t),
            Math.round(from.getAlpha() + (to.getAlpha() - from.getAlpha()) * t)
        );
    }

    private enum FrostedOption {
        GLASSY("Glassy", "Amount of the world visible in glass", InterfaceConfig.MIN_GLASSY, InterfaceConfig.MAX_GLASSY) {
            @Override
            float value() {
                return InterfaceConfig.glassy();
            }

            @Override
            void set(double value) {
                InterfaceConfig.setGlassy(value);
            }
        },
        PASSES("Passes", "Blur quality and GPU workload", InterfaceConfig.MIN_PASSES, InterfaceConfig.MAX_PASSES) {
            @Override
            float value() {
                return InterfaceConfig.passes();
            }

            @Override
            void set(double value) {
                InterfaceConfig.setPasses(value);
            }
        },
        OFFSET("Offset", "Width of the frosted blur spread", InterfaceConfig.MIN_OFFSET, InterfaceConfig.MAX_OFFSET) {
            @Override
            float value() {
                return InterfaceConfig.offset();
            }

            @Override
            void set(double value) {
                InterfaceConfig.setOffset(value);
            }
        };

        private final String label;
        private final String description;
        private final float minimum;
        private final float maximum;

        FrostedOption(String label, String description, float minimum, float maximum) {
            this.label = label;
            this.description = description;
            this.minimum = minimum;
            this.maximum = maximum;
        }

        abstract float value();

        abstract void set(double value);
    }

    /**
     * A critically damped visual spring. The represented setting remains
     * snapped to its configured step; only the rendered fill/knob interpolates.
     */
    private static final class SpringAnimation {
        private boolean initialized;
        private float value;
        private float velocity;
        private long lastUpdateAt;

        float update(float target, float smoothTimeSeconds) {
            long now = System.nanoTime();
            if (!initialized) {
                initialized = true;
                value = target;
                lastUpdateAt = now;
                return value;
            }

            float deltaSeconds = clamp((now - lastUpdateAt) / 1_000_000_000.0f, 0.0f, 0.05f);
            lastUpdateAt = now;
            if (deltaSeconds <= 0.0f) {
                return value;
            }

            float omega = 2.0f / Math.max(0.001f, smoothTimeSeconds);
            float x = omega * deltaSeconds;
            float decay = 1.0f / (1.0f + x + 0.48f * x * x + 0.235f * x * x * x);
            float change = value - target;
            float temporary = (velocity + omega * change) * deltaSeconds;
            velocity = (velocity - omega * temporary) * decay;
            value = target + (change + temporary) * decay;

            if (Math.abs(value - target) < 0.0001f && Math.abs(velocity) < 0.0001f) {
                value = target;
                velocity = 0.0f;
            }
            return clamp(value, 0.0f, 1.0f);
        }
    }

    private static final class TimedAnimation {
        private boolean initialized;
        private float start;
        private float value;
        private float target;
        private long startedAt;

        TimedAnimation() {
        }

        TimedAnimation(float initialValue) {
            initialized = true;
            start = initialValue;
            value = initialValue;
            target = initialValue;
            startedAt = System.nanoTime();
        }

        float update(float requestedTarget, long durationNanos) {
            long now = System.nanoTime();
            if (!initialized) {
                initialized = true;
                start = requestedTarget;
                value = requestedTarget;
                target = requestedTarget;
                startedAt = now;
                return value;
            }

            value = sample(now, durationNanos);
            if (Float.compare(requestedTarget, target) != 0) {
                start = value;
                target = requestedTarget;
                startedAt = now;
            }
            value = sample(now, durationNanos);
            return value;
        }

        private float sample(long now, long durationNanos) {
            float elapsed = clamp((now - startedAt) / (float) durationNanos, 0, 1);
            float eased = 1.0f - (float) Math.pow(1.0f - elapsed, 3);
            return start + (target - start) * eased;
        }
    }

    private record NavHit(CelestePage page, float x, float y, float width, float height) {
        boolean contains(double mouseX, double mouseY) {
            return inside(mouseX, mouseY, x, y, width, height);
        }
    }

    private record ModuleHit(GuiModule module, float x, float y, float width, float height) {
        boolean contains(double mouseX, double mouseY) {
            return inside(mouseX, mouseY, x, y, width, height);
        }
    }

    private record SettingHit(GuiSetting setting, float x, float y, float width, float height) {
        boolean contains(double mouseX, double mouseY) {
            return inside(mouseX, mouseY, x, y, width, height);
        }
    }

    private record ConfigHit(FrostedOption option, float x, float y, float width, float height) {
        boolean contains(double mouseX, double mouseY) {
            return inside(mouseX, mouseY, x, y, width, height);
        }
    }

    private record FriendHit(FriendManager.Friend friend, float x, float y, float width, float height,
                             float deleteX, float deleteY, float deleteWidth, float deleteHeight) {
        boolean contains(double mouseX, double mouseY) {
            return inside(mouseX, mouseY, x, y, width, height);
        }

        boolean containsDelete(double mouseX, double mouseY) {
            return deleteWidth > 0 && inside(mouseX, mouseY, deleteX, deleteY, deleteWidth, deleteHeight);
        }
    }

    private record PlayerSuggestion(UUID uuid, String name, Identifier skin) {
    }

    private record SuggestionHit(PlayerSuggestion suggestion, float x, float y, float width, float height) {
        boolean contains(double mouseX, double mouseY) {
            return inside(mouseX, mouseY, x, y, width, height);
        }
    }

    private record FieldHit(float x, float y, float width, float height) {
        boolean contains(double mouseX, double mouseY) {
            return inside(mouseX, mouseY, x, y, width, height);
        }
    }

    private record ActionHit(String action, String tooltip, boolean helpOnly, float x, float y, float width, float height) {
        boolean contains(double mouseX, double mouseY) {
            return inside(mouseX, mouseY, x, y, width, height);
        }
    }

    private record ProfileHit(String name, float x, float y, float width, float height,
                              float deleteX, float deleteY, float deleteWidth, float deleteHeight) {
        boolean contains(double mouseX, double mouseY) {
            return inside(mouseX, mouseY, x, y, width, height);
        }

        boolean containsDelete(double mouseX, double mouseY) {
            return deleteWidth > 0 && inside(mouseX, mouseY, deleteX, deleteY, deleteWidth, deleteHeight);
        }
    }

    private record TabHit(ConfigLibrary.Tab tab, float x, float y, float width, float height) {
        boolean contains(double mouseX, double mouseY) {
            return inside(mouseX, mouseY, x, y, width, height);
        }
    }

    private record IconHit(String action, String id, float x, float y, float width, float height) {
        boolean contains(double mouseX, double mouseY) {
            return inside(mouseX, mouseY, x, y, width, height);
        }
    }

    private void runConfigIcon(String action, String id) {
        ConfigLibrary library = ConfigLibrary.getInstance();
        ConfigLibrary.Card card = currentConfigCard(id);
        try {
            switch (action) {
                case "star" -> {
                    library.toggleFavourite(id);
                    setConfigStatus(library.isFavourite(id) ? "Favourited" : "Unfavourited");
                }
                case "gear" -> inspectConfigId = id.equals(inspectConfigId) ? "" : id;
                case "publish" -> {
                    if (card != null && card.origin() == ConfigLibrary.Tab.MY) {
                        runCloudConfigAction(library.publishPublicAsync(card.name()), "Publishing " + card.name() + "...");
                    }
                }
                case "download" -> {
                    if (card != null && card.origin() != ConfigLibrary.Tab.MY) {
                        runCloudConfigAction(library.downloadPublicAsync(id), "Downloading " + card.name() + "...");
                    }
                }
                case "trash" -> {
                    if (configTab == ConfigLibrary.Tab.FAVOURITE) {
                        library.unfavourite(id);
                        setConfigStatus("Unfavourited");
                    } else if (card != null && card.origin() == ConfigLibrary.Tab.MY) {
                        runConfigDelete(card.name());
                    } else {
                        library.hidePublic(id);
                        setConfigStatus("Hidden");
                    }
                    if (id.equals(inspectConfigId)) {
                        inspectConfigId = "";
                    }
                }
                default -> {
                }
            }
        } catch (RuntimeException exception) {
            setConfigStatus(exception.getMessage() == null ? "Config action failed" : exception.getMessage());
        }
    }

    private ConfigLibrary.Card currentConfigCard(String id) {
        return ConfigLibrary.getInstance().list(configTab, search).stream()
            .filter(card -> card.id().equals(id))
            .findFirst().orElse(null);
    }

    private void runConfigAction(String action) {
        ConfigProfileManager configs = ConfigProfileManager.getInstance();
        try {
            switch (action) {
                case "Create" -> setConfigStatus("Created " + configs.create(configInput));
                case "Save" -> {
                    if (!configInput.isBlank()) {
                        setConfigStatus("Saved " + configs.create(configInput));
                    } else {
                        configs.saveActive();
                        setConfigStatus("Saved " + configs.active());
                    }
                }
                case "Import" -> {
                    configs.importFromPath(configInput);
                    setConfigStatus("Imported " + configs.active());
                }
                default -> {
                }
            }
        } catch (RuntimeException exception) {
            setConfigStatus(exception.getMessage() == null ? "Config action failed" : exception.getMessage());
        }
    }

    private void runConfigLoad(String name) {
        try {
            ConfigProfileManager.getInstance().load(name);
            setConfigStatus("Loaded " + name);
        } catch (RuntimeException exception) {
            setConfigStatus(exception.getMessage());
        }
    }

    private void runConfigDelete(String name) {
        try {
            ConfigProfileManager.getInstance().delete(name);
            setConfigStatus("Deleted " + name);
        } catch (RuntimeException exception) {
            setConfigStatus(exception.getMessage());
        }
    }


    private void setPendingTooltip(float x, float y, String text) {
        pendingTooltip = text == null ? "" : text;
        pendingTooltipX = x + 12;
        pendingTooltipY = y + 16;
    }

    private void setConfigStatus(String message) {
        configStatus = message == null ? "" : message;
        configStatusUntil = System.nanoTime() + 4_000_000_000L;
    }

    private void runCloudConfigAction(java.util.concurrent.CompletableFuture<String> action, String pending) {
        setConfigStatus(pending);
        action.whenComplete((message, error) -> {
            if (client == null) {
                return;
            }
            client.execute(() -> {
                if (error == null) {
                    setConfigStatus(message);
                    return;
                }
                Throwable cause = error;
                while (cause.getCause() != null && cause != cause.getCause()) {
                    cause = cause.getCause();
                }
                setConfigStatus(cause.getMessage() == null ? "Cloud config action failed" : cause.getMessage());
            });
        });
    }
}
