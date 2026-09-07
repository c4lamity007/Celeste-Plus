package dev.c4lam.celeste.config;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local config browser state backed by the licensed VPS public library. */
public final class ConfigLibrary {
    public enum Tab {
        MY("My Configs"),
        FAVOURITE("Favourite Configs"),
        PUBLIC("Public Configs");

        private final String label;

        Tab(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Card(
        String id,
        String name,
        String dateLabel,
        Tab origin,
        List<String> enabledModules,
        Properties snapshot
    ) {
        public String initial() {
            return name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
        }
    }

    private static final Logger LOGGER = dev.c4lam.celeste.compat.RuntimeLogging.getLogger("Celeste/ConfigLibrary");
    private static final ConfigLibrary INSTANCE = new ConfigLibrary();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("America/Los_Angeles");
    private static final ExecutorService CLOUD_WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "celeste-cloud-configs");
        thread.setDaemon(true);
        thread.setContextClassLoader(ClassLoader.getPlatformClassLoader());
        return thread;
    });

    private final Path libraryFile = FabricLoader.getInstance().getConfigDir()
        .resolve("celeste").resolve("library.json");
    private final Set<String> favourites = new LinkedHashSet<>();
    private final Set<String> hiddenPublic = new LinkedHashSet<>();
    private final CloudConfigClient cloud = new CloudConfigClient();
    private final AtomicBoolean cloudBusy = new AtomicBoolean();
    private List<Card> publicCards = List.of();
    private String publicError = "";
    private boolean initialized;
    private volatile boolean closed;
    private CompletableFuture<String> cloudFuture;
    private final CompletableFuture<Void> cloudStopped = new CompletableFuture<>();

    private ConfigLibrary() {
    }

    public static ConfigLibrary getInstance() {
        return INSTANCE;
    }

    public synchronized void initialize() {
        if (initialized || closed) {
            return;
        }
        load();
        initialized = true;
        refreshPublicAsync();
    }

    public synchronized boolean isFavourite(String id) {
        return id != null && favourites.contains(id);
    }

    public synchronized void toggleFavourite(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        if (!favourites.remove(id)) {
            favourites.add(id);
        }
        save();
    }

    public synchronized void unfavourite(String id) {
        if (id != null && favourites.remove(id)) {
            save();
        }
    }

    public synchronized void hidePublic(String id) {
        if (id == null || id.isBlank() || !hiddenPublic.add(id)) {
            return;
        }
        save();
    }

    public synchronized int totalVisible(Tab tab) {
        return list(tab, "").size();
    }

    public synchronized List<Card> list(Tab tab, String query) {
        initialize();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Card> cards = new ArrayList<>();
        switch (tab == null ? Tab.MY : tab) {
            case MY -> cards.addAll(listMine());
            case FAVOURITE -> {
                for (Card card : listMine()) {
                    if (isFavourite(card.id())) {
                        cards.add(card);
                    }
                }
                for (Card card : listPublic()) {
                    if (isFavourite(card.id())) {
                        cards.add(card);
                    }
                }
            }
            case PUBLIC -> cards.addAll(listPublic());
        }
        if (needle.isEmpty()) {
            return List.copyOf(cards);
        }
        return cards.stream()
            .filter(card -> card.name().toLowerCase(Locale.ROOT).contains(needle)
                || card.id().toLowerCase(Locale.ROOT).contains(needle))
            .toList();
    }

    public boolean cloudBusy() {
        return cloudBusy.get();
    }

    public synchronized String publicError() {
        return publicError;
    }

    public CompletableFuture<String> refreshPublicAsync() {
        return cloudTask(() -> {
            refreshPublicNow();
            return "Cloud configs refreshed";
        });
    }

    public CompletableFuture<String> publishPublicAsync(String rawName) {
        String name = ConfigProfileManager.sanitize(rawName);
        return cloudTask(() -> {
            ConfigProfileManager profiles = ConfigProfileManager.getInstance();
            if (name.equals(profiles.active())) {
                profiles.saveActive();
            }
            String published = cloud.publish(name, profiles.exportImage(name), profiles.enabledModules(name));
            refreshPublicNow();
            return "Published " + published;
        });
    }

    public CompletableFuture<String> downloadPublicAsync(String id) {
        return cloudTask(() -> {
            Card card;
            synchronized (this) {
                card = publicCards.stream().filter(entry -> entry.id().equals(id)).findFirst().orElse(null);
            }
            if (card == null) {
                throw new IllegalArgumentException("Public config is gone");
            }
            byte[] image = cloud.download(id);
            String imported = ConfigProfileManager.getInstance().importCloudImage(card.name(), image);
            refreshPublicNow();
            return "Downloaded " + imported;
        });
    }

    public synchronized List<String> enabledModules(Card card) {
        if (card == null) {
            return List.of();
        }
        if (card.origin() != Tab.MY) {
            return List.copyOf(card.enabledModules());
        }
        return ConfigProfileManager.getInstance().enabledModules(card.name());
    }

    private List<Card> listMine() {
        List<Card> cards = new ArrayList<>();
        for (ConfigProfileManager.ConfigEntry entry : ConfigProfileManager.getInstance().listEntries()) {
            cards.add(new Card(
                "my:" + entry.name(),
                entry.name(),
                format(entry.modifiedMillis()),
                Tab.MY,
                ConfigProfileManager.getInstance().enabledModules(entry.name()),
                new Properties()
            ));
        }
        return cards;
    }

    private List<Card> listPublic() {
        List<Card> cards = new ArrayList<>();
        for (Card card : publicCards) {
            if (!hiddenPublic.contains(card.id())) {
                cards.add(card);
            }
        }
        return cards;
    }

    private synchronized CompletableFuture<String> cloudTask(CloudOperation operation) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Cloud configs are stopped"));
        }
        if (!cloudBusy.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture("Cloud sync is already running");
        }
        cloudFuture = CompletableFuture.supplyAsync(() -> {
            try {
                if (closed) throw new IllegalStateException("Cloud configs are stopped");
                String result = operation.run();
                synchronized (this) {
                    if (!closed) publicError = "";
                }
                return result;
            } catch (Exception exception) {
                synchronized (this) {
                    if (!closed) publicError = friendly(exception);
                }
                throw new CompletionException(exception);
            } finally {
                cloudBusy.set(false);
            }
        }, CLOUD_WORKER);
        return cloudFuture;
    }

    /** Interrupt work without blocking Minecraft; stopped() proves actual worker retirement. */
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (cloudFuture != null) cloudFuture.cancel(false);
        CLOUD_WORKER.shutdownNow();
        cloudBusy.set(false);
        if (CLOUD_WORKER.isTerminated()) cloudStopped.complete(null);
        else CompletableFuture.runAsync(this::awaitCloudTermination);
    }

    public CompletionStage<Void> stopped() {
        return cloudStopped.minimalCompletionStage();
    }

    private void awaitCloudTermination() {
        try {
            if (!CLOUD_WORKER.awaitTermination(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Cloud config worker did not stop");
            }
            synchronized (this) { cloudFuture = null; }
            cloudStopped.complete(null);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            cloudStopped.completeExceptionally(failure);
        } catch (RuntimeException failure) {
            cloudStopped.completeExceptionally(failure);
        }
    }

    @FunctionalInterface
    private interface CloudOperation {
        String run() throws Exception;
    }

    private void refreshPublicNow() {
        try {
            List<Card> next = cloud.list().stream()
                .map(remote -> new Card(
                    remote.id(),
                    remote.name(),
                    format(remote.updatedAt() * 1_000L),
                    Tab.PUBLIC,
                    remote.modules(),
                    new Properties()
                ))
                .toList();
            synchronized (this) {
                if (!closed) publicCards = List.copyOf(next);
            }
        } catch (Exception exception) {
            throw new IllegalStateException(friendly(exception), exception);
        }
    }

    private static String friendly(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause != cause.getCause()) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? "Cloud config request failed" : message;
    }

    private static String format(long millis) {
        if (millis <= 0) {
            return "-";
        }
        return DATE.format(Instant.ofEpochMilli(millis).atZone(DISPLAY_ZONE));
    }

    private void load() {
        favourites.clear();
        hiddenPublic.clear();
        if (!Files.isRegularFile(libraryFile)) {
            return;
        }
        try {
            String raw = Files.readString(libraryFile, StandardCharsets.UTF_8);
            favourites.addAll(readArray(raw, "favourites"));
            hiddenPublic.addAll(readArray(raw, "hiddenPublic"));
        } catch (IOException exception) {
            LOGGER.error("Could not load config library {}", libraryFile, exception);
        }
    }

    private void save() {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"favourites\": ");
        writeArray(json, favourites);
        json.append(",\n  \"hiddenPublic\": ");
        writeArray(json, hiddenPublic);
        json.append("\n}\n");
        try {
            Files.createDirectories(libraryFile.getParent());
            Files.writeString(libraryFile, json.toString(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            LOGGER.error("Could not save config library {}", libraryFile, exception);
        }
    }

    private static void writeArray(StringBuilder json, Set<String> values) {
        json.append('[');
        boolean first = true;
        for (String value : values) {
            if (!first) {
                json.append(", ");
            }
            first = false;
            json.append('"').append(escape(value)).append('"');
        }
        json.append(']');
    }

    private static List<String> readArray(String raw, String key) {
        List<String> values = new ArrayList<>();
        String token = "\"" + key + "\"";
        int keyIndex = raw.indexOf(token);
        if (keyIndex < 0) {
            return values;
        }
        int open = raw.indexOf('[', keyIndex);
        int close = raw.indexOf(']', open);
        if (open < 0 || close < 0) {
            return values;
        }
        String body = raw.substring(open + 1, close);
        for (String part : body.split(",")) {
            String trimmed = part.trim();
            if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                values.add(unescape(trimmed.substring(1, trimmed.length() - 1)));
            }
        }
        return values;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
