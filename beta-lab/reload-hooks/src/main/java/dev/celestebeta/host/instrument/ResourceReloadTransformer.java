package dev.celestebeta.host.instrument;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

/**
 * Offline, fail-closed preparation of the two Minecraft 1.21.11 reload hooks.
 *
 * <p>This class does not attach an agent, register a transformer, redefine a class,
 * or resolve Minecraft classes. The caller remains responsible for verifying the
 * canonical bridge's visibility and identity before installing the returned bytes.
 */
public final class ResourceReloadTransformer {
    private static final String NAMED_CLIENT = "net/minecraft/client/MinecraftClient";
    private static final String INTERMEDIARY_CLIENT = "net/minecraft/class_310";
    private static final String BRIDGE = "dev/celestebeta/host/ResourceReloadBridge";
    private static final String FUTURE = "java/util/concurrent/CompletableFuture";
    private static final String OPTIONAL = "java/util/Optional";
    private static final String THROWABLE = "java/lang/Throwable";
    private static final String INPUTS_DESCRIPTOR =
            "(Ljava/lang/Object;ZLjava/util/concurrent/CompletableFuture;Ljava/util/List;)Ljava/util/List;";
    private static final String BEFORE_DESCRIPTOR =
            "(Ljava/lang/Object;ZLjava/util/concurrent/CompletableFuture;Ljava/util/Optional;)Z";
    private static final String AFTER_DESCRIPTOR =
            "(Ljava/lang/Object;ZLjava/util/concurrent/CompletableFuture;Ljava/util/Optional;Ljava/lang/Throwable;)V";

    private ResourceReloadTransformer() {
    }

