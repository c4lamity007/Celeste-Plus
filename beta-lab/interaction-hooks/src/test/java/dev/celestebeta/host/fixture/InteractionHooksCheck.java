package dev.celestebeta.host.fixture;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.celestebeta.host.InteractionHookBridge;
import dev.celestebeta.host.instrument.InteractionHooksTransformer;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

public final class InteractionHooksCheck {
    private static int checks;
    private static final String BRIDGE = "dev/celestebeta/host/InteractionHookBridge";
    private static StartupRecorder startupRecorder;
    private InteractionHooksCheck() { }

    public static void main(String[] args) throws Exception {
        String named = "net/minecraft/client/network/ClientPlayerInteractionManager";
        try (InputStream stream = InteractionHooksCheck.class.getClassLoader().getResourceAsStream(named + ".class")) {
            verify(named, stream.readAllBytes(), true);
        }
        String intermediary = "net/minecraft/class_636";
        try (JarFile jar = new JarFile(Path.of(args[0]).toFile()); InputStream stream = jar.getInputStream(jar.getJarEntry(intermediary + ".class"))) {
            verify(intermediary, stream.readAllBytes(), false);
        }
        startupWrapper();
        System.out.println("PASS interaction hooks: " + checks + " checks; actual class schemas and executable ordering/return fixtures");
    }

