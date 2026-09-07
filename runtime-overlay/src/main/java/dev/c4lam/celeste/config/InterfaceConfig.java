package dev.c4lam.celeste.config;

import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
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
import java.util.Properties;

/** Live configuration for Celeste's interface and frosted-glass renderer. */
public final class InterfaceConfig {
    private static final Logger LOGGER = dev.c4lam.celeste.compat.RuntimeLogging.getLogger("Celeste/Interface");
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir()
        .resolve("celeste").resolve("interface.properties");
    public static final float DEFAULT_SCALE = 0.9f;
    public static final float MIN_SCALE = 0.6f;
    public static final float MAX_SCALE = 1.5f;

    public static final float DEFAULT_GLASSY = 0.4f;
    public static final float MIN_GLASSY = 0.0f;
    public static final float MAX_GLASSY = 1.0f;

    public static final int DEFAULT_PASSES = 3;
    public static final int MIN_PASSES = 1;
    public static final int MAX_PASSES = 5;

    public static final float DEFAULT_OFFSET = 12.0f;
    public static final float MIN_OFFSET = 5.0f;
    public static final float MAX_OFFSET = 25.0f;
    public static final int DEFAULT_MENU_KEY = GLFW.GLFW_KEY_RIGHT_SHIFT;

    private static volatile float scale = DEFAULT_SCALE;
    private static volatile float glassy = DEFAULT_GLASSY;
    private static volatile int passes = DEFAULT_PASSES;
    private static volatile float offset = DEFAULT_OFFSET;
    private static volatile int menuKey = DEFAULT_MENU_KEY;
    private static boolean initialized;
    private static boolean dirty;
    private static int saveTicks;

