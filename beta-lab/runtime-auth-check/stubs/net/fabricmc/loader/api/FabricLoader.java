package net.fabricmc.loader.api;

import java.nio.file.Path;

/** Offline fixture: default game/config lookup must never touch a user profile. */
public final class FabricLoader {
    private FabricLoader() { }
    public static FabricLoader getInstance() {
        throw new AssertionError("Credential checks must use their explicit fixture path");
    }
    public Path getConfigDir() {
        throw new AssertionError("No Minecraft profile is available in this fixture");
    }
}
