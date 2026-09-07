package dev.c4lam.celeste.beta.resources;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Set;
import net.minecraft.resource.AbstractFileResourcePack;
import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.ResourcePackInfo;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

/** A reload-local view of immutable bytes, with no paths, ZIPs, or extraction. */
public final class MemoryResourcePack extends AbstractFileResourcePack {
    private volatile MemoryResourceBytes store;

    MemoryResourcePack(ResourcePackInfo info, MemoryResourceBytes store) {
        super(info);
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public InputSupplier<InputStream> openRoot(String... segments) {
        Objects.requireNonNull(segments, "segments");
        // Root lookup is intentionally not a second route into assets/.
        if (segments.length != 1 || !("pack.mcmeta".equals(segments[0])
                || "pack.png".equals(segments[0]))) {
            return null;
        }
        return supplier(segments[0]);
    }

    @Override
    public InputSupplier<InputStream> open(ResourceType type, Identifier id) {
        Objects.requireNonNull(id, "id");
        return type == ResourceType.CLIENT_RESOURCES
                ? supplier("assets/" + id.getNamespace() + "/" + id.getPath()) : null;
    }

    @Override
    public void findResources(ResourceType type, String namespace, String prefix,
                              ResultConsumer consumer) {
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(prefix, "prefix");
        MemoryResourceBytes current = store;
        if (type != ResourceType.CLIENT_RESOURCES || current == null
                || !"celeste".equals(namespace)
                || (!prefix.isEmpty() && !MemoryResourceBytes.safePath(prefix))) {
            return;
        }
        String directory = prefix.isEmpty() ? "" : prefix + "/";
        // The sorted identifiers are built once, not per lookup or per frame.
        for (Identifier id : current.identifiers()) {
            if (id.getPath().startsWith(directory)) {
                InputSupplier<InputStream> found = open(type, id);
                if (found != null) {
                    consumer.accept(id, found);
                }
            }
        }
    }

    @Override
    public Set<String> getNamespaces(ResourceType type) {
        MemoryResourceBytes current = store;
        return type == ResourceType.CLIENT_RESOURCES && current != null
                && !current.identifiers().isEmpty() ? Set.of("celeste") : Set.of();
    }

    private InputSupplier<InputStream> supplier(String name) {
        MemoryResourceBytes current = store;
        if (current == null || current.bytes(name) == null) {
            return null;
        }
        // Capture the view/name, not an array: an unused supplier cannot keep
        // payload bytes alive after view/internal-layer close.
        return () -> {
            MemoryResourceBytes live = store;
            byte[] bytes = live == null ? null : live.bytes(name);
            if (bytes == null) {
                throw new FileNotFoundException("Memory resource is no longer available: " + name);
            }
            return new ReleasingInputStream(bytes);
        };
    }

    boolean belongsTo(MemoryResourceBytes candidate) {
        return store == candidate;
    }

    /** Closing one reload view does not close other views or the internal layer. */
    @Override
    public void close() {
        store = null;
    }

    /** No data copy on open; close releases this stream's reference to the array. */
    private static final class ReleasingInputStream extends ByteArrayInputStream {
        ReleasingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public synchronized void close() {
            buf = new byte[0];
            pos = 0;
            count = 0;
            mark = 0;
        }
    }
}
