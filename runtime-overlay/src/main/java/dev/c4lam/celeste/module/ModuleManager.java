package dev.c4lam.celeste.module;

import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;

import dev.c4lam.celeste.action.GameplayFoundation;
import dev.c4lam.celeste.module.delivery.ModulePayloadProvider;
import dev.c4lam.celeste.module.delivery.ModulePayloadRuntime;
import dev.c4lam.celeste.module.delivery.ModulePayloadCatalog;
import dev.c4lam.celeste.module.delivery.ModuleHookRegistry;
import dev.c4lam.celeste.module.delivery.ModuleScope;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import dev.c4lam.celeste.gui.model.GuiCatalog;
import dev.c4lam.celeste.gui.model.GuiModule;
import dev.c4lam.celeste.gui.model.GuiSetting;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** Deterministic client-thread module lifecycle, keybind edges, and persistence. */
public final class ModuleManager implements AutoCloseable {
    private static final Logger LOGGER = dev.c4lam.celeste.compat.RuntimeLogging.getLogger("Celeste/Modules");
    private static final ModuleManager INSTANCE = new ModuleManager();

    private final Map<String, ModuleFeature> features = new LinkedHashMap<>();
    private final Map<String, Boolean> active = new HashMap<>();
    private final Map<String, ModuleScope> legacyScopes = new LinkedHashMap<>();
    private CompletionStage<Void> legacyStopped = CompletableFuture.completedFuture(null);
    private final Map<String, Boolean> keysDown = new HashMap<>();
    private boolean pendingFreecamFlightToggle;
    private final Path configPath = FabricLoader.getInstance().getConfigDir()
        .resolve("celeste").resolve("modules.properties");

    private volatile ModulePayloadRuntime payloads;
    private final ModuleHookRegistry legacyHooks = new ModuleHookRegistry((id, generation) -> {
        GuiModule module = moduleById(id);
        return dev.c4lam.celeste.CelesteClient.active() && module != null && module.enabled();
    });
    private boolean payloadCatalogRegistered;
    private boolean initialized;
    private Object lastWorld;
    private Object lastPlayer;
    private String lastSnapshot = "";
    private int persistenceTicks;

    private ModuleManager() {
    }

    public static ModuleManager getInstance() {
        return INSTANCE;
    }

