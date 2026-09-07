package dev.c4lam.celeste.compat;

import java.lang.invoke.MethodHandle;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.world.WorldEventHandler;
import net.minecraft.entity.ItemEntity;

/** Cached access to two original Minecraft members; no target accessor or invoker is installed. */
public final class FakeGambleAccess {
    private FakeGambleAccess() { }
    public static ClientWorld world(WorldEventHandler handler) {
        try { return (ClientWorld) Access.WORLD.invokeExact(handler); }
        catch (Throwable failure) { throw NamedOrIntermediaryMembers.propagate(failure); }
    }
    public static void tryMerge(ItemEntity entity) {
        try { Access.MERGE.invokeExact(entity); }
        catch (Throwable failure) { throw NamedOrIntermediaryMembers.propagate(failure); }
    }
    public static void verifyLateMembers() {
        if (Access.WORLD == null || Access.MERGE == null) throw new IllegalStateException("Missing local inventory simulation members");
    }
    private static final class Access {
        static final MethodHandle WORLD = NamedOrIntermediaryMembers.getter(WorldEventHandler.class, ClientWorld.class, "world", "field_53069");
        static final MethodHandle MERGE = NamedOrIntermediaryMembers.method(ItemEntity.class, void.class, "tryMerge", "method_6973");
    }
}
