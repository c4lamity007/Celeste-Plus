package dev.c4lam.celeste.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Fixed 1.21.11 member names, resolved once without altering the target class. */
final class NamedOrIntermediaryMembers {
    private NamedOrIntermediaryMembers() { }

    static MethodHandle getter(Class<?> owner, Class<?> type, String named, String intermediary) {
        Field selected = null;
        for (String name : names(named, intermediary)) {
            try {
                Field field = owner.getDeclaredField(name);
                if (field.getType() != type || Modifier.isStatic(field.getModifiers())) {
                    throw new IllegalStateException("Unexpected Minecraft field type: " + owner.getName() + "." + name);
                }
                if (selected != null) throw new IllegalStateException("Ambiguous Minecraft field: " + owner.getName());
                selected = field;
            } catch (NoSuchFieldException absent) {
                // Exactly one of the supported namespaces must resolve.
            }
        }
        if (selected == null) throw new IllegalStateException("Missing Minecraft field: " + owner.getName() + "." + named);
        try {
            return MethodHandles.privateLookupIn(owner, MethodHandles.lookup()).unreflectGetter(selected);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Minecraft field is not accessible: " + owner.getName() + "." + named, failure);
        }
    }

    static MethodHandle method(Class<?> owner, Class<?> result, String named, String intermediary, Class<?>... parameters) {
        Method selected = null;
        for (String name : names(named, intermediary)) {
            try {
                Method method = owner.getDeclaredMethod(name, parameters);
                if (method.getReturnType() != result || Modifier.isStatic(method.getModifiers())) {
                    throw new IllegalStateException("Unexpected Minecraft method type: " + owner.getName() + "." + name);
                }
                if (selected != null) throw new IllegalStateException("Ambiguous Minecraft method: " + owner.getName());
                selected = method;
            } catch (NoSuchMethodException absent) {
                // Overloads are selected by their exact runtime parameter types.
            }
        }
        if (selected == null) throw new IllegalStateException("Missing Minecraft method: " + owner.getName() + "." + named);
        try {
            return MethodHandles.privateLookupIn(owner, MethodHandles.lookup()).unreflect(selected);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Minecraft method is not accessible: " + owner.getName() + "." + named, failure);
        }
    }

    private static String[] names(String named, String intermediary) {
        if (named == null || named.isEmpty() || intermediary == null || intermediary.isEmpty()) {
            throw new IllegalArgumentException("Both supported member names are required");
        }
        return named.equals(intermediary) ? new String[] {named} : new String[] {named, intermediary};
    }

    static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new IllegalStateException("Minecraft member invocation failed", failure);
    }
}
