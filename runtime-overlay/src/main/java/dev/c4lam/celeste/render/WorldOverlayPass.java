package dev.c4lam.celeste.render;

import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.util.Identifier;

/** Transparent Celeste overlays are composited after the world's depth-based atmosphere. */
public final class WorldOverlayPass {
    private static final Identifier OVERLAYS = Identifier.of("celeste", "world_overlays/after_fog");
    // Keep the existing label phase identity for detachable callbacks across reinjection.
    private static final Identifier LABELS = Identifier.of("celeste", "sky_lyrics/after_fog");

    private WorldOverlayPass() { }

    public static void register(String name, WorldRenderEvents.EndMain draw) {
        // Fog samples terrain depth. Applying it after a transparent overlay would
        // incorrectly shade that overlay using the distance to the terrain behind it.
        WorldRenderEvents.END_MAIN.addPhaseOrdering(Event.DEFAULT_PHASE, OVERLAYS);
        WorldRenderEvents.END_MAIN.addPhaseOrdering(OVERLAYS, LABELS);
        BetaSubscriptions.register(WorldRenderEvents.END_MAIN, name,
            WorldRenderEvents.EndMain.class, draw,
            proxy -> WorldRenderEvents.END_MAIN.register(OVERLAYS, proxy));
    }
}
