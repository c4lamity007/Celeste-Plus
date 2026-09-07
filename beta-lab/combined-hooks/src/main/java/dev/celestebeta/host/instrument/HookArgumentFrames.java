package dev.celestebeta.host.instrument;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Keeps unchanged reference arguments live when a late hook extends their use. */
public final class HookArgumentFrames {
    private static final Object WIDE_SECOND = new Object();
    private HookArgumentFrames() { }

    /**
     * Repairs expanded frame metadata only. Selected locals must be unchanged
     * receiver/reference arguments; no game classes are loaded or resolved.
     */
    public static void retain(String owner, MethodNode method, int... referenceSlots) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(referenceSlots, "referenceSlots");
        if (referenceSlots.length == 0) return;
        if (method.name.equals("<init>")) throw reject("uninitialized constructor receiver");
        Map<Integer, String> arguments = new LinkedHashMap<>();
        int slot = 0;
        if ((method.access & Opcodes.ACC_STATIC) == 0) arguments.put(slot++, owner);
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            if (argument.getSort() == Type.OBJECT || argument.getSort() == Type.ARRAY) {
                arguments.put(slot, argument.getInternalName());
            }
            slot += argument.getSize();
        }
        Map<Integer, String> retained = new LinkedHashMap<>();
        for (int selected : referenceSlots) {
            String type = arguments.get(selected);
            if (type == null || selected >= method.maxLocals) throw reject("not a reference argument slot: " + selected);
            retained.put(selected, type);
        }
        for (var instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            if (instruction instanceof VarInsnNode variable && opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) {
                int width = opcode == Opcodes.LSTORE || opcode == Opcodes.DSTORE ? 2 : 1;
                for (int selected : retained.keySet()) {
                    if (selected >= variable.var && selected < variable.var + width) {
                        throw reject("argument slot overwritten: " + selected);
                    }
                }
            } else if (instruction instanceof IincInsnNode increment && retained.containsKey(increment.var)) {
                throw reject("argument slot incremented: " + increment.var);
            }
        }
        // Stage every update first, so a rejected frame never leaves a partial repair.
        Map<FrameNode, List<Object>> repairs = new LinkedHashMap<>();
        for (var instruction : method.instructions) {
            if (!(instruction instanceof FrameNode frame)) continue;
            if (frame.type != Opcodes.F_NEW) throw reject("frame was not expanded");
            List<Object> locals = new ArrayList<>();
            if (frame.local != null) for (Object value : frame.local) {
                locals.add(value);
                if (wide(value)) locals.add(WIDE_SECOND);
            }
            if (locals.size() > method.maxLocals) throw reject("frame exceeds method locals");
            for (var selected : retained.entrySet()) {
                int index = selected.getKey();
                while (locals.size() <= index) locals.add(Opcodes.TOP);
                Object value = locals.get(index);
                if (!(value instanceof String) && !Opcodes.TOP.equals(value) && !Opcodes.NULL.equals(value)) {
                    throw reject("non-reference frame value at argument slot: " + index);
                }
                // The original argument remains in this slot on every path. Its
                // descriptor type is valid even where the old frame widened or
                // discarded that reference after vanilla's final use.
                locals.set(index, selected.getValue());
            }
            List<Object> entries = new ArrayList<>();
            for (Object value : locals) if (value != WIDE_SECOND) entries.add(value);
            repairs.put(frame, entries);
        }
        repairs.forEach((frame, locals) -> frame.local = locals);
    }
    private static boolean wide(Object value) { return Opcodes.LONG.equals(value) || Opcodes.DOUBLE.equals(value); }
    private static IllegalArgumentException reject(String reason) {
        return new IllegalArgumentException("Hook argument frame retention rejected: " + reason);
    }
}