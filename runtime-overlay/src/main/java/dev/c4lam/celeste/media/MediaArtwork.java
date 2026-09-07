package dev.c4lam.celeste.media;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.c4lam.celeste.CelesteClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Bounded asynchronous decoder and one-entry GPU cache for system-media art.
 * Byte/Base64/STB work stays off the render thread; only texture registration
 * and destruction are scheduled onto Minecraft's render/client executor.
 */
public final class MediaArtwork implements AutoCloseable {
    private static final Logger LOGGER = dev.c4lam.celeste.compat.RuntimeLogging.getLogger("Celeste/MediaArtwork");
    private static final MediaArtwork INSTANCE = new MediaArtwork();
    private CompletableFuture<Void> stopCompletion;
    private static final Identifier TEXTURE_ID = Identifier.of(CelesteClient.MOD_ID, "dynamic/media_artwork");
    private static final int MAX_ENCODED_CHARS = 7_000_000;
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_DIMENSION = 2_048;
    private static final long MAX_PIXELS = 4_194_304L;

    private final ExecutorService decoder = new ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1),
        runnable -> {
            Thread thread = new Thread(runnable, "Celeste Media Artwork");
            thread.setDaemon(true);
            thread.setContextClassLoader(ClassLoader.getPlatformClassLoader());
            return thread;
        },
        new ThreadPoolExecutor.DiscardOldestPolicy()
    );

    private String observedKey = "";
    private int observedPayloadHash;
    private int observedPayloadLength = -1;
    private long generation;
    private boolean ready;
    private boolean registered;
    private boolean closed;

    private MediaArtwork() {
    }

    public static MediaArtwork getInstance() {
        return INSTANCE;
    }

    /** Returns the current dynamic texture, or {@code null} for the glyph fallback. */
    public synchronized Identifier texture(MediaPlaybackSnapshot snapshot) {
        if (closed || !RenderSystem.isOnRenderThread()) {
            return null;
        }

        String key = snapshot == null || !snapshot.available() ? "" : safe(snapshot.artworkKey());
        String payload = snapshot == null || !snapshot.available() ? "" : safe(snapshot.artworkBase64());
        if (!key.equals(observedKey)) {
            ++generation;
            if (registered) {
                MinecraftClient.getInstance().getTextureManager().destroyTexture(TEXTURE_ID);
                registered = false;
            }
            observedKey = key;
            observedPayloadHash = 0;
            observedPayloadLength = -1;
            ready = false;
        }
        if (key.isBlank() || payload.isBlank()) {
            return null;
        }

        int payloadHash = payload.hashCode();
        int payloadLength = payload.length();
        if (payloadHash != observedPayloadHash || payloadLength != observedPayloadLength) {
            if (registered) {
                MinecraftClient.getInstance().getTextureManager().destroyTexture(TEXTURE_ID);
                registered = false;
            }
            observedPayloadHash = payloadHash;
            observedPayloadLength = payloadLength;
            ready = false;
            long request = ++generation;
            try {
                decoder.execute(() -> decode(request, key, payload));
            } catch (RejectedExecutionException ignored) {
                // Shutdown raced the last HUD render; glyph fallback remains valid.
            }
        }
        return ready ? TEXTURE_ID : null;
    }

    private void decode(long request, String key, String payload) {
        NativeImage image = decodeImage(payload);
        if (image == null) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        try {
            client.execute(() -> install(request, key, image));
        } catch (RejectedExecutionException exception) {
            image.close();
        }
    }

    private NativeImage decodeImage(String payload) {
        if (payload.length() > MAX_ENCODED_CHARS) {
            return null;
        }

        byte[] encoded;
        try {
            encoded = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException exception) {
            LOGGER.debug("System media supplied malformed artwork Base64", exception);
            return null;
        }
        if (encoded.length == 0 || encoded.length > MAX_BYTES || !safeDimensions(inspectDimensions(encoded))) {
            return null;
        }

        NativeImage image = null;
        try {
            image = NativeImage.read(encoded);
            if (!safeDimensions(new Dimensions(image.getWidth(), image.getHeight()))) {
                image.close();
                return null;
            }
            return image;
        } catch (IOException | RuntimeException exception) {
            if (image != null) {
                image.close();
            }
            LOGGER.debug("Could not decode system media artwork", exception);
            return null;
        }
    }

    private void install(long request, String key, NativeImage image) {
        RenderSystem.assertOnRenderThread();
        synchronized (this) {
            if (closed || request != generation || !key.equals(observedKey)) {
                image.close();
                return;
            }

            NativeImageBackedTexture texture = null;
            try {
                texture = new NativeImageBackedTexture(() -> "Celeste system media artwork", image);
                TextureManager manager = MinecraftClient.getInstance().getTextureManager();
                manager.registerTexture(TEXTURE_ID, texture);
                texture = null; // TextureManager owns it now.
                registered = true;
                ready = true;
            } catch (RuntimeException exception) {
                if (texture != null) {
                    texture.close();
                } else {
                    image.close();
                }
                ready = false;
                LOGGER.debug("Could not upload system media artwork", exception);
            }
        }
    }

    private static Dimensions inspectDimensions(byte[] bytes) {
        if (isPng(bytes)) {
            return bytes.length >= 24
                ? new Dimensions(readIntBigEndian(bytes, 16), readIntBigEndian(bytes, 20))
                : Dimensions.INVALID;
        }
        if (isJpeg(bytes)) {
            return inspectJpeg(bytes);
        }
        if (bytes.length >= 10
            && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F'
            && bytes[3] == '8' && (bytes[4] == '7' || bytes[4] == '9') && bytes[5] == 'a') {
            return new Dimensions(readUnsignedShortLittleEndian(bytes, 6), readUnsignedShortLittleEndian(bytes, 8));
        }
        return Dimensions.INVALID;
    }

    private static Dimensions inspectJpeg(byte[] bytes) {
        int position = 2;
        while (position + 3 < bytes.length) {
            while (position < bytes.length && unsigned(bytes[position]) != 0xFF) position++;
            while (position < bytes.length && unsigned(bytes[position]) == 0xFF) position++;
            if (position >= bytes.length) break;

            int marker = unsigned(bytes[position++]);
            if (marker == 0xD8 || marker == 0x01 || marker >= 0xD0 && marker <= 0xD7) continue;
            if (marker == 0xD9 || marker == 0xDA || position + 1 >= bytes.length) break;

            int length = readUnsignedShortBigEndian(bytes, position);
            if (length < 2 || position + length > bytes.length) break;
            if (isStartOfFrame(marker) && length >= 7) {
                return new Dimensions(
                    readUnsignedShortBigEndian(bytes, position + 5),
                    readUnsignedShortBigEndian(bytes, position + 3)
                );
            }
            position += length;
        }
        return Dimensions.INVALID;
    }

    private static boolean isStartOfFrame(int marker) {
        return marker >= 0xC0 && marker <= 0xCF
            && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
    }

    private static boolean isPng(byte[] bytes) {
        return bytes.length >= 24
            && unsigned(bytes[0]) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
            && unsigned(bytes[4]) == 0x0D && unsigned(bytes[5]) == 0x0A
            && unsigned(bytes[6]) == 0x1A && unsigned(bytes[7]) == 0x0A
            && bytes[12] == 'I' && bytes[13] == 'H' && bytes[14] == 'D' && bytes[15] == 'R';
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes.length >= 4 && unsigned(bytes[0]) == 0xFF && unsigned(bytes[1]) == 0xD8;
    }

    private static boolean safeDimensions(Dimensions dimensions) {
        return dimensions.width > 0 && dimensions.height > 0
            && dimensions.width <= MAX_DIMENSION && dimensions.height <= MAX_DIMENSION
            && (long) dimensions.width * dimensions.height <= MAX_PIXELS;
    }

    @Override
    public void close() {
        long request;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            request = ++generation;
            ready = false;
            registered = false;
            observedKey = "";
            observedPayloadHash = 0;
            observedPayloadLength = -1;
        }
        decoder.shutdownNow();
        MinecraftClient client = MinecraftClient.getInstance();
        Runnable cleanup = () -> {
            RenderSystem.assertOnRenderThread();
            synchronized (this) {
                if (closed && request == generation) {
                    client.getTextureManager().destroyTexture(TEXTURE_ID);
                }
            }
        };
        if (RenderSystem.isOnRenderThread()) {
            cleanup.run();
        } else {
            try {
                client.execute(cleanup);
            } catch (RejectedExecutionException ignored) {
            }
        }
    }

    /** The render barrier follows decoder exit, so no delayed install can survive successful Stop. */
    public synchronized CompletionStage<Void> stopped() {
        if (!closed) return CompletableFuture.failedFuture(new IllegalStateException("Media artwork is not closed"));
        if (stopCompletion == null) {
            MinecraftClient client = MinecraftClient.getInstance();
            stopCompletion = CompletableFuture.runAsync(() -> {
                try {
                    if (!decoder.awaitTermination(30L, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Media artwork decoder did not finish stopping within 30 seconds");
                    }
                    CompletableFuture<Void> drained = new CompletableFuture<>();
                    client.execute(() -> drained.complete(null));
                    // Do not start the JVM-global timeout scheduler from a disposable payload domain.
                    drained.get(30L, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Media artwork shutdown observation was interrupted", interrupted);
                } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
                    throw new IllegalStateException("Media artwork render cleanup did not finish within 30 seconds", failure);
                }
            });
        }
        return stopCompletion.minimalCompletionStage();
    }
    private static int readIntBigEndian(byte[] bytes, int offset) {
        return unsigned(bytes[offset]) << 24 | unsigned(bytes[offset + 1]) << 16
            | unsigned(bytes[offset + 2]) << 8 | unsigned(bytes[offset + 3]);
    }

    private static int readUnsignedShortBigEndian(byte[] bytes, int offset) {
        return unsigned(bytes[offset]) << 8 | unsigned(bytes[offset + 1]);
    }

    private static int readUnsignedShortLittleEndian(byte[] bytes, int offset) {
        return unsigned(bytes[offset]) | unsigned(bytes[offset + 1]) << 8;
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private record Dimensions(int width, int height) {
        private static final Dimensions INVALID = new Dimensions(0, 0);
    }
}
