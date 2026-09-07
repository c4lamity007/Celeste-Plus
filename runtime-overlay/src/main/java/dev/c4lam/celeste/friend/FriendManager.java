package dev.c4lam.celeste.friend;

import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;

import dev.c4lam.celeste.CelesteClient;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import dev.c4lam.celeste.config.ConfigImageCodec;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.EntityHitResult;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/**
 * Owns Celeste's persistent friend list and the in-game middle-click gesture.
 *
 * <p>Friend identity is UUID based. Names are retained only as a useful last-known
 * label, so name changes cannot create duplicate or stale friend identities.</p>
 */
public final class FriendManager implements AutoCloseable {
    private static final Logger LOGGER = dev.c4lam.celeste.compat.RuntimeLogging.getLogger("Celeste/Friends");
    private static final FriendManager INSTANCE = new FriendManager();
    private static final String FRIEND_KEY_PREFIX = "friend.";

    private final Path friendFile = FabricLoader.getInstance().getConfigDir()
        .resolve("celeste")
        .resolve("friends.properties");
    private final Path privateDir = FabricLoader.getInstance().getConfigDir()
        .resolve("celeste")
        .resolve("private-friends");
    private final Map<UUID, Friend> friends = new LinkedHashMap<>();

    private boolean initialized;
    private volatile boolean closed;
    private boolean dirty;
    private boolean middleMouseDown;
    private UUID sessionUuid;

    private FriendManager() {
    }

    public static FriendManager getInstance() {
        return INSTANCE;
    }

    /** Loads the saved list and installs the client-tick input listener once. */
    public synchronized void initialize() {
        if (initialized || closed) {
            return;
        }

        load();
        BetaSubscriptions.register(ClientTickEvents.END_CLIENT_TICK, "celeste:friends/end_tick",
            ClientTickEvents.EndTick.class, this::onEndClientTick, ClientTickEvents.END_CLIENT_TICK::register);
        initialized = true;
    }

    public synchronized boolean isFriend(UUID uuid) {
        return uuid != null && friends.containsKey(uuid);
    }

    public boolean isFriend(PlayerEntity player) {
        return player != null && isFriend(player.getUuid());
    }

    /** Returns an immutable, name-sorted snapshot safe for GUI iteration. */
    public synchronized List<Friend> getFriends() {
        List<Friend> snapshot = new ArrayList<>(friends.values());
        snapshot.sort(Comparator.comparing(Friend::lastKnownName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Friend::uuid));
        return List.copyOf(snapshot);
    }