    private InterfaceConfig() {
    }

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        load();
        BetaSubscriptions.register(ClientTickEvents.END_CLIENT_TICK, "celeste:interface/end_tick",
            ClientTickEvents.EndTick.class, client -> flushEventually(), ClientTickEvents.END_CLIENT_TICK::register);
        initialized = true;
    }

    public static float scale() {
        return scale;
    }

    public static synchronized void setScale(double value) {
        float next = (float) Math.max(MIN_SCALE, Math.min(MAX_SCALE, value));
        if (Float.compare(scale, next) != 0) {
            scale = next;
            dirty = true;
        }
    }

    public static float glassy() {
        return glassy;
    }

    /** Blur contribution; the glass shader consumes the inverse as tint mix. */
    public static float glassTintMix() {
        return 1.0f - glassy;
    }

    public static synchronized void setGlassy(double value) {
        float next = snap(value, MIN_GLASSY, MAX_GLASSY, 0.1f);
        if (Float.compare(glassy, next) != 0) {
            glassy = next;
            dirty = true;
        }
    }

    public static int passes() {
        return passes;
    }

    public static synchronized void setPasses(double value) {
        int next = Math.round(snap(value, MIN_PASSES, MAX_PASSES, 1.0f));
        if (passes != next) {
            passes = next;
            dirty = true;
        }
    }

    public static float offset() {
        return offset;
    }

    public static synchronized void setOffset(double value) {
        float next = snap(value, MIN_OFFSET, MAX_OFFSET, 1.0f);
        if (Float.compare(offset, next) != 0) {
            offset = next;
            dirty = true;
        }
    }

    public static int menuKey() {
        return menuKey;
    }

    public static synchronized void setMenuKey(int keyCode) {
        int next = keyCode <= GLFW.GLFW_KEY_UNKNOWN ? DEFAULT_MENU_KEY : keyCode;
        if (menuKey != next) {
            menuKey = next;
            dirty = true;
        }
    }

    public static synchronized void reset() {
        scale = DEFAULT_SCALE;
        glassy = DEFAULT_GLASSY;
        passes = DEFAULT_PASSES;
        offset = DEFAULT_OFFSET;
        menuKey = DEFAULT_MENU_KEY;
        dirty = true;
    }

    public static synchronized Properties exportState() {
        Properties values = new Properties();
        values.setProperty("interface.scale", Float.toString(scale));
        values.setProperty("interface.glassy", Float.toString(glassy));
        values.setProperty("interface.passes", Integer.toString(passes));
        values.setProperty("interface.offset", Float.toString(offset));
        values.setProperty("interface.menuKey", Integer.toString(menuKey));
        return values;
    }

    public static synchronized void importState(Properties values) {
        if (values == null) {
            return;
        }
        scale = loadScale(values.getProperty("interface.scale", values.getProperty("scale")));
        glassy = clamp(values.getProperty("interface.glassy", values.getProperty("glassy")),
            DEFAULT_GLASSY, MIN_GLASSY, MAX_GLASSY, 0.1f);
        passes = Math.round(clamp(values.getProperty("interface.passes", values.getProperty("passes")),
            DEFAULT_PASSES, MIN_PASSES, MAX_PASSES, 1.0f));
        offset = clamp(values.getProperty("interface.offset", values.getProperty("offset")),
            DEFAULT_OFFSET, MIN_OFFSET, MAX_OFFSET, 1.0f);
        menuKey = loadMenuKey(values.getProperty("interface.menuKey", values.getProperty("menuKey")));
        dirty = true;
        save();
    }

    public static synchronized void close() {
        save();
    }

    private static synchronized void flushEventually() {
        if (dirty && ++saveTicks >= 20) {
            save();
        }
    }

    private static void load() {
        if (!Files.isRegularFile(CONFIG_PATH)) {
            return;
        }
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(CONFIG_PATH)) {
            values.load(input);
            scale = loadScale(values.getProperty("scale"));
            glassy = clamp(values.getProperty("glassy"), DEFAULT_GLASSY, MIN_GLASSY, MAX_GLASSY, 0.1f);
            passes = Math.round(clamp(values.getProperty("passes"), DEFAULT_PASSES,
                MIN_PASSES, MAX_PASSES, 1.0f));
            offset = clamp(values.getProperty("offset"), DEFAULT_OFFSET,
                MIN_OFFSET, MAX_OFFSET, 1.0f);
            menuKey = loadMenuKey(values.getProperty("menuKey"));
        } catch (IOException | NumberFormatException exception) {
            LOGGER.warn("Could not load interface settings from {}", CONFIG_PATH, exception);
        }
    }

    private static void save() {
        if (!dirty) {
            saveTicks = 0;
            return;
        }
        Properties values = new Properties();
        values.setProperty("scale", Float.toString(scale));
        values.setProperty("glassy", Float.toString(glassy));
        values.setProperty("passes", Integer.toString(passes));
        values.setProperty("offset", Float.toString(offset));
        values.setProperty("menuKey", Integer.toString(menuKey));
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Path temporary = CONFIG_PATH.resolveSibling(CONFIG_PATH.getFileName() + ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                values.store(output, "Celeste interface settings");
            }
            try {
                Files.move(temporary, CONFIG_PATH,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
            saveTicks = 0;
        } catch (IOException exception) {
            LOGGER.warn("Could not save interface settings to {}", CONFIG_PATH, exception);
        }
    }

    private static float clamp(String raw, float fallback, float minimum, float maximum, float step) {
        return raw == null ? fallback : snap(Double.parseDouble(raw), minimum, maximum, step);
    }

    private static float loadScale(String raw) {
        if (raw == null) {
            return DEFAULT_SCALE;
        }
        double stored = Double.parseDouble(raw);
        // Migrate the short-lived 1..8 scale model. Its default was 2.0,
        // which corresponds to the restored 0.9 default in the reference-scale model.
        if (stored > MAX_SCALE) {
            stored *= DEFAULT_SCALE / 2.0f;
        }
        return snap(stored, MIN_SCALE, MAX_SCALE, 0.01f);
    }

    private static int loadMenuKey(String raw) {
        if (raw == null) {
            return DEFAULT_MENU_KEY;
        }
        int parsed = Integer.parseInt(raw);
        return parsed <= GLFW.GLFW_KEY_UNKNOWN ? DEFAULT_MENU_KEY : parsed;
    }

    private static float snap(double value, float minimum, float maximum, float step) {
        double clamped = Math.max(minimum, Math.min(maximum, value));
        return (float) (Math.round(clamped / step) * step);
    }
}