    /** Must be configured by the authenticated modular bootstrap before registration. */
    public synchronized void configurePayloads(ModulePayloadProvider provider) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread() || initialized || payloads != null || !features.isEmpty())
            throw new IllegalStateException("Module delivery must be configured on the client thread before registration");
        dev.c4lam.celeste.render.ModuleBoxRenderer.initialize();
        dev.c4lam.celeste.render.ModuleVisualBudget.initialize();
        dev.c4lam.celeste.module.delivery.ModulePipelineResources.initialize();
        payloads = new ModulePayloadRuntime(provider, client::execute,
            id -> new ModuleContext(client, moduleById(id)), new ModulePayloadRuntime.Listener() {
                @Override public void installed(String id, ModuleFeature feature) {
                    if (!id.equals(key(feature.name())) || moduleById(id) == null || features.containsKey(id))
                        throw new IllegalStateException("Module implementation identity differs from its catalog");
                    features.put(id, feature);
                }
                @Override public void retiring(String id, ModuleFeature feature) {
                    GuiModule module = moduleById(id);
                    active.put(id, false);
                    GameplayFoundation.releaseOwner(feature);
                    try {
                        if (module != null) runScoped(feature, () -> feature.onDisable(new ModuleContext(client, module)));
                    } finally {
                        features.remove(id, feature);
                        active.remove(id);
                    }
                }
                @Override public void failed(String id, Throwable failure) {
                    LOGGER.error("Module {} could not load or retire", id, failure);
                }
            });
    }

    public boolean payloadsConfigured() { return payloads != null; }

    /** UI uses saved enable intent for its switch and actual delivery state for progress. */
    public String deliveryStatus(String name) {
        ModulePayloadRuntime current=payloads;
        if(current==null)return "";
        ModulePayloadRuntime.State state=current.state(key(name));
        if(state==null)return "";
        return switch(state) {
            case FETCHING -> "Loading\u2026";
            case STARTING -> "Starting\u2026";
            case RETIRING -> "Stopping\u2026";
            case FAILED -> "Failed - retry or reconnect";
            default -> "";
        };
    }

    public Map<String, ModulePayloadRuntime.State> payloadStates() {
        return payloads == null ? Map.of() : payloads.states();
    }

    public synchronized void registerPayloadCatalog() {
        if (payloads == null) throw new IllegalStateException("Module delivery is not configured");
        if (payloadCatalogRegistered) return;
        for (var descriptor : ModulePayloadCatalog.MODULES) {
            GuiModule module = findModule(descriptor.name());
            if (module == null || !descriptor.id().equals(key(module.name())))
                throw new IllegalStateException("Module metadata differs from the GUI catalog");
            payloads.register(descriptor.id());
        }
        payloadCatalogRegistered = true;
    }

    public ModuleHookRegistry hooks() {
        return payloads == null ? legacyHooks : payloads.hooks();
    }

    public CompletionStage<Void> payloadsStopped() {
        return payloads == null ? legacyStopped : payloads.stopped();
    }

    private static GuiModule moduleById(String id) {
        return GuiCatalog.modules().stream().filter(module -> key(module.name()).equals(id)).findFirst().orElse(null);
    }

    private boolean featureAuthorized(ModuleFeature feature) {
        return payloads == null || payloads.authorized(key(feature.name()));
    }

    private void runScoped(ModuleFeature feature, Runnable action) {
        if (payloads == null) {
            ModuleScope scope=legacyScopes.get(key(feature.name()));
            if (scope == null) { action.run(); return; }
            try (var ownership=scope.enter()) { action.run(); }
        } else try (var ownership = payloads.enter(key(feature.name()))) { action.run(); }
    }

    public synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        load();
        lastSnapshot = snapshot();
        BetaSubscriptions.register(ClientTickEvents.START_CLIENT_TICK, "celeste:modules/start_tick",
            ClientTickEvents.StartTick.class, this::startTick, ClientTickEvents.START_CLIENT_TICK::register);
        BetaSubscriptions.register(ClientTickEvents.END_CLIENT_TICK, "celeste:modules/end_tick",
            ClientTickEvents.EndTick.class, this::endTick, ClientTickEvents.END_CLIENT_TICK::register);
        BetaSubscriptions.register(WorldRenderEvents.START_MAIN, "celeste:modules/render_frame",
            WorldRenderEvents.StartMain.class, context -> onRenderFrame(), WorldRenderEvents.START_MAIN::register);
    }

    public synchronized void register(ModuleFeature feature) {
        if (payloads != null) throw new IllegalStateException("Resident module implementations are forbidden in modular mode");
        if (feature == null || findModule(feature.name()) == null) {
            throw new IllegalArgumentException("No GUI module exists for " + (feature == null ? "null" : feature.name()));
        }
        if (features.putIfAbsent(key(feature.name()), feature) != null) {
            throw new IllegalStateException("Duplicate module feature: " + feature.name());
        }
    }

    /** Compatibility profile uses the same factories with host-lifetime scoped registrations. */
    public synchronized void registerScoped(ModuleFeature feature, ModuleScope scope) {
        java.util.Objects.requireNonNull(scope);
        register(feature);
        legacyScopes.put(key(feature.name()), scope);
    }

    public List<ModuleFeature> features() {
        return List.copyOf(features.values());
    }

    public GuiModule module(String name) {
        return findModule(name);
    }

    public synchronized Properties exportState() {
        return properties();
    }

    public synchronized void importState(Properties properties) {
        if (properties == null) {
            return;
        }
        properties = dev.c4lam.celeste.gui.model.AmbientEffectsSettings.prepare(properties);
        for (GuiModule module : GuiCatalog.modules()) {
            String prefix = "module." + key(module.name());
            String enabled = properties.getProperty(prefix + ".enabled");
            if (enabled != null) {
                module.setEnabled(Boolean.parseBoolean(enabled));
            }
            for (GuiSetting setting : module.settings()) {
                apply(setting, properties.getProperty(prefix + ".setting." + key(setting.label())));
            }
        }
        save(snapshot());
    }

    public synchronized void saveNow() {
        save(snapshot());
    }

    private void startTick(MinecraftClient client) {
        if (!initialized || !dev.c4lam.celeste.CelesteClient.active()) {
            return;
        }
        if (payloads != null) dev.c4lam.celeste.module.delivery.ModulePipelineResources.maintenance();
        GameplayFoundation.beginTick(client);
        handleKeybinds(client);
        // A respawn may replace the player without replacing ClientWorld. Clear
        // old targets, player-age timers and input leases before re-enabling.
        if (lastWorld != client.world || lastPlayer != client.player) {
            deactivateAll(client);
            lastWorld = client.world;
            lastPlayer = client.player;
            for (ModuleFeature feature : new ArrayList<>(features.values())) {
                GuiModule module = findModule(feature.name());
                if (module != null) {
                    try {
                        runScoped(feature, () -> feature.onWorldChanged(new ModuleContext(client, module)));
                    } catch (RuntimeException exception) {
                        LOGGER.error("Module {} failed during world change", feature.name(), exception);
                    }
                }
            }
        }
        syncActive(client);
        tick(client, true);
        GameplayFoundation.resolveRotations(client);
        actionTick(client);
        GameplayFoundation.executeActions(client);
    }

    private void onRenderFrame() {
        if (!initialized || !dev.c4lam.celeste.CelesteClient.active()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        for (ModuleFeature feature : new ArrayList<>(features.values())) {
            GuiModule module = findModule(feature.name());
            if (module == null || !featureAuthorized(feature) || !Boolean.TRUE.equals(active.get(key(feature.name())))) {
                continue;
            }
            ModuleContext context = new ModuleContext(client, module);
            try {
                runScoped(feature, () -> feature.onRenderFrame(context));
            } catch (RuntimeException exception) {
                LOGGER.error("Module {} failed during render frame", feature.name(), exception);
                module.setEnabled(false);
                disable(feature, context);
            }
        }
    }

    private void endTick(MinecraftClient client) {
        if (!initialized || !dev.c4lam.celeste.CelesteClient.active()) {
            return;
        }
        tick(client, false);
        GameplayFoundation.afterTick(client);
        if (++persistenceTicks >= 20) {
            persistenceTicks = 0;
            String current = snapshot();
            if (!current.equals(lastSnapshot)) {
                save(current);
            }
        }
    }

    private void actionTick(MinecraftClient client) {
        for (ModuleFeature feature : new ArrayList<>(features.values())) {
            GuiModule module = findModule(feature.name());
            if (module == null || !featureAuthorized(feature) || !Boolean.TRUE.equals(active.get(key(feature.name())))) {
                continue;
            }
            ModuleContext context = new ModuleContext(client, module);
            try {
                runScoped(feature, () -> feature.onActionTick(context));
            } catch (RuntimeException exception) {
                LOGGER.error("Module {} failed during start-tick action phase", feature.name(), exception);
                module.setEnabled(false);
                disable(feature, context);
            }
        }
    }

    private void tick(MinecraftClient client, boolean start) {
        for (ModuleFeature feature : new ArrayList<>(features.values())) {
            GuiModule module = findModule(feature.name());
            if (module == null || !featureAuthorized(feature) || !Boolean.TRUE.equals(active.get(key(feature.name())))) {
                continue;
            }
            ModuleContext context = new ModuleContext(client, module);
            try {
                if (start) {
                    runScoped(feature, () -> feature.onStartTick(context));
                } else {
                    runScoped(feature, () -> feature.onEndTick(context));
                }
            } catch (RuntimeException exception) {
                LOGGER.error("Module {} failed during {} tick", feature.name(), start ? "start" : "end", exception);
                module.setEnabled(false);
                disable(feature, context);
            }
        }
    }

    private void syncActive(MinecraftClient client) {
        GuiModule freecam = moduleById("freecam");
        if (freecam == null || !freecam.enabled()) pendingFreecamFlightToggle = false;
        if (payloads != null) {
            Set<String> desired = new HashSet<>();
            for (var descriptor : ModulePayloadCatalog.MODULES) {
                GuiModule module = moduleById(descriptor.id());
                if (module != null && module.enabled()) desired.add(descriptor.id());
            }
            payloads.synchronize(desired);
        }
        for (ModuleFeature feature : new ArrayList<>(features.values())) {
            GuiModule module = findModule(feature.name());
            if (module == null) {
                continue;
            }
            String id = key(feature.name());
            boolean shouldRun = module.enabled() && (payloads == null || payloads.ready(id)) && feature.canRun(client);
            boolean running = Boolean.TRUE.equals(active.get(id));
            ModuleContext context = new ModuleContext(client, module);
            if (shouldRun && !running) {
                try {
                    runScoped(feature, () -> feature.onEnable(context));
                    if (payloads != null) payloads.started(id);
                    active.put(id, true);
                    if (id.equals("freecam") && pendingFreecamFlightToggle) {
                        pendingFreecamFlightToggle = false;
                        toggleFreecamFlight(client);
                    }
                } catch (RuntimeException exception) {
                    LOGGER.error("Module {} failed while enabling", feature.name(), exception);
                    if (payloads != null) payloads.abort(id, exception);
                    else { module.setEnabled(false); disable(feature, context); }
                }
            } else if (!shouldRun && running) {
                disable(feature, context);
            }
        }
    }

    private void disable(ModuleFeature feature, ModuleContext context) {
        active.put(key(feature.name()), false);
        if (payloads != null) payloads.paused(key(feature.name()));
        GameplayFoundation.releaseOwner(feature);
        try {
            runScoped(feature, () -> feature.onDisable(context));
        } catch (RuntimeException ignored) {
            LOGGER.error("Module {} failed while disabling", feature.name(), ignored);
        }
    }

    private void handleKeybinds(MinecraftClient client) {
        if (client.getWindow() == null) {
            return;
        }
        boolean accept = client.currentScreen == null && client.getOverlay() == null
            && client.isWindowFocused() && client.player != null && client.world != null;
        for (GuiModule module : GuiCatalog.modules()) {
            GuiSetting.Keybind bind = module.settings().stream()
                .filter(GuiSetting.Keybind.class::isInstance)
                .map(GuiSetting.Keybind.class::cast)
                .filter(keybind -> keybind.label().equalsIgnoreCase("Bind")
                    || keybind.label().equalsIgnoreCase("Keybind"))
                .findFirst().orElse(null);
            String id = key(module.name());
            if (id.equals("freecam") && !module.enabled()) pendingFreecamFlightToggle = false;
            if (bind == null || bind.keyCode() == GLFW.GLFW_KEY_UNKNOWN) {
                keysDown.remove(id);
                continue;
            }
            // Observe physical edges even while a GUI/overlay owns input. Closing it while
            // a key is still held must not manufacture a second module toggle.
            boolean pressed = InputUtil.isKeyPressed(client.getWindow(), bind.keyCode());
            if (accept && pressed && !keysDown.getOrDefault(id, false)) {
                if (id.equals("freecam")) {
                    if (!module.enabled()) {
                        module.toggle();
                    } else if (Boolean.TRUE.equals(active.get(id))) {
                        // Freecam's bind controls camera flight after the module
                        // has loaded. Keep its intent and payload resident.
                        try {
                            toggleFreecamFlight(client);
                        } catch (RuntimeException exception) {
                            LOGGER.error("Freecam flight toggle failed", exception);
                        }
                    } else {
                        // Preserve each edge while loading without canceling
                        // delivery. onEnable starts flight; replay odd parity.
                        pendingFreecamFlightToggle = !pendingFreecamFlightToggle;
                    }
                } else {
                    module.toggle();
                }
            }
            keysDown.put(id, pressed);
        }
    }

    private void toggleFreecamFlight(MinecraftClient client) {
        try { hooks().invoke("freecam", "freecam.toggle", false, client); }
        catch (Exception failure) { throw new IllegalStateException("Freecam flight toggle failed", failure); }
    }

    private void deactivateAll(MinecraftClient client) {
        for (ModuleFeature feature : new ArrayList<>(features.values())) {
            if (!Boolean.TRUE.equals(active.get(key(feature.name())))) {
                continue;
            }
            GuiModule module = findModule(feature.name());
            if (module != null) {
                disable(feature, new ModuleContext(client, module));
            }
        }
    }

    private void load() {
        if (!Files.isRegularFile(configPath)) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(configPath)) {
            properties.load(input);
        } catch (IOException ignored) {
            LOGGER.error("Could not load module configuration from {}", configPath, ignored);
            return;
        }
        properties = dev.c4lam.celeste.gui.model.AmbientEffectsSettings.prepare(properties);
        for (GuiModule module : GuiCatalog.modules()) {
            String prefix = "module." + key(module.name());
            String enabled = properties.getProperty(prefix + ".enabled");
            if (enabled != null) {
                module.setEnabled(Boolean.parseBoolean(enabled));
            }
            for (GuiSetting setting : module.settings()) {
                apply(setting, properties.getProperty(prefix + ".setting." + key(setting.label())));
            }
        }
    }

    private void save(String currentSnapshot) {
        try {
            Files.createDirectories(configPath.getParent());
            Path temporary = configPath.resolveSibling(configPath.getFileName() + ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties().store(output, "Celeste module state");
            }
            try {
                Files.move(temporary, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
            lastSnapshot = currentSnapshot;
        } catch (IOException ignored) {
            LOGGER.error("Could not save module configuration to {}", configPath, ignored);
        }
    }

    private Properties properties() {
        Properties properties = new Properties();
        for (GuiModule module : GuiCatalog.modules()) {
            String prefix = "module." + key(module.name());
            properties.setProperty(prefix + ".enabled", Boolean.toString(module.enabled()));
            for (GuiSetting setting : module.settings()) {
                if (setting instanceof GuiSetting.Action) continue;
                properties.setProperty(prefix + ".setting." + key(setting.label()), value(setting));
            }
        }
        return properties;
    }

    private String snapshot() {
        StringBuilder builder = new StringBuilder();
        for (GuiModule module : GuiCatalog.modules()) {
            builder.append(key(module.name())).append('=').append(module.enabled()).append(';');
            for (GuiSetting setting : module.settings()) {
                if (setting instanceof GuiSetting.Action) continue;
                builder.append(key(setting.label())).append('=').append(value(setting)).append(';');
            }
        }
        return builder.toString();
    }

    private static void apply(GuiSetting setting, String value) {
        if (value == null) {
            return;
        }
        try {
            if (setting instanceof GuiSetting.Toggle toggle) {
                toggle.setValue(Boolean.parseBoolean(value));
            } else if (setting instanceof GuiSetting.Slider slider) {
                slider.setValue(Double.parseDouble(value));
            } else if (setting instanceof GuiSetting.Range range) {
                range.setSerialized(value);
            } else if (setting instanceof GuiSetting.Mode mode) {
                mode.setValue(value);
            } else if (setting instanceof GuiSetting.Keybind bind) {
                bind.setKeyCode(Integer.parseInt(value));
            } else if (setting instanceof GuiSetting.ColorPick pick) {
                Integer rgb = GuiSetting.ColorPick.parse(value);
                if (rgb != null) {
                    pick.setRgb(rgb);
                }
            } else if (setting instanceof GuiSetting.ItemPick pick) {
                pick.setItemId(value);
            } else if (setting instanceof GuiSetting.BlockList blocks) {
                blocks.setSerialized(value);
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private static String value(GuiSetting setting) {
        if (setting instanceof GuiSetting.Toggle toggle) return Boolean.toString(toggle.value());
        if (setting instanceof GuiSetting.Slider slider) return Double.toString(slider.value());
        if (setting instanceof GuiSetting.Range range) return range.serialized();
        if (setting instanceof GuiSetting.Mode mode) return mode.value();
        if (setting instanceof GuiSetting.Keybind bind) return Integer.toString(bind.keyCode());
        if (setting instanceof GuiSetting.ColorPick pick) return pick.serialized();
        if (setting instanceof GuiSetting.ItemPick pick) return pick.itemId();
        if (setting instanceof GuiSetting.BlockList blocks) return blocks.serialized();
        return "";
    }

    private static GuiModule findModule(String name) {
        if (name == null) {
            return null;
        }
        return GuiCatalog.modules().stream()
            .filter(module -> module.name().equalsIgnoreCase(name))
            .findFirst().orElse(null);
    }

    private static String key(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    @Override
    public synchronized void close() {
        boolean saveState=initialized;
        initialized = false;
        pendingFreecamFlightToggle = false;
        if (payloads != null) payloads.close();
        deactivateAll(MinecraftClient.getInstance());
        if (payloads == null && !legacyScopes.isEmpty()) {
            List<ModuleScope> retiring=List.copyOf(legacyScopes.values());
            retiring.forEach(ModuleScope::close);
            legacyStopped=CompletableFuture.allOf(retiring.stream()
                .map(scope -> scope.retired().toCompletableFuture()).toArray(CompletableFuture[]::new));
            legacyScopes.clear();
            features.clear();
        }
        if (saveState) save(snapshot());
    }
}
