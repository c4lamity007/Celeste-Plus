package dev.celestebeta.host.fixture;

import dev.celestebeta.host.instrument.HookArgumentFrames;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Executable synthetic JVM checks for arguments discarded by original stack maps. */
public final class HookArgumentFramesCheck {
    private static final String SELF = "dev/celestebeta/host/fixture/HookArgumentFramesCheck";
    private static int checks;
    private static Object[] observed;
    private HookArgumentFramesCheck() { }
    public static void main(String[] args) throws Exception { System.out.println("PASS hook argument frames: " + run() + " checks"); }
    public static int run() throws Exception {
        checks = 0;
        for (String owner : List.of("fixture/named/ArgumentHooks", "fixture/class_9975")) {
            execute(owner, false);
            execute(owner, true);
            rejections(owner);
        }
        return checks;
    }
    public static void observe(Object receiver, Object text, Object array) { observed = new Object[] {receiver, text, array}; }
    public static void observeReceiver(Object receiver) { observed = new Object[] {receiver}; }

    private static void execute(String owner, boolean chopped) throws Exception {
        byte[] original = fixture(owner, chopped);
        Object dormant = define(original).getConstructor().newInstance();
        observed = null;
        invoke(dormant, chopped, true, null);
        check(observed == null, "original fixture runs without callback");
        ClassNode node = read(original);
        MethodNode method = method(node);
        inject(method, chopped);
        expect(VerifyError.class, () -> define(write(node)).getDeclaredMethods(), "unrepaired dropped locals fail real JVM verification");
        int instructions = method.instructions.size(), locals = method.maxLocals, stack = method.maxStack;
        List<? extends List<Object>> stacks = frames(method).stream().map(frame -> new ArrayList<>(frame.stack)).toList();
        HookArgumentFrames.retain(owner, method, chopped ? new int[] {0} : new int[] {0, 1, 2});
        check(instructions == method.instructions.size() && locals == method.maxLocals && stack == method.maxStack,
            "repair changes no instructions, max locals, or max stack");
        check(stacks.equals(frames(method).stream().map(frame -> frame.stack).toList()), "operand stack frames unchanged");
        if (!chopped) for (FrameNode frame : frames(method)) {
            check(frame.local.get(3).equals(Opcodes.LONG) && frame.local.get(4).equals(Opcodes.DOUBLE), "wide local entries preserved");
        }
        Object fixed = define(write(node)).getConstructor().newInstance();
        for (boolean branch : List.of(false, true)) {
            observed = null;
            Object[] supplied = invoke(fixed, chopped, branch, null);
            check(observed != null && observed[0] == fixed, "receiver survives reduced frame and branch");
            if (!chopped) check(observed[1] == supplied[0] && observed[2] == supplied[1], "original string and array references retained");
        }
        if (!chopped) {
            RuntimeException failure = new IllegalStateException("synthetic body failure");
            observed = null;
            try { invoke(fixed, false, true, failure); throw new AssertionError("missing original exception"); }
            catch (InvocationTargetException thrown) { check(thrown.getCause() == failure, "original catch/rethrow identity preserved"); }
            check(observed == null, "tail callback absent on throwing path");
        }
        byte[] repaired = write(node);
        HookArgumentFrames.retain(owner, method, chopped ? new int[] {0} : new int[] {0, 1, 2});
        check(Arrays.equals(repaired, write(node)), "retention is idempotent");
    }
    private static Object[] invoke(Object instance, boolean chopped, boolean branch, RuntimeException failure) throws Exception {
        if (chopped) { instance.getClass().getMethod("run").invoke(instance); return new Object[0]; }
        String text = new String(new char[] {'o', 'r', 'i', 'g'});
        String[] array = new String[] {"synthetic"};
        instance.getClass().getMethod("run", String.class, String[].class, long.class, double.class, boolean.class, RuntimeException.class)
            .invoke(instance, text, array, 7L, 2.5D, branch, failure);
        return new Object[] {text, array};
    }
    private static void rejections(String owner) {
        for (int opcode : new int[] {Opcodes.ASTORE, Opcodes.LSTORE, Opcodes.DSTORE}) {
            MethodNode method = method(read(fixture(owner, false)));
            method.instructions.insert(new VarInsnNode(opcode, opcode == Opcodes.ASTORE ? 1 : 0));
            List<Object> before = new ArrayList<>(frames(method).getFirst().local);
            expect(IllegalArgumentException.class, () -> HookArgumentFrames.retain(owner, method, 1), "all reference writes and wide overlap rejected");
            check(before.equals(frames(method).getFirst().local), "write rejection does not mutate frames");
        }
        MethodNode increment = method(read(fixture(owner, false)));
        increment.instructions.insert(new IincInsnNode(2, 1));
        expect(IllegalArgumentException.class, () -> HookArgumentFrames.retain(owner, increment, 2), "increment of selected local rejected");
        for (int slot : new int[] {-1, 3, 4, 5, 6, 7, 9}) {
            MethodNode method = method(read(fixture(owner, false)));
            expect(IllegalArgumentException.class, () -> HookArgumentFrames.retain(owner, method, slot), "non-reference or wide second slot rejected");
        }
        MethodNode invalid = method(read(fixture(owner, false)));
        List<FrameNode> frames = frames(invalid);
        List<Object> first = new ArrayList<>(frames.getFirst().local);
        frames.getLast().local.set(1, Opcodes.INTEGER);
        expect(IllegalArgumentException.class, () -> HookArgumentFrames.retain(owner, invalid, 1), "primitive frame value rejected");
        check(first.equals(frames.getFirst().local), "late rejection leaves earlier frames unchanged");
        MethodNode widened = method(read(fixture(owner, false)));
        for (FrameNode frame : frames(widened)) frame.local.set(1, "java/lang/Object");
        HookArgumentFrames.retain(owner, widened, 1);
        check(frames(widened).stream().allMatch(frame -> frame.local.get(1).equals("java/lang/String")), "widened reference recovers initial descriptor type");
        MethodNode compressed = method(read(fixture(owner, true)));
        frames(compressed).getFirst().type = Opcodes.F_CHOP;
        expect(IllegalArgumentException.class, () -> HookArgumentFrames.retain(owner, compressed, 0), "unexpanded frames rejected");
    }
    private static byte[] fixture(String owner, boolean chopped) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode(); ctor.visitVarInsn(Opcodes.ALOAD, 0); ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(1, 1); ctor.visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "run", chopped ? "()V"
            : "(Ljava/lang/String;[Ljava/lang/String;JDZLjava/lang/RuntimeException;)Ljava/lang/Object;", null, null);
        method.visitCode();
        Label branch = new Label(), healthy = new Label(), done = new Label(), start = new Label(), end = new Label(), handler = new Label();
        if (chopped) {
            method.visitJumpInsn(Opcodes.GOTO, done); method.visitLabel(done);
            method.visitFrame(Opcodes.F_CHOP, 1, null, 0, null);
            method.visitInsn(Opcodes.RETURN); method.visitMaxs(0, 1);
        } else {
            Object[] dropped = {Opcodes.TOP, Opcodes.TOP, Opcodes.TOP, Opcodes.LONG, Opcodes.DOUBLE, Opcodes.INTEGER, "java/lang/RuntimeException"};
            method.visitLabel(start);
            method.visitVarInsn(Opcodes.ILOAD, 7); method.visitJumpInsn(Opcodes.IFEQ, branch);
            method.visitLabel(healthy); method.visitFrame(Opcodes.F_FULL, dropped.length, dropped, 0, null);
            method.visitVarInsn(Opcodes.ALOAD, 8); method.visitJumpInsn(Opcodes.IFNULL, done);
            method.visitVarInsn(Opcodes.ALOAD, 8); method.visitInsn(Opcodes.ATHROW);
            method.visitLabel(branch); method.visitFrame(Opcodes.F_FULL, dropped.length, dropped, 0, null);
            method.visitVarInsn(Opcodes.LLOAD, 3); method.visitInsn(Opcodes.POP2);
            method.visitVarInsn(Opcodes.DLOAD, 5); method.visitInsn(Opcodes.POP2);
            method.visitJumpInsn(Opcodes.GOTO, done);
            method.visitLabel(done); method.visitFrame(Opcodes.F_FULL, dropped.length, dropped, 0, null);
            method.visitInsn(Opcodes.ACONST_NULL); method.visitInsn(Opcodes.ARETURN);
            method.visitLabel(end); method.visitLabel(handler);
            method.visitFrame(Opcodes.F_FULL, dropped.length, dropped, 1, new Object[] {"java/lang/RuntimeException"});
            method.visitInsn(Opcodes.ATHROW);
            method.visitTryCatchBlock(start, end, handler, "java/lang/RuntimeException"); method.visitMaxs(2, 9);
        }
        method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    private static void inject(MethodNode method, boolean chopped) {
        for (var instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != (chopped ? Opcodes.RETURN : Opcodes.ARETURN)) continue;
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            if (!chopped) { hook.add(new VarInsnNode(Opcodes.ALOAD, 1)); hook.add(new VarInsnNode(Opcodes.ALOAD, 2)); }
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, chopped ? "observeReceiver" : "observe",
                chopped ? "(Ljava/lang/Object;)V" : "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false));
            method.instructions.insertBefore(instruction, hook);
        }
        method.maxStack = Math.max(method.maxStack, chopped ? 1 : 4);
    }
    private static List<FrameNode> frames(MethodNode method) {
        List<FrameNode> result = new ArrayList<>();
        for (var instruction : method.instructions) if (instruction instanceof FrameNode frame) result.add(frame);
        return result;
    }
    private static MethodNode method(ClassNode node) { return node.methods.stream().filter(method -> method.name.equals("run")).findFirst().orElseThrow(); }
    private static ClassNode read(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES); return node; }
    private static byte[] write(ClassNode node) { ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray(); }
    private static Class<?> define(byte[] bytes) { return new ClassLoader(HookArgumentFramesCheck.class.getClassLoader()) {
        Class<?> fixture() { return defineClass(null, bytes, 0, bytes.length); }
    }.fixture(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
    private static void expect(Class<? extends Throwable> type, Throwing body, String message) {
        try { body.run(); } catch (Throwable thrown) { if (type.isInstance(thrown)) { checks++; return; } throw new AssertionError(message, thrown); }
        throw new AssertionError(message);
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}