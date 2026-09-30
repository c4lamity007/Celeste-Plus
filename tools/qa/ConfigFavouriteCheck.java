package dev.c4lam.celeste.config;

import dev.c4lam.celeste.module.ModuleManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Real library/profile/PNG persistence with a controlled cloud boundary, no game/network. */
public final class ConfigFavouriteCheck {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static Properties snapshot(String value) {
        Properties p = new Properties();
        p.setProperty("module.test.enabled", "true");
        p.setProperty("module.test.value", value);
        p.setProperty("hud.layout", "unchanged-snapshot");
        return p;
    }
    private static Properties read(Path path) throws Exception {
        Properties p = new Properties();
        p.load(new ByteArrayInputStream(ConfigImageCodec.read(path)));
        return p;
    }
    private static byte[] png(Path root, Properties p) throws Exception {
        var bytes = new ByteArrayOutputStream();
        p.store(bytes, "fixture");
        Path image = root.resolve("fixture.png");
        ConfigImageCodec.write(image, bytes.toByteArray());
        return Files.readAllBytes(image);
    }
    private static void reload(ConfigLibrary library) throws Exception {
        var method = ConfigLibrary.class.getDeclaredMethod("load");
        method.setAccessible(true);
        method.invoke(library);
    }
    private static void failed(java.util.concurrent.CompletableFuture<String> future) throws Exception {
        try { future.get(5, TimeUnit.SECONDS); throw new AssertionError("Expected failure"); }
        catch (java.util.concurrent.ExecutionException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        System.setProperty("celeste.fixture.config", args[0]);
        Path root = Path.of(args[0]);
        Files.createDirectories(root);
        CloudConfigClient.image = png(root, snapshot("server-value"));
        CloudConfigClient.cards = List.of(
            new CloudConfigClient.RemoteConfig("cloud-first", "public-preset", "", 1, 0, List.of("Test"), false),
            new CloudConfigClient.RemoteConfig("cloud-second", "second", "", 1, 0, List.of("Test"), false),
            new CloudConfigClient.RemoteConfig("cloud-failure", "failure", "", 1, 0, List.of("Test"), false));
        ConfigProfileManager profiles = ConfigProfileManager.getInstance();
        ConfigLibrary library = ConfigLibrary.getInstance();
        profiles.initialize();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (library.cloudBusy() && System.nanoTime() < deadline) Thread.sleep(5);
        check(!library.cloudBusy(), "Initial refresh did not finish");
        String collision = profiles.importSnapshot("public-preset", snapshot("local-value"));
        String active = profiles.active();
        int imports = ModuleManager.imports;

        CloudConfigClient.pause = true;
        var pending = library.toggleFavouriteAsync("cloud-first");
        check(CloudConfigClient.entered.await(5, TimeUnit.SECONDS), "Download did not start asynchronously");
        check(!library.isFavourite("cloud-first"), "Favourite marked before successful download");
        check(!library.toggleFavouriteAsync("cloud-first").get(5, TimeUnit.SECONDS).isEmpty(), "Duplicate click got no status");
        check(CloudConfigClient.downloads == 1, "Concurrent star downloaded twice");
        CloudConfigClient.release.countDown();
        pending.get(5, TimeUnit.SECONDS);
        Path saved = profiles.configsDirectory().resolve("public-preset-2.png");
        check(Files.isRegularFile(saved), "Public favourite missing from My Configs");
        check(read(saved).equals(snapshot("server-value")), "Imported live state instead of server snapshot");
        check(read(profiles.configsDirectory().resolve(collision + ".png")).equals(snapshot("local-value")), "Name collision overwrote owned profile");
        check(profiles.active().equals(active) && ModuleManager.imports == imports, "Starring applied config or switched active profile");
        check(library.isFavourite("cloud-first") && library.isFavourite("my:public-preset-2"), "Saved and public stars disagree");
        var favourites = library.list(ConfigLibrary.Tab.FAVOURITE, "");
        check(favourites.size() == 1 && favourites.getFirst().origin() == ConfigLibrary.Tab.MY, "Favourite list duplicates cloud and local copy");

        reload(library);
        check(library.isFavourite("cloud-first") && library.isFavourite("my:public-preset-2"), "Favourite mapping did not persist");
        library.unfavourite("my:public-preset-2");
        check(!library.isFavourite("cloud-first") && Files.isRegularFile(saved), "Unstar deleted saved copy or left remote star set");
        CloudConfigClient.failure = true;
        int fetched = CloudConfigClient.downloads;
        library.toggleFavouriteAsync("cloud-first").get(5, TimeUnit.SECONDS);
        check(CloudConfigClient.downloads == fetched && library.isFavourite("cloud-first"), "Offline restar did not reuse saved copy");
        library.downloadPublicAsync("cloud-first").get(5, TimeUnit.SECONDS);
        check(CloudConfigClient.downloads == fetched && profiles.listProfiles().size() == 3, "Download after star created duplicate copy");

        failed(library.toggleFavouriteAsync("cloud-failure"));
        check(!library.isFavourite("cloud-failure") && !profiles.listProfiles().contains("failure"), "Failed network request left a false favourite/profile");
        CloudConfigClient.failure = false;
        byte[] valid = CloudConfigClient.image;
        CloudConfigClient.image = new byte[] {1, 2, 3};
        failed(library.toggleFavouriteAsync("cloud-failure"));
        check(!library.isFavourite("cloud-failure") && !profiles.listProfiles().contains("failure"), "Invalid PNG was saved/favourited");
        CloudConfigClient.image = valid;

        library.downloadPublicAsync("cloud-second").get(5, TimeUnit.SECONDS);
        fetched = CloudConfigClient.downloads;
        library.toggleFavouriteAsync("cloud-second").get(5, TimeUnit.SECONDS);
        check(CloudConfigClient.downloads == fetched && library.isFavourite("my:second"), "Starring downloaded config made another copy");
        profiles.delete("second");
        library.forgetLocalCopy("second");
        check(!library.isFavourite("cloud-second"), "Delete left a stale public favourite");
        library.toggleFavouriteAsync("cloud-second").get(5, TimeUnit.SECONDS);
        check(CloudConfigClient.downloads == fetched + 1 && profiles.listProfiles().contains("second"), "Deleted copy could not be saved again");

        Path state = root.resolve("celeste/library.json");
        byte[] savedState = Files.readAllBytes(state);
        Files.delete(state);
        Files.createDirectory(state);
        boolean failedSave = false;
        try { library.unfavourite("cloud-second"); } catch (IllegalStateException expected) { failedSave = true; }
        check(failedSave && library.isFavourite("cloud-second"), "Failed persistence silently changed favourite state");
        Files.delete(state);
        Files.write(state, savedState);
        try (var stream = Files.list(state.getParent())) {
            check(stream.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "Atomic save left temporary state files");
        }

        Files.writeString(state, "{\"favourites\":[\"my:" + collision + "\",\"cloud-failure\"],\"hiddenPublic\":[\"hidden\"]}");
        reload(library);
        check(library.isFavourite("my:" + collision) && library.isFavourite("cloud-failure"), "Existing library format lost favourites");
        library.unfavourite("cloud-failure");
        library.toggleFavouriteAsync("cloud-failure").get(5, TimeUnit.SECONDS);
        check(library.isFavourite("my:failure") && profiles.listProfiles().contains("failure"), "Legacy cloud favourite could not migrate to saved copy");
        check(profiles.active().equals(active) && ModuleManager.imports == imports, "Favourite workflow applied module state");
        library.close();
        library.stopped().toCompletableFuture().get(5, TimeUnit.SECONDS);
        failed(library.toggleFavouriteAsync("cloud-first"));
        System.out.println("PASS " + checks + " public config favourite checks; real profile and PNG persistence, fake cloud/game boundaries");
    }
}
