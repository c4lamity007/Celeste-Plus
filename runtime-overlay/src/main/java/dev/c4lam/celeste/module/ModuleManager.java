package dev.c4lam.celeste.module;

import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;

import dev.c4lam.celeste.action.GameplayFoundation;
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
    private final Map<String, Boolean> keysDown = new HashMap<>();
    private final Path configPath = FabricLoader.getInstance().getConfigDir()
        .resolve("celeste").resolve("modules.properties");

    private boolean initialized;
    private Object lastWorld;
    private String lastSnapshot = "";
    private int persistenceTicks;

    private ModuleManager() {
    }

    public static ModuleManager getInstance() {
        return INSTANCE;
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
        if (feature == null || findModule(feature.name()) == null) {
            throw new IllegalArgumentException("No GUI module exists for " + (feature == null ? "null" : feature.name()));
        }
        if (features.putIfAbsent(key(feature.name()), feature) != null) {
            throw new IllegalStateException("Duplicate module feature: " + feature.name());
        }
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
        lastSnapshot = snapshot();
        save(lastSnapshot);
    }

    public synchronized void saveNow() {
        lastSnapshot = snapshot();
        save(lastSnapshot);
    }

    private void startTick(MinecraftClient client) {
        if (!initialized || !dev.c4lam.celeste.CelesteClient.active()) {
            return;
        }
        GameplayFoundation.beginTick(client);
        handleKeybinds(client);
        if (lastWorld != client.world) {
            deactivateAll(client);
            lastWorld = client.world;
            for (ModuleFeature feature : features.values()) {
                GuiModule module = findModule(feature.name());
                if (module != null) {
                    try {
                        feature.onWorldChanged(new ModuleContext(client, module));
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
            if (module == null || !Boolean.TRUE.equals(active.get(key(feature.name())))) {
                continue;
            }
            ModuleContext context = new ModuleContext(client, module);
            try {
                feature.onRenderFrame(context);
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
            if (module == null || !Boolean.TRUE.equals(active.get(key(feature.name())))) {
                continue;
            }
            ModuleContext context = new ModuleContext(client, module);
            try {
                feature.onActionTick(context);
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
            if (module == null || !Boolean.TRUE.equals(active.get(key(feature.name())))) {
                continue;
            }
            ModuleContext context = new ModuleContext(client, module);
            try {
                if (start) {
                    feature.onStartTick(context);
                } else {
                    feature.onEndTick(context);
                }
            } catch (RuntimeException exception) {
                LOGGER.error("Module {} failed during {} tick", feature.name(), start ? "start" : "end", exception);
                module.setEnabled(false);
                disable(feature, context);
            }
        }
    }

    private void syncActive(MinecraftClient client) {
        for (ModuleFeature feature : features.values()) {
            GuiModule module = findModule(feature.name());
            if (module == null) {
                continue;
            }
            String id = key(feature.name());
            boolean shouldRun = module.enabled() && feature.canRun(client);
            boolean running = Boolean.TRUE.equals(active.get(id));
            ModuleContext context = new ModuleContext(client, module);
            if (shouldRun && !running) {
                active.put(id, true);
                try {
                    feature.onEnable(context);
                } catch (RuntimeException exception) {
                    LOGGER.error("Module {} failed while enabling", feature.name(), exception);
                    module.setEnabled(false);
                    disable(feature, context);
                }
            } else if (!shouldRun && running) {
                disable(feature, context);
            }
        }
    }

    private void disable(ModuleFeature feature, ModuleContext context) {
        active.put(key(feature.name()), false);
        GameplayFoundation.releaseOwner(feature);
        try {
            feature.onDisable(context);
        } catch (RuntimeException ignored) {
            LOGGER.error("Module {} failed while disabling", feature.name(), ignored);
        }
    }

    private void handleKeybinds(MinecraftClient client) {
        if (client.getWindow() == null) {
            return;
        }
        boolean accept = client.currentScreen == null && client.player != null;
        for (GuiModule module : GuiCatalog.modules()) {
            GuiSetting.Keybind bind = module.settings().stream()
                .filter(GuiSetting.Keybind.class::isInstance)
                .map(GuiSetting.Keybind.class::cast)
                .filter(keybind -> keybind.label().equalsIgnoreCase("Bind"))
                .findFirst().orElse(null);
            if (bind == null || bind.keyCode() == GLFW.GLFW_KEY_UNKNOWN) {
                continue;
            }
            String id = key(module.name());
            boolean pressed = accept && InputUtil.isKeyPressed(client.getWindow(), bind.keyCode());
            if (pressed && !keysDown.getOrDefault(id, false)) {
                module.toggle();
            }
            keysDown.put(id, pressed);
        }
    }

    private void deactivateAll(MinecraftClient client) {
        for (ModuleFeature feature : features.values()) {
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
        if (!initialized) {
            return;
        }
        initialized = false;
        deactivateAll(MinecraftClient.getInstance());
        save(snapshot());
    }
}
