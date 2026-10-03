package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;

import net.minecraft.block.BlockState;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only, bounded observations of server updates, never client block prediction. */
public final class AuraServerState {
    private static final int LIMIT = 1024;
    private static final Map<BlockPos, BlockObservation> BLOCKS = new LinkedHashMap<>();
    private static final Map<Integer, EntityObservation> ENTITIES = new LinkedHashMap<>();
    private static final Map<BlockPos, Long> UNCONFIRMED = new LinkedHashMap<>();
    private static ClientWorld world;
    private static long revision;
    static {
        BetaSubscriptions.register(ClientPlayConnectionEvents.DISCONNECT, "celeste:aura/disconnect",
            ClientPlayConnectionEvents.Disconnect.class, (handler, client) -> clear(), ClientPlayConnectionEvents.DISCONNECT::register);
    }

    private AuraServerState() { }
    public record BlockObservation(long revision, BlockState state) { }
    public record EntityObservation(long revision, boolean present) { }

    public static long revision(ClientWorld current) { ensureWorld(current); return revision; }
    public static BlockObservation block(ClientWorld current, BlockPos pos) { ensureWorld(current); return BLOCKS.get(pos); }
    public static EntityObservation entity(ClientWorld current, int id) { ensureWorld(current); return ENTITIES.get(id); }

    public static boolean canPredict(ClientWorld current) { ensureWorld(current); return UNCONFIRMED.size() < LIMIT; }
    public static void predict(ClientWorld current, BlockPos pos, long sentRevision) {
        ensureWorld(current);
        if (current != null && pos != null && UNCONFIRMED.size() < LIMIT) UNCONFIRMED.put(pos.toImmutable(), sentRevision);
    }
    public static boolean blockReady(ClientWorld current, BlockPos pos) {
        ensureWorld(current);
        Long sent = UNCONFIRMED.get(pos);
        if (sent == null) return true;
        BlockObservation observed = BLOCKS.get(pos);
        if (observed == null || observed.revision() <= sent || !observed.state().equals(current.getBlockState(pos))) return false;
        UNCONFIRMED.remove(pos);
        return true;
    }

    public static void observeBlock(ClientWorld current, BlockPos pos, BlockState state) {
        ensureWorld(current);
        if (current == null || pos == null || state == null) return;
        BLOCKS.remove(pos);
        BLOCKS.put(pos.toImmutable(), new BlockObservation(++revision, state));
        trim(BLOCKS);
    }

    public static void observeEntity(ClientWorld current, int id, boolean present) {
        ensureWorld(current);
        if (current == null) return;
        ENTITIES.remove(id);
        ENTITIES.put(id, new EntityObservation(++revision, present));
        trim(ENTITIES);
    }

    public static void clear() { world = null; revision = 0; BLOCKS.clear(); ENTITIES.clear(); UNCONFIRMED.clear(); }

    private static void ensureWorld(ClientWorld current) {
        if (world != current) { clear(); world = current; }
    }

    private static <K, V> void trim(Map<K, V> values) {
        if (values.size() > LIMIT) values.remove(values.keySet().iterator().next());
    }
}
