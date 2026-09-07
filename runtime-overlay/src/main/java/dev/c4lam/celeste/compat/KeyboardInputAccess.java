package dev.c4lam.celeste.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.client.input.Input;
import net.minecraft.util.math.Vec2f;

/** Writes only the existing protected Input movement field; no accessor interface is added. */
public final class KeyboardInputAccess {
    private KeyboardInputAccess() { }
    public static void setMovement(Input input, Vec2f value) {
        try { Access.SET.invokeExact(input, value); }
        catch (Throwable failure) { throw NamedOrIntermediaryMembers.propagate(failure); }
    }
    public static void verifyLateMember() {
        if (Access.SET == null) throw new IllegalStateException("Missing keyboard movement field");
    }
    private static final class Access {
        private static final MethodHandle SET = resolve();
        private static MethodHandle resolve() {
            Field selected = null;
            for (String name : new String[] {"movementVector", "field_55868"}) {
                try {
                    Field field = Input.class.getDeclaredField(name);
                    if (field.getType() != Vec2f.class || Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                        throw new IllegalStateException("Unexpected keyboard movement field: " + name);
                    }
                    if (selected != null) throw new IllegalStateException("Ambiguous keyboard movement field");
                    selected = field;
                } catch (NoSuchFieldException absent) {
                    // Exactly one supported namespace must resolve.
                }
            }
            if (selected == null) throw new IllegalStateException("Missing keyboard movement field");
            try { return MethodHandles.privateLookupIn(Input.class, MethodHandles.lookup()).unreflectSetter(selected); }
            catch (IllegalAccessException failure) { throw new IllegalStateException("Keyboard movement field is inaccessible", failure); }
        }
    }
}
