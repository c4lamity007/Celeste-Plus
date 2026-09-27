package dev.celestebeta.host.fixture;

import dev.celestebeta.host.instrument.CombinedHooksTransformer;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
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
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Executable synthetic mod callbacks in real game schemas; never starts Minecraft. */
public final class GenericReturnHooksCheck {
    private static final String SELF = "dev/celestebeta/host/fixture/GenericReturnHooksCheck";
    private static final String CI = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
    private static final String CIR = CI + "Returnable";
    private static int branch, body, dispatches, guards, checks;
    private static boolean cancel;
    private static final RuntimeException FAILURE = new IllegalStateException("original body error");
    private GenericReturnHooksCheck() { }

    public static int run(Path intermediary) throws Exception {
        checks = 0;
        try (JarFile jar = new JarFile(intermediary.toFile())) {
            for (boolean mapped : List.of(false, true)) {
                for (Case entry : List.of(
                        new Case("entity/LivingEntity", "1309", "getHandSwingDuration", "method_6028", "VisualHookBridge", "swingDuration", false, true),
                        new Case("client/render/SkyRendering", "9975", "updateRenderState", "method_74926", "VisualHookBridge", "extractSky", false, false),
                        new Case("client/render/entity/LivingEntityRenderer", "922", "updateRenderState", "method_62355", "VisualHookBridge", "applyRotations", true, false),
                        new Case("client/input/KeyboardInput", "743", "tick", "method_3129", "RotationHookBridge", "keyboardTick", true, false),
                        new Case("entity/ItemEntity", "1542", "tick", "method_5773", "FakeGambleHookBridge", "itemTick", true, false))) {
                    String owner = "net/minecraft/" + (mapped ? "class_" + entry.id : entry.owner);
                    byte[] original;
                    if (mapped) try (InputStream input = jar.getInputStream(jar.getJarEntry(owner + ".class"))) { original = input.readAllBytes(); }
                    else try (InputStream input = GenericReturnHooksCheck.class.getClassLoader().getResourceAsStream(owner + ".class")) { original = input.readAllBytes(); }
                    for (boolean tailGuard : List.of(false, true)) execute(owner, original, mapped ? entry.mappedMethod : entry.method, entry, tailGuard);
                }
            }
        }
        return checks;
    }

