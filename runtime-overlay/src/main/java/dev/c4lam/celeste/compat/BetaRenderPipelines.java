package dev.c4lam.celeste.compat;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.util.Identifier;

/** Tracks registrations owned by a late payload; registry operations run on the client thread. */
public final class BetaRenderPipelines {
    private static Lease active;
    private static boolean retired;
    private BetaRenderPipelines() { }

    /** Open before initializing any payload pipeline class; a retired payload cannot reopen. */
    public static synchronized Lease open() {
        if (retired || active != null) throw new IllegalStateException("Payload pipeline lease is unavailable");
        active = new Lease(registry());
        return active;
    }

    /** Startup registration is unchanged until a late-payload lease is opened. */
    public static synchronized RenderPipeline register(RenderPipeline pipeline) {
        Objects.requireNonNull(pipeline, "pipeline");
        if (retired) throw new IllegalStateException("Payload render pipelines have been retired");
        Lease lease = active;
        Identifier id = pipeline.getLocation();
        if (lease != null && id.getNamespace().equals("celeste")) {
            RenderPipeline previous = lease.registry.get(id);
            Registration tracked = lease.owned.get(id);
            if (tracked != null && previous == tracked.pipeline) previous = tracked.previous;
            // Record before invoking the original method, including a partial
            // registration if that invocation unexpectedly throws afterward.
            lease.owned.put(id, new Registration(previous, pipeline));
        }
        try {
            return (RenderPipeline) Access.REGISTER.invokeExact(pipeline);
        } catch (Throwable failure) {
            throw NamedOrIntermediaryMembers.propagate(failure);
        }
    }

    public static final class Lease implements AutoCloseable {
        private final Map<Identifier, RenderPipeline> registry;
        private final Map<Identifier, Registration> owned = new LinkedHashMap<>();
        private boolean closed;
        private Lease(Map<Identifier, RenderPipeline> registry) { this.registry = registry; }

        /** Quiesce callbacks first, then close before removing assets and reloading resources. */
        @Override public void close() {
            synchronized (BetaRenderPipelines.class) {
                if (closed) return;
                retired = true;
                for (var iterator = owned.entrySet().iterator(); iterator.hasNext();) {
                    var entry = iterator.next();
                    Registration registration = entry.getValue();
                    // Never remove a later replacement installed by another owner.
                    if (registry.get(entry.getKey()) == registration.pipeline) {
                        if (registration.previous == null) registry.remove(entry.getKey());
                        else registry.put(entry.getKey(), registration.previous);
                    }
                    iterator.remove();
                }
                closed = true;
                if (active == this) active = null;
            }
        }
    }

    private record Registration(RenderPipeline previous, RenderPipeline pipeline) { }

    @SuppressWarnings("unchecked")
    private static Map<Identifier, RenderPipeline> registry() {
        try { return (Map<Identifier, RenderPipeline>) (Map<?, ?>) Access.REGISTRY.invokeExact(); }
        catch (Throwable failure) { throw NamedOrIntermediaryMembers.propagate(failure); }
    }
    private static final class Access {
        private static final MethodHandle REGISTRY = registryGetter();
        private static final MethodHandle REGISTER = registerMethod();
        private static MethodHandle registryGetter() {
            Field selected = null;
            for (String name : new String[] {"PIPELINES", "field_56845"}) {
                try {
                    Field field = RenderPipelines.class.getDeclaredField(name);
                    if (field.getType() != Map.class || !Modifier.isStatic(field.getModifiers()) || !Modifier.isFinal(field.getModifiers())) {
                        throw new IllegalStateException("Unexpected render pipeline registry field");
                    }
                    if (selected != null) throw new IllegalStateException("Ambiguous render pipeline registry field");
                    selected = field;
                } catch (NoSuchFieldException absent) { /* Try the other fixed namespace. */ }
            }
            if (selected == null) throw new IllegalStateException("Missing render pipeline registry field");
            try { return MethodHandles.privateLookupIn(RenderPipelines.class, MethodHandles.lookup()).unreflectGetter(selected); }
            catch (IllegalAccessException failure) { throw new IllegalStateException("Render pipeline registry is inaccessible", failure); }
        }
        private static MethodHandle registerMethod() {
            Method selected = null;
            for (String name : new String[] {"register", "method_67887"}) {
                try {
                    Method method = RenderPipelines.class.getDeclaredMethod(name, RenderPipeline.class);
                    if (method.getReturnType() != RenderPipeline.class || !Modifier.isStatic(method.getModifiers())) {
                        throw new IllegalStateException("Unexpected render pipeline registration method");
                    }
                    if (selected != null) throw new IllegalStateException("Ambiguous render pipeline registration method");
                    selected = method;
                } catch (NoSuchMethodException absent) { /* Try the other fixed namespace. */ }
            }
            if (selected == null) throw new IllegalStateException("Missing render pipeline registration method");
            try { return MethodHandles.privateLookupIn(RenderPipelines.class, MethodHandles.lookup()).unreflect(selected); }
            catch (IllegalAccessException failure) { throw new IllegalStateException("Render pipeline registration is inaccessible", failure); }
        }
    }
}