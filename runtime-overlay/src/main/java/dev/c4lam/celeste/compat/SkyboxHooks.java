package dev.c4lam.celeste.compat;

import dev.c4lam.celeste.module.impl.visual.expansion.SkyboxFeature;
import dev.c4lam.celeste.module.impl.visual.expansion.SkyboxRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.state.SkyRenderState;
import net.minecraft.client.world.ClientWorld;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Shared by startup mixins and late hooks; adds no state to Minecraft renderer classes. */
public final class SkyboxHooks {
    private static final ReferenceQueue<Object> RETIRED_RENDERERS = new ReferenceQueue<>();
    private static final Map<RendererKey, FrameState> FRAMES = new HashMap<>();

    private SkyboxHooks() { }

    /** Extraction starts a new frame, which owns no vanilla sky layers until a draw succeeds. */
    public static synchronized void extract(Object renderer, ClientWorld world, Camera camera, SkyRenderState state) {
        FrameState frame = frame(renderer);
        frame.drawn = false;
        frame.value = SkyboxFeature.extract(world, camera, state);
    }

    /** True only when this renderer's current replacement actually reached the render target. */
    public static synchronized boolean draw(Object renderer) {
        FrameState frame = frame(renderer);
        frame.drawn = SkyboxRenderer.draw(frame.value);
        return frame.drawn;
    }

    /** A disabled module or changed world must immediately release the remaining vanilla layers. */
    public static synchronized boolean ownsSky(Object renderer) {
        Objects.requireNonNull(renderer, "renderer");
        retireCollectedRenderers();
        FrameState frame = FRAMES.get(new RendererKey(renderer));
        return frame != null && frame.drawn && SkyboxFeature.isCurrent(frame.value);
    }

    /** Retire all extracted frames when the owning Celeste runtime closes. */
    public static synchronized void clear() {
        FRAMES.clear();
        retireCollectedRenderers();
    }

    private static FrameState frame(Object renderer) {
        Objects.requireNonNull(renderer, "renderer");
        retireCollectedRenderers();
        RendererKey lookup = new RendererKey(renderer);
        FrameState frame = FRAMES.get(lookup);
        if (frame == null) {
            frame = new FrameState();
            FRAMES.put(new RendererKey(renderer, RETIRED_RENDERERS), frame);
        }
        return frame;
    }

    private static void retireCollectedRenderers() {
        RendererKey retired;
        while ((retired = (RendererKey) RETIRED_RENDERERS.poll()) != null) FRAMES.remove(retired);
    }

    private static final class FrameState {
        private SkyboxRenderer.Frame value = SkyboxRenderer.Frame.EMPTY;
        private boolean drawn;
    }

    /** WeakHashMap would use renderer.equals(); these keys intentionally use object identity. */
    private static final class RendererKey extends WeakReference<Object> {
        private final int identityHash;

        private RendererKey(Object renderer) {
            super(renderer);
            identityHash = System.identityHashCode(renderer);
        }

        private RendererKey(Object renderer, ReferenceQueue<Object> queue) {
            super(renderer, queue);
            identityHash = System.identityHashCode(renderer);
        }

        @Override public int hashCode() { return identityHash; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            Object renderer = get();
            return renderer != null && other instanceof RendererKey key && renderer == key.get();
        }
    }
}