    public synchronized Optional<Friend> findByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }

        return friends.values().stream()
            .filter(friend -> friend.lastKnownName().equalsIgnoreCase(name.trim()))
            .findFirst();
    }

    public boolean addFriend(PlayerEntity player) {
        return player != null && addFriend(player.getUuid(), player.getName().getString());
    }

    /**
     * Adds a friend by username from the current world or tab list.
     *
     * @return a status string for the GUI
     */
    public synchronized String addFriendByName(MinecraftClient client, String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "Type a username first";
        }
        String name = rawName.trim();
        if (client != null && client.world != null) {
            for (PlayerEntity player : client.world.getPlayers()) {
                if (player.getName().getString().equalsIgnoreCase(name)) {
                    boolean added = addFriend(player.getUuid(), player.getName().getString());
                    return added ? "Added " + player.getName().getString() : player.getName().getString() + " is already a friend";
                }
            }
        }
        if (client != null && client.getNetworkHandler() != null) {
            for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
                if (entry.getProfile() == null) {
                    continue;
                }
                String profileName = entry.getProfile().name();
                UUID profileId = entry.getProfile().id();
                if (profileName != null && profileId != null && profileName.equalsIgnoreCase(name)) {
                    boolean added = addFriend(profileId, profileName);
                    return added ? "Added " + profileName : profileName + " is already a friend";
                }
            }
        }
        return "Couldn't find " + name + " in this world";
    }

    /**
     * Adds a friend, or refreshes that UUID's last-known name.
     *
     * @return {@code true} only when the UUID was newly added
     */
    public synchronized boolean addFriend(UUID uuid, String lastKnownName) {
        if (uuid == null) {
            return false;
        }

        String cleanName = cleanName(lastKnownName, uuid);
        Friend previous = friends.put(uuid, new Friend(uuid, cleanName));
        boolean added = previous == null;
        if (added || !previous.lastKnownName().equals(cleanName)) {
            dirty = true;
            save();
        }
        return added;
    }

    public synchronized boolean removeFriend(UUID uuid) {
        if (uuid == null || friends.remove(uuid) == null) {
            return false;
        }

        dirty = true;
        save();
        return true;
    }

    public synchronized boolean removeFriend(String lastKnownName) {
        Optional<Friend> friend = findByName(lastKnownName);
        return friend.isPresent() && removeFriend(friend.get().uuid());
    }

    /**
     * Toggles a player and immediately persists the result.
     *
     * @return {@code true} when the player is a friend after the toggle
     */
    public synchronized boolean toggleFriend(PlayerEntity player) {
        if (player == null) {
            return false;
        }

        UUID uuid = player.getUuid();
        if (friends.remove(uuid) != null) {
            dirty = true;
            save();
            return false;
        }

        friends.put(uuid, new Friend(uuid, cleanName(player.getName().getString(), uuid)));
        dirty = true;
        save();
        return true;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (dirty) {
            save();
        }
        middleMouseDown = false;
    }

    private void onEndClientTick(MinecraftClient client) {
        if (closed || !CelesteClient.active()) {
            middleMouseDown = false;
            return;
        }
        syncSession(client);
        boolean down = GLFW.glfwGetMouseButton(client.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_MIDDLE)
            == GLFW.GLFW_PRESS;
        boolean pressed = down && !middleMouseDown;
        middleMouseDown = down;

        // Track the physical edge even while a screen is open so closing one while
        // holding the button cannot accidentally toggle the entity behind it.
        if (!pressed || client.currentScreen != null || client.player == null || client.world == null) {
            return;
        }

        if (!(client.crosshairTarget instanceof EntityHitResult hitResult)) {
            return;
        }

        Entity target = hitResult.getEntity();
        if (target instanceof PlayerEntity player && player != client.player) {
            toggleFriend(player);
        }
    }

    private synchronized void load() {
        friends.clear();
        dirty = false;

        if (!Files.isRegularFile(friendFile)) {
            return;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(friendFile)) {
            properties.load(input);
        } catch (IOException exception) {
            LOGGER.error("Could not load friend list from {}", friendFile, exception);
            return;
        }

        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith(FRIEND_KEY_PREFIX)) {
                continue;
            }

            String rawUuid = key.substring(FRIEND_KEY_PREFIX.length());
            try {
                UUID uuid = UUID.fromString(rawUuid);
                friends.put(uuid, new Friend(uuid, cleanName(properties.getProperty(key), uuid)));
            } catch (IllegalArgumentException exception) {
                LOGGER.warn("Ignoring invalid friend UUID '{}' in {}", rawUuid, friendFile);
            }
        }
    }

    /** Writes to a sibling temporary file, then replaces the live file atomically. */
    private synchronized void save() {
        if (!dirty) {
            return;
        }

        Path parent = friendFile.getParent();
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, "friends-", ".properties.tmp");

            Properties properties = new Properties();
            properties.setProperty("schema", "1");
            friends.values().stream()
                .sorted(Comparator.comparing(friend -> friend.uuid().toString()))
                .forEach(friend -> properties.setProperty(
                    FRIEND_KEY_PREFIX + friend.uuid(),
                    friend.lastKnownName()
                ));

            try (FileChannel channel = FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            )) {
                OutputStream output = Channels.newOutputStream(channel);
                properties.store(output, "Celeste Client friends - UUID=last known name");
                output.flush();
                channel.force(true);
            }

            try {
                Files.move(
                    temporary,
                    friendFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, friendFile, StandardCopyOption.REPLACE_EXISTING);
            }

            temporary = null;
            dirty = false;
            savePrivatePng();
        } catch (IOException exception) {
            LOGGER.error("Could not save friend list to {}", friendFile, exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    LOGGER.debug("Could not remove temporary friend file {}", temporary, exception);
                }
            }
        }
    }

    private void syncSession(MinecraftClient client) {
        if (client == null || client.player == null) {
            return;
        }
        UUID uuid = client.player.getUuid();
        if (uuid.equals(sessionUuid)) {
            return;
        }
        if (dirty) {
            save();
        }
        boolean switchedAccounts = sessionUuid != null;
        sessionUuid = uuid;
        loadPrivatePng(switchedAccounts);
    }

    private Path privateFile() {
        return sessionUuid == null ? null : privateDir.resolve(sessionUuid + ".png");
    }

    private boolean hasAnyPrivatePng() {
        if (!Files.isDirectory(privateDir)) {
            return false;
        }
        try (var stream = Files.list(privateDir)) {
            return stream.anyMatch(path -> path.getFileName().toString().endsWith(".png"));
        } catch (IOException exception) {
            return false;
        }
    }

    private void savePrivatePng() {
        Path file = privateFile();
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(privateDir);
            Properties properties = new Properties();
            properties.setProperty("schema", "1");
            friends.values().forEach(friend -> properties.setProperty(
                FRIEND_KEY_PREFIX + friend.uuid(),
                friend.lastKnownName()
            ));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            properties.store(output, "Celeste private friends");
            ConfigImageCodec.write(file, output.toByteArray());
        } catch (IOException exception) {
            LOGGER.error("Could not save private friends PNG", exception);
        }
    }

    private void loadPrivatePng(boolean switchedAccounts) {
        Path file = privateFile();
        if (file == null) {
            return;
        }
        if (!Files.isRegularFile(file)) {
            if (switchedAccounts || hasAnyPrivatePng()) {
                friends.clear();
                dirty = false;
            } else if (!friends.isEmpty()) {
                dirty = true;
                savePrivatePng();
                dirty = false;
            }
            return;
        }
        try {
            Properties properties = new Properties();
            properties.load(new ByteArrayInputStream(ConfigImageCodec.read(file)));
            friends.clear();
            for (String key : properties.stringPropertyNames()) {
                if (!key.startsWith(FRIEND_KEY_PREFIX)) {
                    continue;
                }
                try {
                    UUID uuid = UUID.fromString(key.substring(FRIEND_KEY_PREFIX.length()));
                    friends.put(uuid, new Friend(uuid, cleanName(properties.getProperty(key), uuid)));
                } catch (IllegalArgumentException ignored) {
                }
            }
            dirty = false;
        } catch (IOException exception) {
            LOGGER.warn("Could not load private friends PNG", exception);
        }
    }

    private static String cleanName(String name, UUID uuid) {
        if (name == null) {
            return uuid.toString();
        }

        String clean = name.replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')
            .trim();
        if (clean.isEmpty()) {
            return uuid.toString();
        }

        // Defensive bound for hand-edited files and non-player text implementations.
        return clean.length() <= 64 ? clean : clean.substring(0, 64);
    }

    public record Friend(UUID uuid, String lastKnownName) {
        public Friend {
            if (uuid == null) {
                throw new IllegalArgumentException("Friend UUID cannot be null");
            }
            if (lastKnownName == null || lastKnownName.isBlank()) {
                throw new IllegalArgumentException("Friend name cannot be blank");
            }
        }

        public String normalizedName() {
            return lastKnownName.toLowerCase(Locale.ROOT);
        }
    }
}