    /**
     * Returns prepared bytes for an exact supported internal class name.
     *
     * @throws IllegalArgumentException if the name, class identity, method shape,
     *         or unique insertion patterns do not match, or bridge calls already exist
     * @throws NullPointerException if either argument is null
     */
    public static byte[] prepare(String internalClassName, byte[] original) {
        Objects.requireNonNull(internalClassName, "internalClassName");
        Objects.requireNonNull(original, "original");
        Layout layout = layout(internalClassName);
        ClassReader reader = new ClassReader(original);
        if (!internalClassName.equals(reader.getClassName())) {
            throw mismatch("requested class does not match the class bytes");
        }

        ClassNode inspected = new ClassNode(Opcodes.ASM9);
        reader.accept(inspected, ClassReader.EXPAND_FRAMES);
        rejectExistingBridgeCalls(inspected);
        MethodNode inputs = uniqueMethod(inspected, layout.inputName(), layout.inputDescriptor());
        MethodNode result = uniqueMethod(inspected, "method_24228", layout.resultDescriptor());
        requireInstanceCode(inputs, 5);
        requireInstanceCode(result, 5);

        // Complete all preflight checks before modifying either method's tree.
        VarInsnNode inputStore = findInputStore(inputs, layout);
        List<AbstractInsnNode> returns = validateResult(result);
        List<int[]> returnStacks = returnStacks(result, returns, layout.owner());

        augmentInputs(inputs, inputStore);
        augmentResult(result, returns, returnStacks, layout);

        // A reader-backed writer copies every non-target method's original bytes.
        // Only the two prepared MethodNodes are emitted through visitors. No
        // COMPUTE_FRAMES/getCommonSuperClass path can load game classes here.
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor downstream = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (name.equals(inputs.name) && descriptor.equals(inputs.desc)) {
                    inputs.accept(downstream);
                    return null;
                }
                if (name.equals(result.name) && descriptor.equals(result.desc)) {
                    result.accept(downstream);
                    return null;
                }
                return downstream;
            }
        }, 0);
        return writer.toByteArray();
    }

    private static Layout layout(String owner) {
        if (NAMED_CLIENT.equals(owner)) {
            return new Layout(owner, owner + "$LoadingContext", "reloadResources",
                    "net/minecraft/resource/ResourcePackManager", "createResourcePacks");
        }
        if (INTERMEDIARY_CLIENT.equals(owner)) {
            return new Layout(owner, owner + "$class_8764", "method_36561",
                    "net/minecraft/class_3283", "method_29211");
        }
        throw mismatch("unsupported internal class name: " + owner);
    }

    private static void rejectExistingBridgeCalls(ClassNode node) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && BRIDGE.equals(call.owner)) {
                    throw mismatch("class already references the reload bridge in " + method.name + method.desc);
                }
            }
        }
    }

    private static MethodNode uniqueMethod(ClassNode node, String name, String descriptor) {
        MethodNode found = null;
        for (MethodNode candidate : node.methods) {
            if (name.equals(candidate.name) && descriptor.equals(candidate.desc)) {
                if (found != null) {
                    throw mismatch("duplicate target method: " + name + descriptor);
                }
                found = candidate;
            }
        }
        if (found == null) {
            throw mismatch("missing target method: " + name + descriptor);
        }
        return found;
    }

    private static void requireInstanceCode(MethodNode method, int minimumLocals) {
        int unsupported = Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE;
        if ((method.access & unsupported) != 0 || method.instructions.size() == 0
                || method.maxLocals < minimumLocals) {
            throw mismatch("unsupported target method shape: " + method.name + method.desc);
        }
    }

    private static VarInsnNode findInputStore(MethodNode method, Layout layout) {
        MethodInsnNode call = null;
        VarInsnNode attemptStore = null;
        int attemptStoreCount = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode candidate
                    && layout.packManager().equals(candidate.owner)
                    && layout.packMethod().equals(candidate.name)
                    && "()Ljava/util/List;".equals(candidate.desc)) {
                if (call != null) {
                    throw mismatch("duplicate resource-pack input call");
                }
                if (candidate.getOpcode() != Opcodes.INVOKEVIRTUAL || candidate.itf) {
                    throw mismatch("resource-pack input call is not the expected virtual call");
                }
                call = candidate;
            }
            if (instruction instanceof VarInsnNode variable && variable.var == 3
                    && variable.getOpcode() >= Opcodes.ISTORE && variable.getOpcode() <= Opcodes.ASTORE) {
                if (variable.getOpcode() != Opcodes.ASTORE) {
                    throw mismatch("reload attempt local is not a reference");
                }
                attemptStore = variable;
                attemptStoreCount++;
            }
        }
        if (call == null) {
            throw mismatch("missing resource-pack input call");
        }
        AbstractInsnNode next = nextOpcode(call);
        if (!(next instanceof VarInsnNode store)
                || store.getOpcode() != Opcodes.ASTORE || store.var != 4) {
            throw mismatch("resource-pack input call is not followed by ASTORE 4");
        }
        if (attemptStoreCount != 1
                || method.instructions.indexOf(attemptStore) >= method.instructions.indexOf(call)) {
            throw mismatch("reload attempt requires one earlier ASTORE 3");
        }
        return store;
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode next = instruction.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        return next;
    }

    private static List<AbstractInsnNode> validateResult(MethodNode method) {
        List<AbstractInsnNode> returns = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            if (opcode == Opcodes.RETURN) {
                returns.add(instruction);
            } else if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.ARETURN) {
                throw mismatch("non-void result callback return");
            }
            if (instruction instanceof VarInsnNode variable && variable.var < 5
                    && opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) {
                throw mismatch("result callback overwrites a hook argument");
            }
            if (instruction instanceof IincInsnNode increment && increment.var < 5) {
                throw mismatch("result callback increments a hook argument");
            }
            if (opcode == Opcodes.JSR || opcode == Opcodes.RET) {
                throw mismatch("legacy subroutines are unsupported in the result callback");
            }
        }
        if (returns.isEmpty()) {
            throw mismatch("missing normal result callback return");
        }
        return returns;
    }

    private static void augmentInputs(MethodNode method, VarInsnNode store) {
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new VarInsnNode(Opcodes.ILOAD, 1));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 3));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 4));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "inputs", INPUTS_DESCRIPTOR, false));
        hook.add(new VarInsnNode(Opcodes.ASTORE, 4));
        method.instructions.insert(store, hook);
        method.maxStack = Math.max(method.maxStack, 4);
    }

    /** RETURN discards the entire operand stack; a jump to the shared epilogue must do the same. */
    private static List<int[]> returnStacks(MethodNode method, List<AbstractInsnNode> returns, String owner) {
        Frame<BasicValue>[] frames;
        try { frames = new Analyzer<>(new BasicInterpreter()).analyze(owner, method); }
        catch (AnalyzerException failure) { throw mismatch("unverifiable result callback stack: " + failure.getMessage()); }
        List<int[]> stacks = new ArrayList<>();
        for (AbstractInsnNode returned : returns) {
            Frame<BasicValue> frame = frames[method.instructions.indexOf(returned)];
            if (frame == null) throw mismatch("unreachable result callback return");
            int[] widths = new int[frame.getStackSize()];
            for (int i = 0; i < widths.length; i++) widths[i] = frame.getStack(i).getSize();
            stacks.add(widths);
        }
        return stacks;
    }

    private static void augmentResult(MethodNode method, List<AbstractInsnNode> returns, List<int[]> returnStacks, Layout layout) {
        LabelNode originalStart = new LabelNode();
        LabelNode normalExit = new LabelNode();
        LabelNode failedExit = new LabelNode();
        int throwableLocal = method.maxLocals;

        InsnList head = hookArguments();
        head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "beforeResult", BEFORE_DESCRIPTOR, false));
        head.add(new JumpInsnNode(Opcodes.IFNE, normalExit));
        head.add(originalStart);
        method.instructions.insert(head);

        // All original normal exits (and the handled HEAD path) meet outside the
        // protected callback range. Observer failures cannot re-enter the observer.
        for (int i = 0; i < returns.size(); i++) {
            AbstractInsnNode originalReturn = returns.get(i);
            // An INVOKE-site Mixin cancellation can leave evaluated receiver or
            // arguments below its void RETURN, including category-two values.
            int[] widths = returnStacks.get(i);
            InsnList discard = new InsnList();
            for (int value = widths.length - 1; value >= 0; value--) {
                discard.add(new InsnNode(widths[value] == 2 ? Opcodes.POP2 : Opcodes.POP));
            }
            method.instructions.insertBefore(originalReturn, discard);
            method.instructions.set(originalReturn, new JumpInsnNode(Opcodes.GOTO, normalExit));
        }
        method.instructions.add(normalExit);
        method.instructions.add(argumentFrame(layout, false));
        method.instructions.add(hookArguments());
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
                "afterResult", AFTER_DESCRIPTOR, false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        method.instructions.add(failedExit);
        method.instructions.add(argumentFrame(layout, true));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, throwableLocal));
        method.instructions.add(hookArguments());
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, throwableLocal));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
                "afterResult", AFTER_DESCRIPTOR, false));
        // ResourceReloadBridge.afterResult is a no-throw boundary. It contains
        // host observer failures, so the exact original Throwable is rethrown.
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, throwableLocal));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));

        // Appending preserves priority of every existing callback catch handler.
        // The bridge HEAD and both epilogues are outside this half-open range.
        method.tryCatchBlocks.add(new TryCatchBlockNode(originalStart, normalExit, failedExit, THROWABLE));
        method.maxLocals = throwableLocal + 1;
        method.maxStack = Math.max(method.maxStack, 5);
    }

    private static InsnList hookArguments() {
        InsnList arguments = new InsnList();
        arguments.add(new VarInsnNode(Opcodes.ALOAD, 0));
        arguments.add(new VarInsnNode(Opcodes.ILOAD, 1));
        arguments.add(new VarInsnNode(Opcodes.ALOAD, 3));
        arguments.add(new VarInsnNode(Opcodes.ALOAD, 4));
        return arguments;
    }

    private static FrameNode argumentFrame(Layout layout, boolean throwableOnStack) {
        Object[] locals = {layout.owner(), Opcodes.INTEGER, layout.context(), FUTURE, OPTIONAL};
        Object[] stack = throwableOnStack ? new Object[]{THROWABLE} : new Object[0];
        return new FrameNode(Opcodes.F_NEW, locals.length, locals, stack.length, stack);
    }

    private static IllegalArgumentException mismatch(String detail) {
        return new IllegalArgumentException("Resource reload hook preparation rejected: " + detail);
    }

    private record Layout(String owner, String context, String inputName,
                          String packManager, String packMethod) {
        String inputDescriptor() {
            return "(ZL" + context + ";)Ljava/util/concurrent/CompletableFuture;";
        }

        String resultDescriptor() {
            return "(ZL" + context
                    + ";Ljava/util/concurrent/CompletableFuture;Ljava/util/Optional;)V";
        }
    }
}