    /** Executes the compiled startup finally body, substituting only its game-facing helper dependencies. */
    private static void startupWrapper() throws Exception {
        String owner = "dev/c4lam/celeste/mixin/ClientPlayerInteractionManagerSlotMixin";
        ClassNode compiled;
        try (InputStream input = InteractionHooksCheck.class.getClassLoader().getResourceAsStream(owner + ".class")) {
            if (input == null) throw new AssertionError("missing compiled startup slot mixin");
            compiled = read(input.readAllBytes());
        }
        compiled.access &= ~Opcodes.ACC_ABSTRACT;
        String posName = "net/minecraft/util/math/BlockPos", directionName = "net/minecraft/util/math/Direction";
        String inventoryName = "net/minecraft/entity/player/PlayerInventory";
        String helperName = "dev/c4lam/celeste/compat/InteractionSlotHooks";
        Map<String, byte[]> definitions = new HashMap<>();
        definitions.put(owner.replace('/', '.'), write(compiled));
        for (String name : List.of(posName, directionName, inventoryName)) definitions.put(name.replace('/', '.'), basic(name, null));
        definitions.put(helperName.replace('/', '.'), startupHelper(helperName));
        ClassLoader loader = new ClassLoader(InteractionHooksCheck.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                byte[] bytes = definitions.get(name); if (bytes == null) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> type = findLoadedClass(name); if (type == null) type = defineClass(name, bytes, 0, bytes.length);
                    if (resolve) resolveClass(type); return type;
                }
            }
        };
        Class<?> type = loader.loadClass(owner.replace('/', '.'));
        Object instance = type.getConstructor().newInstance();
        Object pos = loader.loadClass(posName.replace('/', '.')).getConstructor().newInstance();
        Object direction = loader.loadClass(directionName.replace('/', '.')).getConstructor().newInstance();
        Method wrapper = type.getDeclaredMethod("celeste$withSilentBreakingStack", pos.getClass(), direction.getClass(), Operation.class);
        wrapper.setAccessible(true);
        StartupRecorder recorder = new StartupRecorder(instance); startupRecorder = recorder;
        try {
            for (boolean returned : List.of(true, false, false, true, false)) {
                recorder.events.clear(); int previous = recorder.endCalls;
                Operation<Boolean> original = values -> {
                    check(values.length == 2 && values[0] == pos && values[1] == direction, "startup operation receives exact arguments");
                    recorder.events.add("body"); return returned;
                };
                check(Boolean.valueOf(returned).equals(invoke(wrapper, instance, pos, direction, original)), "startup normal or cancelled return preserved");
                check(recorder.events.equals(List.of("begin:1", "body", "end:0")) && recorder.depth == 0 && recorder.endCalls == previous + 1,
                    "compiled startup finally clears repeated cancellation and reset state exactly once");
            }
            RuntimeException bodyFailure = new IllegalArgumentException("startup operation body");
            Operation<Boolean> throwing = values -> { recorder.events.add("body"); throw bodyFailure; };
            recorder.events.clear(); int previous = recorder.endCalls;
            check(expect(() -> invoke(wrapper, instance, pos, direction, throwing), IllegalArgumentException.class) == bodyFailure,
                "compiled startup wrapper preserves original body exception");
            check(recorder.events.equals(List.of("begin:1", "body", "end:0")) && recorder.depth == 0 && recorder.endCalls == previous + 1,
                "compiled startup finally cleans exceptional exit exactly once");
            RuntimeException endFailure = new IllegalStateException("startup cleanup");
            for (int exit = 0; exit < 3; exit++) {
                boolean returned = exit == 0;
                Operation<Boolean> original = exit == 2 ? throwing : values -> { recorder.events.add("body"); return returned; };
                recorder.events.clear(); recorder.endFailure = endFailure; previous = recorder.endCalls;
                check(expect(() -> invoke(wrapper, instance, pos, direction, original), IllegalStateException.class) == endFailure,
                    "compiled startup cleanup failure propagates with Java finally semantics");
                check(recorder.events.equals(List.of("begin:1", "body", "end:0")) && recorder.depth == 0 && recorder.endCalls == previous + 1,
                    "compiled startup cleanup is not repeated when it throws");
                recorder.endFailure = null;
            }
            recorder.events.clear(); previous = recorder.endCalls;
            Operation<Boolean> inner = values -> { recorder.events.add("inner"); return false; };
            Operation<Boolean> outer = values -> {
                recorder.events.add("outer");
                check(!startupInvoke(wrapper, instance, pos, direction, inner) && recorder.depth == 1,
                    "nested startup cancellation restores outer projection depth");
                return true;
            };
            check(Boolean.TRUE.equals(invoke(wrapper, instance, pos, direction, outer)), "nested startup outer return preserved");
            check(recorder.events.equals(List.of("begin:1", "outer", "begin:2", "inner", "end:1", "end:0"))
                && recorder.depth == 0 && recorder.endCalls == previous + 2, "compiled startup finally balances nested operations");
            RuntimeException beginFailure = new IllegalArgumentException("startup begin");
            recorder.events.clear(); recorder.beginFailure = beginFailure; previous = recorder.endCalls;
            check(expect(() -> invoke(wrapper, instance, pos, direction, inner), IllegalArgumentException.class) == beginFailure,
                "compiled startup begin failure remains exact");
            check(recorder.events.equals(List.of("begin-failed")) && recorder.depth == 0 && recorder.endCalls == previous,
                "compiled startup begin is outside its finally region");
        } finally { startupRecorder = null; }
    }

    private static boolean startupInvoke(Method method, Object instance, Object pos, Object direction, Operation<Boolean> operation) {
        try { return (Boolean) invoke(method, instance, pos, direction, operation); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static byte[] startupHelper(String owner) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        for (String phase : List.of("begin", "end")) {
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, phase + "Breaking", "(Ljava/lang/Object;)V", null, null);
            method.visitCode(); method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "dev/celestebeta/host/fixture/InteractionHooksCheck", "startup" + (phase.equals("begin") ? "Begin" : "End"), "(Ljava/lang/Object;)V", false);
            method.visitInsn(Opcodes.RETURN); method.visitMaxs(1, 1); method.visitEnd();
        }
        MethodVisitor slot = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "synchronizationSlot", "(Lnet/minecraft/entity/player/PlayerInventory;)I", null, null);
        slot.visitCode(); slot.visitInsn(Opcodes.ICONST_0); slot.visitInsn(Opcodes.IRETURN); slot.visitMaxs(1, 1); slot.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }

    public static void startupBegin(Object manager) {
        StartupRecorder recorder = startupRecorder; check(recorder != null && manager == recorder.instance, "startup begin receives exact manager");
        if (recorder.beginFailure != null) { recorder.events.add("begin-failed"); throw recorder.beginFailure; }
        recorder.depth++; recorder.events.add("begin:" + recorder.depth);
    }
    public static void startupEnd(Object manager) {
        StartupRecorder recorder = startupRecorder; check(recorder != null && manager == recorder.instance && recorder.depth > 0, "startup cleanup has matching manager and begin");
        recorder.depth--; recorder.endCalls++; recorder.events.add("end:" + recorder.depth);
        if (recorder.endFailure != null) throw recorder.endFailure;
    }
    private static final class StartupRecorder {
        final Object instance; final List<String> events = new ArrayList<>();
        int depth, endCalls; RuntimeException beginFailure, endFailure;
        StartupRecorder(Object instance) { this.instance = instance; }
    }

    private static void verify(String owner, byte[] original, boolean named) throws Exception {
        byte[] snapshot = original.clone();
        byte[] prepared = InteractionHooksTransformer.prepare(owner, original);
        ClassNode before = read(original), after = read(prepared);
        check(schema(before).equals(schema(after)), "class schema unchanged");
        List<MethodNode> targets = new ArrayList<>();
        for (int i = 0; i < before.methods.size(); i++) {
            MethodNode old = before.methods.get(i), edited = after.methods.get(i);
            if (!text(old).equals(text(edited))) {
                targets.add(old);
                new Analyzer<>(new BasicVerifier()).analyze(owner, edited);
                checks++;
                check(hasHook(edited), "each changed method has intended bridge");
                if (kind(old, named) == 1) {
                    check(returnCount(edited) == 1, "all breaking returns share a normal cleanup epilogue");
                    check(countHook(edited, "endBreaking") == 2,
                        "breaking cleanup has normal and exceptional epilogues");
                }
            }
        }
        check(targets.size() == 5, "only five interaction method bodies changed");
        check(java.util.Arrays.equals(original, snapshot), "input bytes preserved");
        expect(() -> InteractionHooksTransformer.prepare(owner, prepared), IllegalArgumentException.class);
        ClassNode missing = read(original); missing.methods.removeIf(method -> method.name.equals(named ? "interactItem" : "method_2919"));
        expect(() -> InteractionHooksTransformer.prepare(owner, write(missing)), IllegalArgumentException.class);
        ClassNode startup = read(original); startup.methods.add(new MethodNode(Opcodes.ACC_PRIVATE, "celeste$old", "()V", null, null));
        expect(() -> InteractionHooksTransformer.prepare(owner, write(startup)), IllegalArgumentException.class);
        ClassNode coveredReturn = read(original);
        MethodNode breaking = coveredReturn.methods.stream().filter(method -> method.name.equals(named ? "attackBlock" : "method_2910")).findFirst().orElseThrow();
        for (var instruction : breaking.instructions.toArray()) if (instruction.getOpcode() == Opcodes.IRETURN) {
            LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
            breaking.instructions.insertBefore(instruction, start); breaking.instructions.insert(instruction, end);
            breaking.instructions.add(handler); breaking.instructions.add(new InsnNode(Opcodes.ATHROW));
            breaking.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
            break;
        }
        ClassNode coveredPrepared = read(InteractionHooksTransformer.prepare(owner, write(coveredReturn)));
        MethodNode coveredMethod = coveredPrepared.methods.stream().filter(method -> method.name.equals(breaking.name)).findFirst().orElseThrow();
        new Analyzer<>(new BasicVerifier()).analyze(owner, coveredMethod);
        check(countHook(coveredMethod, "endBreaking") == 2, "existing handlers can cover original normal returns");
        execute(owner, named, targets);
    }

    @SuppressWarnings("try")
    private static void execute(String owner, boolean named, List<MethodNode> targets) throws Exception {
        Map<String, byte[]> definitions = new HashMap<>();
        String inventory = named ? "net/minecraft/entity/player/PlayerInventory" : "net/minecraft/class_1661";
        String getter = named ? "getSelectedSlot" : "method_67532";
        for (MethodNode target : targets) {
            for (Type type : Type.getArgumentTypes(target.desc)) if (type.getSort() == Type.OBJECT) definitions.put(type.getClassName(), basic(type.getInternalName(), null));
            Type result = Type.getReturnType(target.desc);
            if (result.getSort() == Type.OBJECT) definitions.put(result.getClassName(), basic(result.getInternalName(), null));
        }
        definitions.put(inventory.replace('/', '.'), basic(inventory, getter));
        definitions.put(owner.replace('/', '.'), InteractionHooksTransformer.prepare(owner, fixture(owner, inventory, getter, targets)));
        ClassLoader loader = new ClassLoader(InteractionHooksCheck.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!definitions.containsKey(name)) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) { byte[] bytes = definitions.get(name); type = defineClass(name, bytes, 0, bytes.length); }
                    if (resolve) resolveClass(type);
                    return type;
                }
            }
        };
        Class<?> type = loader.loadClass(owner.replace('/', '.'));
        Object instance = type.getConstructor().newInstance();
        Recorder recorder = new Recorder(instance);
        try (var registration = InteractionHookBridge.install(recorder)) {
            expect(() -> InteractionHookBridge.install(recorder), IllegalStateException.class);
            for (MethodNode target : targets) {
                Method method = java.util.Arrays.stream(type.getDeclaredMethods()).filter(value -> value.getName().equals(target.name)).findFirst().orElseThrow();
                Object[] arguments = new Object[method.getParameterCount()];
                for (int i = 0; i < arguments.length; i++) arguments[i] = method.getParameterTypes()[i].getConstructor().newInstance();
                type.getField("body").setInt(instance, 0);
                recorder.events.clear(); recorder.first = recorder.second = null;
                Object returned = invoke(method, instance, arguments);
                int kind = kind(target, named);
                if (kind == 0) {
                    check(type.getField("selected").getInt(instance) == 7 && recorder.originalSlot == 4, "slot override receives vanilla read");
                    check(recorder.events.equals(List.of("slot")), "one selected-slot hook");
                } else {
                    check(recorder.events.equals(List.of("before:0", "after:1")), "HEAD before body, RETURN after body");
                    if (kind == 1) check(Boolean.TRUE.equals(returned) && recorder.breakingDepth == 0, "breaking boolean preserved and projection cleared");
                    if (kind == 2) check(recorder.first == arguments[0] && recorder.second == arguments[1], "exact player and target passed");
                    if (kind == 3) check(recorder.first == arguments[0] && recorder.second == arguments[1]
                        && recorder.result == returned && returned != null, "exact item result and input identities preserved");
                    recorder.events.clear(); type.getField("body").setInt(instance, 0);
                    RuntimeException originalFailure = new IllegalStateException("fixture body failed");
                    type.getField("failure").set(instance, originalFailure);
                    check(expect(() -> invoke(method, instance, arguments), IllegalStateException.class) == originalFailure,
                        "original body exception remains exact");
                    check(recorder.events.equals(kind == 1 ? List.of("before:0", "after:0") : List.of("before:0")),
                        "only breaking has finally cleanup on body exception");
                    type.getField("failure").set(instance, null);
                    if (kind == 1) {
                        check(recorder.breakingDepth == 0, "throwing breaking body leaves no projection depth");
                        breakingExits(type, instance, method, arguments, recorder);
                    }
                }
            }
            int previous = recorder.events.size();
            Thread worker = new Thread(() -> {
                InteractionHookBridge.beginBreaking(instance); InteractionHookBridge.endBreaking(instance);
                if (InteractionHookBridge.selectedSlot(instance, 3) != 3) throw new AssertionError("worker slot changed");
            });
            worker.start(); worker.join(5000);
            check(!worker.isAlive() && recorder.events.size() == previous, "non-client thread keeps original behavior");
        }
        int previous = recorder.events.size();
        InteractionHookBridge.beforeItem(instance, null, null);
        check(InteractionHookBridge.selectedSlot(instance, 4) == 4 && recorder.events.size() == previous, "closed bridge is inert");
    }

    private static void breakingExits(Class<?> type, Object instance, Method method, Object[] arguments, Recorder recorder) throws Exception {
        type.getField("earlyReturn").setBoolean(instance, true);
        for (int attempt = 0; attempt < 3; attempt++) {
            recorder.events.clear(); type.getField("body").setInt(instance, 0); int previous = recorder.endCalls;
            check(Boolean.FALSE.equals(invoke(method, instance, arguments)), "early breaking cancellation preserves false return");
            check(recorder.events.equals(List.of("before:0", "after:0")) && recorder.breakingDepth == 0
                && recorder.endCalls == previous + 1, "repeated early cancellation cleans exactly once without residual projection");
        }
        type.getField("earlyReturn").setBoolean(instance, false);
        RuntimeException endFailure = new IllegalArgumentException("fixture breaking cleanup failed");
        for (boolean early : List.of(false, true)) {
            type.getField("earlyReturn").setBoolean(instance, early); type.getField("body").setInt(instance, 0);
            recorder.events.clear(); recorder.endFailure = endFailure; int previous = recorder.endCalls;
            check(expect(() -> invoke(method, instance, arguments), IllegalArgumentException.class) == endFailure,
                "breaking cleanup exception identity preserved");
            check(recorder.events.equals(List.of("before:0", early ? "after:0" : "after:1"))
                && recorder.endCalls == previous + 1 && recorder.breakingDepth == 0,
                "throwing end callback is not caught and called twice");
            recorder.endFailure = null;
        }
        type.getField("earlyReturn").setBoolean(instance, false); type.getField("body").setInt(instance, 0);
        RuntimeException beginFailure = new IllegalArgumentException("fixture breaking begin failed");
        recorder.events.clear(); recorder.beginFailure = beginFailure; int previous = recorder.endCalls;
        check(expect(() -> invoke(method, instance, arguments), IllegalArgumentException.class) == beginFailure,
            "breaking begin exception identity preserved");
        check(recorder.events.equals(List.of("before:0")) && recorder.endCalls == previous && recorder.breakingDepth == 0,
            "failed begin is outside cleanup handler");
        recorder.beginFailure = null;
    }

    private static byte[] fixture(String owner, String inventory, String getter, List<MethodNode> targets) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "body", "I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "selected", "I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "failure", "Ljava/lang/RuntimeException;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "earlyReturn", "Z", null, null).visitEnd();
        constructor(writer);
        boolean named = owner.endsWith("ClientPlayerInteractionManager");
        for (MethodNode target : targets) {
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, target.name, target.desc, null, null);
            method.visitCode();
            if (kind(target, named) == 1) {
                Label proceed = new Label(); method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitFieldInsn(Opcodes.GETFIELD, owner, "earlyReturn", "Z"); method.visitJumpInsn(Opcodes.IFEQ, proceed);
                method.visitInsn(Opcodes.ICONST_0); method.visitInsn(Opcodes.IRETURN); method.visitLabel(proceed);
            }
            Label normal = new Label();
            method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, "failure", "Ljava/lang/RuntimeException;");
            method.visitJumpInsn(Opcodes.IFNULL, normal); method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitFieldInsn(Opcodes.GETFIELD, owner, "failure", "Ljava/lang/RuntimeException;"); method.visitInsn(Opcodes.ATHROW);
            method.visitLabel(normal); method.visitVarInsn(Opcodes.ALOAD, 0); method.visitInsn(Opcodes.ICONST_1);
            method.visitFieldInsn(Opcodes.PUTFIELD, owner, "body", "I");
            if (kind(target, named) == 0) {
                method.visitVarInsn(Opcodes.ALOAD, 0); method.visitTypeInsn(Opcodes.NEW, inventory); method.visitInsn(Opcodes.DUP);
                method.visitMethodInsn(Opcodes.INVOKESPECIAL, inventory, "<init>", "()V", false);
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, inventory, getter, "()I", false);
                method.visitFieldInsn(Opcodes.PUTFIELD, owner, "selected", "I"); method.visitInsn(Opcodes.RETURN);
            } else {
                Type result = Type.getReturnType(target.desc);
                if (result.getSort() == Type.BOOLEAN) { method.visitInsn(Opcodes.ICONST_1); method.visitInsn(Opcodes.IRETURN); }
                else if (result.getSort() == Type.VOID) method.visitInsn(Opcodes.RETURN);
                else {
                    method.visitTypeInsn(Opcodes.NEW, result.getInternalName()); method.visitInsn(Opcodes.DUP);
                    method.visitMethodInsn(Opcodes.INVOKESPECIAL, result.getInternalName(), "<init>", "()V", false); method.visitInsn(Opcodes.ARETURN);
                }
            }
            method.visitMaxs(0, 0); method.visitEnd();
        }
        writer.visitEnd(); return writer.toByteArray();
    }
    private static byte[] basic(String owner, String getter) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null); constructor(writer);
        if (getter != null) {
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, getter, "()I", null, null); method.visitCode();
            method.visitInsn(Opcodes.ICONST_4); method.visitInsn(Opcodes.IRETURN); method.visitMaxs(0, 0); method.visitEnd();
        }
        writer.visitEnd(); return writer.toByteArray();
    }
    private static void constructor(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null); method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        method.visitInsn(Opcodes.RETURN); method.visitMaxs(1, 1); method.visitEnd();
    }
    private static int kind(MethodNode method, boolean named) {
        if (method.name.equals(named ? "syncSelectedSlot" : "method_2911")) return 0;
        if (method.desc.endsWith("Z")) return 1;
        return method.desc.endsWith("V") ? 2 : 3;
    }
    private static final class Recorder implements InteractionHookBridge.Handler {
        final Object expected;
        final List<String> events = new ArrayList<>();
        Object first, second, result;
        int originalSlot, breakingDepth, endCalls;
        RuntimeException beginFailure, endFailure;
        Recorder(Object expected) { this.expected = expected; }
        private void event(Object manager, String phase) {
            if (manager != expected) throw new AssertionError("wrong manager");
            try { events.add(phase + ":" + manager.getClass().getField("body").getInt(manager)); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
        public int selectedSlot(Object inventory, int vanilla) { events.add("slot"); originalSlot = vanilla; return 7; }
        public void beginBreaking(Object manager) {
            event(manager, "before"); if (beginFailure != null) throw beginFailure; breakingDepth++;
        }
        public void endBreaking(Object manager) {
            check(breakingDepth > 0, "breaking cleanup has a matching begin"); breakingDepth--; endCalls++;
            event(manager, "after"); if (endFailure != null) throw endFailure;
        }
        public void beforeAttack(Object manager, Object player) { first = player; event(manager, "before"); }
        public void afterAttack(Object manager, Object target) { second = target; event(manager, "after"); }
        public void beforeItem(Object manager, Object player, Object hand) { first = player; second = hand; event(manager, "before"); }
        public void afterItem(Object manager, Object value) { result = value; event(manager, "after"); }
    }
    private static Object invoke(Method method, Object value, Object... args) throws Exception {
        try { return method.invoke(value, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        }
    }
    private static ClassNode read(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES); return node; }
    private static byte[] write(ClassNode node) { ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray(); }
    private static List<String> schema(ClassNode node) {
        List<String> fields = new ArrayList<>(); fields.add(node.access + ":" + node.name + ":" + node.superName + ":" + node.interfaces);
        node.fields.forEach(field -> fields.add(field.access + ":" + field.name + ":" + field.desc));
        node.methods.forEach(method -> fields.add(method.access + ":" + method.name + ":" + method.desc + ":" + method.exceptions)); return fields;
    }
    private static boolean hasHook(MethodNode method) {
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.owner.equals(BRIDGE)) return true;
        return false;
    }
    private static int countHook(MethodNode method, String name) {
        int count = 0; for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
            && call.owner.equals(BRIDGE) && call.name.equals(name)) count++; return count;
    }
    private static int returnCount(MethodNode method) {
        int count = 0; for (var instruction : method.instructions) if (instruction.getOpcode() >= Opcodes.IRETURN
            && instruction.getOpcode() <= Opcodes.RETURN) count++; return count;
    }
    private static String text(MethodNode method) {
        Textifier printer = new Textifier(); method.accept(new TraceMethodVisitor(printer)); StringWriter text = new StringWriter();
        printer.print(new PrintWriter(text)); return text.toString();
    }
    private static void check(boolean passed, String message) { checks++; if (!passed) throw new AssertionError(message); }
    private static Throwable expect(Throwing action, Class<? extends Throwable> type) {
        checks++; try { action.run(); } catch (Throwable failure) {
            if (type.isInstance(failure)) return failure;
            throw new AssertionError("unexpected exception", failure);
        }
        throw new AssertionError("expected " + type.getName());
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