    @SuppressWarnings("try")
    private static void execute(String owner, byte[] original, String name, Case entry, boolean tailGuard) throws Exception {
        ClassNode baseline = read(original);
        MethodNode originalMethod = baseline.methods.stream().filter(method -> method.name.equals(name) && (entry.integer ? method.desc.equals("()I") : method.desc.endsWith(")V"))).findFirst().orElseThrow();
        ClassNode synthetic = read(fixture(owner, originalMethod, entry.integer, tailGuard));
        MethodNode replacement = synthetic.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
        MethodNode helper = synthetic.methods.stream().filter(method -> method.name.equals("arbitrary$observer$cancel" )).findFirst().orElseThrow();
        baseline.methods.set(baseline.methods.indexOf(originalMethod), replacement);
        baseline.methods.add(helper);
        byte[] before = write(baseline);
        ClassNode prepared = read(CombinedHooksTransformer.prepare(owner, before));
        check(prepared.methods.size() == baseline.methods.size() && prepared.fields.size() == baseline.fields.size(), "real schema preserved");
        // Execute only the prepared method and its original helper in a minimal
        // same-owner class, avoiding game constructors and singleton state.
        ClassNode runnable = read(empty(owner));
        MethodNode target = prepared.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(originalMethod.desc)).findFirst().orElseThrow();
        target.access = Opcodes.ACC_PUBLIC;
        runnable.methods.add(target);
        runnable.methods.add(prepared.methods.stream().filter(method -> method.name.equals(helper.name)).findFirst().orElseThrow());
        Map<String, byte[]> definitions = new HashMap<>();
        for (Type type : Type.getArgumentTypes(target.desc)) if (type.getSort() == Type.OBJECT) definitions.put(type.getClassName(), empty(type.getInternalName()));
        definitions.put(owner.replace('/', '.'), write(runnable));
        ClassLoader loader = new ClassLoader(GenericReturnHooksCheck.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(className)) {
                    byte[] bytes = definitions.get(className);
                    if (bytes == null) return super.loadClass(className, resolve);
                    Class<?> result = findLoadedClass(className);
                    if (result == null) result = defineClass(className, bytes, 0, bytes.length);
                    if (resolve) resolveClass(result);
                    return result;
                }
            }
        };
        Class<?> type = loader.loadClass(owner.replace('/', '.'));
        Object instance = type.getConstructor().newInstance();
        var method = java.util.Arrays.stream(type.getDeclaredMethods()).filter(value -> value.getName().equals(name)).findFirst().orElseThrow();
        Object[] arguments = new Object[method.getParameterCount()];
        for (int i = 0; i < arguments.length; i++) if (method.getParameterTypes()[i] == float.class) arguments[i] = 0.0f;
        Class<?> bridge = Class.forName("dev.celestebeta.host." + entry.bridge);
        Class<?> handler = Class.forName(bridge.getName() + "$Handler");
        Object recorder = Proxy.newProxyInstance(handler.getClassLoader(), new Class<?>[] {handler}, (proxy, called, values) -> {
            if (called.getName().equals(entry.operation)) { dispatches++; return entry.integer ? (Integer) values[1] + 1 : null; }
            if (called.getReturnType() == boolean.class) return false;
            if (called.getReturnType() == int.class) return 6;
            if (called.getReturnType() == float.class) return 0.0f;
            return null;
        });
        try (AutoCloseable registration = (AutoCloseable) bridge.getMethod("install", handler).invoke(null, recorder)) {
            for (branch = 0; branch <= 2; branch++) for (boolean cancellation : List.of(false, true)) {
                cancel = cancellation; body = dispatches = guards = 0;
                boolean guardReached = !tailGuard || branch == 0;
                boolean cancelled = cancel && guardReached;
                if (!cancelled && branch == 2) {
                    try { method.invoke(instance, arguments); throw new AssertionError("expected body exception"); }
                    catch (InvocationTargetException error) { check(error.getCause() == FAILURE, "exact original exception"); }
                } else {
                    Object result = method.invoke(instance, arguments);
                    if (entry.integer) check(result.equals(cancelled ? 22 : 7), "cancellation result unchanged; ordinary RETURN result filtered");
                }
                int expectedHooks = cancelled || branch == 2 || entry.tail && branch == 1 ? 0 : 1;
                check(dispatches == expectedHooks, "RETURN/TAIL dispatch policy " + owner + "." + name);
                check(guards == (guardReached ? 1 : 0), "original guard executes exactly once when reached");
                check(body == (!tailGuard && cancelled ? 0 : 1), "original body and early cancellation preserved");
            }
        }
        branch = 0; cancel = false; body = dispatches = guards = 0;
        Object result = method.invoke(instance, arguments);
        check(body == 1 && guards == 1 && dispatches == 0 && (!entry.integer || result.equals(6)), "disconnect restores dormant vanilla behavior");
    }

    public static int body() { body++; if (branch == 2) throw FAILURE; return branch; }
    public static void callback(CallbackInfo info) {
        guards++;
        if (cancel) {
            if (info instanceof CallbackInfoReturnable<?> raw) {
                @SuppressWarnings("unchecked") var result = (CallbackInfoReturnable<Integer>) raw;
                result.setReturnValue(22);
            } else info.cancel();
        }
    }

    private static byte[] fixture(String owner, MethodNode target, boolean integer, boolean tailGuard) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(target.access, target.name, target.desc, null, null);
        method.visitCode();
        int local = 1;
        for (Type argument : Type.getArgumentTypes(target.desc)) local += argument.getSize();
        if (!tailGuard) guard(method, owner, local, integer);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "body", "()I", false);
        Label normal = new Label();
        method.visitJumpInsn(Opcodes.IFEQ, normal);
        emitReturn(method, integer);
        method.visitLabel(normal);
        if (tailGuard) guard(method, owner, local, integer);
        emitReturn(method, integer);
        method.visitMaxs(0, 0); method.visitEnd();
        String info = integer ? CIR : CI;
        MethodVisitor helper = writer.visitMethod(Opcodes.ACC_PRIVATE, "arbitrary$observer$cancel", "(L" + info + ";)V", null, null);
        var metadata = helper.visitAnnotation("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;", true);
        metadata.visit("mixin", "example.unlisted.VisualObserver"); metadata.visit("priority", 2500); metadata.visitEnd();
        helper.visitCode(); helper.visitVarInsn(Opcodes.ALOAD, 1);
        helper.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "callback", "(L" + CI + ";)V", false);
        helper.visitInsn(Opcodes.RETURN); helper.visitMaxs(0, 0); helper.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    private static void guard(MethodVisitor method, String owner, int local, boolean integer) {
        String info = integer ? CIR : CI;
        method.visitTypeInsn(Opcodes.NEW, info); method.visitInsn(Opcodes.DUP);
        method.visitLdcInsn("unlisted-callback"); method.visitInsn(Opcodes.ICONST_1);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, info, "<init>", "(Ljava/lang/String;Z)V", false);
        method.visitVarInsn(Opcodes.ASTORE, local);
        method.visitVarInsn(Opcodes.ALOAD, 0); method.visitVarInsn(Opcodes.ALOAD, local);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "arbitrary$observer$cancel", "(L" + info + ";)V", false);
        method.visitVarInsn(Opcodes.ALOAD, local);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, info, "isCancelled", "()Z", false);
        Label continuation = new Label(); method.visitJumpInsn(Opcodes.IFEQ, continuation);
        if (integer) {
            method.visitVarInsn(Opcodes.ALOAD, local);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CIR, "getReturnValueI", "()I", false);
        }
        method.visitInsn(integer ? Opcodes.IRETURN : Opcodes.RETURN);
        method.visitLabel(continuation);
    }
    private static void emitReturn(MethodVisitor method, boolean integer) {
        if (integer) method.visitIntInsn(Opcodes.BIPUSH, 6);
        method.visitInsn(integer ? Opcodes.IRETURN : Opcodes.RETURN);
    }
    private static byte[] empty(String owner) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        method.visitCode(); method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        method.visitInsn(Opcodes.RETURN); method.visitMaxs(1, 1); method.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    private static ClassNode read(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES); return node; }
    private static byte[] write(ClassNode node) { ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray(); }
    private static void check(boolean passed, String label) { checks++; if (!passed) throw new AssertionError(label); }
    private record Case(String owner, String id, String method, String mappedMethod, String bridge, String operation, boolean tail, boolean integer) { }
}
