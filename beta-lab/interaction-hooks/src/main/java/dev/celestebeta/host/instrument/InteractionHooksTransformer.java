package dev.celestebeta.host.instrument;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.function.BiPredicate;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

/** Original hooks with paired breaking cleanup on all exits, and no added members. */
public final class InteractionHooksTransformer {
    private static final String BRIDGE = "dev/celestebeta/host/InteractionHookBridge";
    private static final String ONE = "(Ljava/lang/Object;)V";
    private static final String TWO = "(Ljava/lang/Object;Ljava/lang/Object;)V";
    private static final String THREE = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V";

    private InteractionHooksTransformer() { }

    public static byte[] prepare(String owner, byte[] bytes) {
        return prepare(owner, bytes, (method, returned) -> false);
    }

    public static byte[] prepare(String owner, byte[] bytes, BiPredicate<MethodNode, AbstractInsnNode> cancellationReturn) {
        boolean named = owner.equals("net/minecraft/client/network/ClientPlayerInteractionManager");
        if (!named && !owner.equals("net/minecraft/class_636")) throw reject("unsupported class");
        ClassReader reader = new ClassReader(bytes);
        if (!reader.getClassName().equals(owner)) throw reject("class identity mismatch");
        ClassNode node = new ClassNode(Opcodes.ASM9);
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        for (MethodNode method : node.methods) {
            if (method.name.startsWith("celeste$")) throw reject("startup Celeste hooks already exist");
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.owner.equals(BRIDGE)) {
                throw reject("interaction hooks already prepared");
            }
        }
        String pos = named ? "net/minecraft/util/math/BlockPos" : "net/minecraft/class_2338";
        String direction = named ? "net/minecraft/util/math/Direction" : "net/minecraft/class_2350";
        String player = named ? "net/minecraft/entity/player/PlayerEntity" : "net/minecraft/class_1657";
        String entity = named ? "net/minecraft/entity/Entity" : "net/minecraft/class_1297";
        String hand = named ? "net/minecraft/util/Hand" : "net/minecraft/class_1268";
        String result = named ? "net/minecraft/util/ActionResult" : "net/minecraft/class_1269";
        List<Target> targets = List.of(
            new Target(named ? "syncSelectedSlot" : "method_2911", "()V", 0, Opcodes.RETURN),
            new Target(named ? "attackBlock" : "method_2910", "(L" + pos + ";L" + direction + ";)Z", 1, Opcodes.IRETURN),
            new Target(named ? "updateBlockBreakingProgress" : "method_2902", "(L" + pos + ";L" + direction + ";)Z", 1, Opcodes.IRETURN),
            new Target(named ? "attackEntity" : "method_2918", "(L" + player + ";L" + entity + ";)V", 2, Opcodes.RETURN),
            new Target(named ? "interactItem" : "method_2919", "(L" + player + ";L" + hand + ";)L" + result + ";", 3, Opcodes.ARETURN));
        Map<String, MethodNode> edits = new LinkedHashMap<>();
        for (Target target : targets) {
            List<MethodNode> matches = node.methods.stream().filter(method -> method.name.equals(target.name) && method.desc.equals(target.desc)).toList();
            if (matches.size() != 1) throw reject("expected one " + target.name + target.desc);
            MethodNode method = matches.getFirst();
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE)) != 0) throw reject("unsupported target shape");
            int returns = 0;
            for (AbstractInsnNode instruction : method.instructions) {
                int opcode = instruction.getOpcode();
                if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                    if (opcode != target.returnOpcode) throw reject("wrong return type");
                    returns++;
                }
                int protectedLocals = target.kind == 2 ? 3 : 1;
                if (target.kind != 0 && (instruction instanceof VarInsnNode variable && variable.var < protectedLocals
                        && opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE
                        || instruction instanceof IincInsnNode increment && increment.var < protectedLocals)) {
                    throw reject("callback argument overwritten");
                }
            }
            if (returns == 0) throw reject("no normal return");
            if (target.kind == 0) selectedSlotCall(method, named);
            edits.put(target.name + target.desc, method);
        }
        for (Target target : targets) {
            MethodNode method = edits.get(target.name + target.desc);
            if (target.kind == 0) {
                MethodInsnNode call = selectedSlotCall(method, named);
                method.instructions.insertBefore(call, new InsnNode(Opcodes.DUP));
                method.instructions.insert(call, new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "selectedSlot", "(Ljava/lang/Object;I)I", false));
                method.maxStack++;
                continue;
            }
            InsnList head = new InsnList();
            head.add(new VarInsnNode(Opcodes.ALOAD, 0));
            if (target.kind > 1) head.add(new VarInsnNode(Opcodes.ALOAD, 1));
            if (target.kind == 3) head.add(new VarInsnNode(Opcodes.ALOAD, 2));
            head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
                target.kind == 1 ? "beginBreaking" : target.kind == 2 ? "beforeAttack" : "beforeItem",
                target.kind == 1 ? ONE : target.kind == 2 ? TWO : THREE, false));
            if (target.kind == 1) {
                // Every protected instruction must keep the receiver valid for
                // the shared normal/exceptional cleanup, even after a chop frame.
                HookArgumentFrames.retain(owner, method, 0);
                addBreakingFinally(owner, method, head);
                continue;
            }
            // Vanilla frames may discard arguments after their last original use.
            // The return callbacks extend the receiver/target lifetimes.
            if (target.kind == 2) HookArgumentFrames.retain(owner, method, 0, 2);
            else HookArgumentFrames.retain(owner, method, 0);
            method.instructions.insert(head);
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction.getOpcode() != target.returnOpcode) continue;
                if (cancellationReturn.test(method, instruction)) continue;
                InsnList tail = new InsnList();
                if (target.kind == 3) tail.add(new InsnNode(Opcodes.DUP));
                tail.add(new VarInsnNode(Opcodes.ALOAD, 0));
                if (target.kind == 2) tail.add(new VarInsnNode(Opcodes.ALOAD, 2));
                if (target.kind == 3) tail.add(new InsnNode(Opcodes.SWAP));
                tail.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
                    target.kind == 2 ? "afterAttack" : "afterItem", TWO, false));
                method.instructions.insertBefore(instruction, tail);
            }
            method.maxStack = Math.max(method.maxStack, 3);
        }
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor output = super.visitMethod(access, name, desc, signature, exceptions);
                MethodNode edit = edits.get(name + desc);
                if (edit == null) return output;
                edit.accept(output);
                return null;
            }
        }, 0);
        return writer.toByteArray();
    }

    /** Wrap original code and handlers, with cleanup outside every original catch range. */
    private static void addBreakingFinally(String owner, MethodNode method, InsnList head) {
        Map<AbstractInsnNode, List<Integer>> residualStack = new IdentityHashMap<>();
        try {
            Frame<BasicValue>[] frames = new Analyzer<>(new BasicInterpreter()).analyze(owner, method);
            for (int index = 0; index < method.instructions.size(); index++) {
                AbstractInsnNode instruction = method.instructions.get(index);
                if (instruction.getOpcode() != Opcodes.IRETURN || frames[index] == null) continue;
                Frame<BasicValue> frame = frames[index];
                List<Integer> widths = new ArrayList<>();
                // @Inject(at=RETURN) may leave the original result below its
                // replacement. IRETURN discards it, whereas GOTO would not.
                for (int slot = frame.getStackSize() - 2; slot >= 0; slot--) widths.add(frame.getStack(slot).getSize());
                residualStack.put(instruction, widths);
            }
        } catch (AnalyzerException error) {
            throw reject("cannot classify breaking exit stack: " + error.getMessage());
        }
        if (method.maxLocals >= 65535) throw reject("no local slot for breaking result");
        int resultLocal = method.maxLocals++;
        LabelNode start = new LabelNode(), end = new LabelNode();
        LabelNode normal = new LabelNode(), exceptional = new LabelNode();
        head.add(start);
        method.instructions.insert(head);
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!residualStack.containsKey(instruction)) continue;
            InsnList exit = new InsnList();
            exit.add(new VarInsnNode(Opcodes.ISTORE, resultLocal));
            for (int width : residualStack.get(instruction)) exit.add(new InsnNode(width == 2 ? Opcodes.POP2 : Opcodes.POP));
            exit.add(new JumpInsnNode(Opcodes.GOTO, normal));
            method.instructions.insertBefore(instruction, exit);
            method.instructions.remove(instruction);
        }
        method.instructions.add(end);
        method.instructions.add(normal);
        List<Object> locals = new ArrayList<>();
        locals.add(owner);
        while (locals.size() < resultLocal) locals.add(Opcodes.TOP);
        locals.add(Opcodes.INTEGER);
        method.instructions.add(new FrameNode(Opcodes.F_NEW, locals.size(), locals.toArray(), 0, new Object[0]));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "endBreaking", ONE, false));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, resultLocal));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.instructions.add(exceptional);
        method.instructions.add(new FrameNode(Opcodes.F_NEW, 1, new Object[] {owner}, 1, new Object[] {"java/lang/Throwable"}));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "endBreaking", ONE, false));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        // Existing handlers have first refusal. Neither cleanup callback is in
        // this range, so a failing cleanup cannot be caught and invoked twice.
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, exceptional, null));
        method.maxStack = Math.max(method.maxStack, 2);
    }

    private static MethodInsnNode selectedSlotCall(MethodNode method, boolean named) {
        String owner = named ? "net/minecraft/entity/player/PlayerInventory" : "net/minecraft/class_1661";
        String name = named ? "getSelectedSlot" : "method_67532";
        MethodInsnNode found = null;
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                && call.name.equals(name) && call.desc.equals("()I")) {
            if (found != null || call.getOpcode() != Opcodes.INVOKEVIRTUAL || call.itf) throw reject("ambiguous selected-slot read");
            found = call;
        }
        if (found == null) throw reject("missing selected-slot read");
        return found;
    }

    private record Target(String name, String desc, int kind, int returnOpcode) { }
    private static IllegalArgumentException reject(String detail) { return new IllegalArgumentException("Interaction hook preparation rejected: " + detail); }
}
