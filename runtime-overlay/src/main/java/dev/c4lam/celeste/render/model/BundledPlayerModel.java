package dev.c4lam.celeste.render.model;

import java.io.IOException;
import java.io.InputStream;

/** Optional self-contained Sahur model, read directly from the payload's resource stream. */
public final class BundledPlayerModel {
    private static final String RESOURCE = "/assets/celeste/models/sahur.glb";
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    private BundledPlayerModel() {}

    public static boolean available() {
        return BundledPlayerModel.class.getResource(RESOURCE) != null;
    }

    public static GlbModel load() throws IOException {
        try (InputStream input = BundledPlayerModel.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IOException("Tung Tung Tung Sahur is not included in this build");
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Bundled model exceeds the 8 MiB asset limit");
            return GlbImporter.parse(bytes);
        }
    }
}
