package dev.c4lam.celeste.compat;

import java.lang.invoke.MethodHandle;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.ResourceTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;

/** Retires newly cached vanilla texture views for this payload's exact verified assets. */
public final class BetaTextureLease implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final Map<Identifier, AbstractTexture> textures;
    private final Set<Identifier> assets;
    private final Set<Identifier> preexisting = new HashSet<>();
    private final BiConsumer<Identifier, AbstractTexture> destroy;
    private boolean closed;

    public static BetaTextureLease open(TextureManager manager, Set<String> verifiedEntries) {
        Set<Identifier> assets = new HashSet<>();
        for (String entry : verifiedEntries) {
            if (entry.startsWith("assets/celeste/") && entry.length() > "assets/celeste/".length()) {
                assets.add(Identifier.of("celeste", entry.substring("assets/celeste/".length())));
            }
        }
        Map<Identifier, AbstractTexture> registry = textures(manager);
        return new BetaTextureLease(registry, assets, (id, expected) -> {
            if (registry.get(id) == expected) manager.destroyTexture(id);
        });
    }

    BetaTextureLease(Map<Identifier, AbstractTexture> textures, Set<Identifier> assets,
                     BiConsumer<Identifier, AbstractTexture> destroy) {
        this.textures = textures;
        this.assets = new HashSet<>(assets);
        this.destroy = destroy;
        for (Identifier id : assets) if (textures.containsKey(id)) preexisting.add(id);
    }

    @Override public void close() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Texture retirement requires the owning client thread");
        if (closed) return;
        for (Identifier id : assets) {
            if (preexisting.contains(id)) continue;
            AbstractTexture texture = textures.get(id);
            // Preserve custom/dynamic texture objects and every pre-existing ID.
            // These exact vanilla views load their contents from our verified pack.
            if (texture != null && texture.getClass() == ResourceTexture.class
                    && ((ResourceTexture) texture).getId().equals(id)) {
                destroy.accept(id, texture);
            }
        }
        assets.clear();
        preexisting.clear();
        closed = true;
    }

    @SuppressWarnings("unchecked")
    private static Map<Identifier, AbstractTexture> textures(TextureManager manager) {
        try { return (Map<Identifier, AbstractTexture>) (Map<?, ?>) Access.TEXTURES.invokeExact(manager); }
        catch (Throwable failure) { throw NamedOrIntermediaryMembers.propagate(failure); }
    }
    private static final class Access {
        private static final MethodHandle TEXTURES = NamedOrIntermediaryMembers.getter(TextureManager.class, Map.class,
            "textures", "field_5286");
    }
}
