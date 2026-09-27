package dev.celestebeta.host.fixture;

import dev.celestebeta.host.FakeGambleHookBridge;
import dev.celestebeta.host.instrument.FakeGambleHooksTransformer;
import dev.c4lam.celeste.compat.FakeGambleAccess;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

/** Schema and synthetic local-inventory behavior fixtures, not a game or server test. */
public final class FakeGambleHooksCheck {
    private static final String BRIDGE = "dev/celestebeta/host/FakeGambleHookBridge";
    private static final List<String> OWNERS = List.of(
        "net/minecraft/client/network/ClientPlayerInteractionManager", "net/minecraft/class_636",
        "net/minecraft/client/network/ClientPlayerEntity", "net/minecraft/class_746",
        "net/minecraft/client/world/ClientWorld", "net/minecraft/class_638",
        "net/minecraft/client/network/ClientCommonNetworkHandler", "net/minecraft/class_8673",
        "net/minecraft/client/world/WorldEventHandler", "net/minecraft/class_9959",
        "net/minecraft/entity/ItemEntity", "net/minecraft/class_1542",
        "net/minecraft/client/network/ClientPlayNetworkHandler", "net/minecraft/class_634",
        "net/minecraft/screen/ScreenHandler", "net/minecraft/class_1703",
        "net/minecraft/client/MinecraftClient", "net/minecraft/class_310",
        "net/minecraft/client/gui/screen/ingame/Generic3x3ContainerScreen", "net/minecraft/class_480");
    private static int checks;

    private FakeGambleHooksCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected intermediary jar path");
        try (JarFile jar = new JarFile(Path.of(args[0]).toFile())) {
            for (String owner : OWNERS) {
                byte[] bytes;
                if (named(owner)) bytes = resource(owner + ".class");
                else {
                    var entry = jar.getJarEntry(owner + ".class");
                    if (entry == null) throw new IllegalStateException("Missing " + owner);
                    try (InputStream input = jar.getInputStream(entry)) { bytes = input.readAllBytes(); }
                }
                actual(owner, bytes);
            }
        }
        FakeGambleAccess.verifyLateMembers(); checks++;
        bridgeContract();
        System.out.println("PASS late fake-gamble hooks: " + checks
            + " checks; 25 real named/intermediary method schemas and executable local inventory/ghost interaction fixtures");
    }

    private static void actual(String owner, byte[] original) throws Exception {
        byte[] snapshot = original.clone(); byte[] prepared = FakeGambleHooksTransformer.prepare(owner, original);
        ClassNode before = read(original), after = read(prepared);
        check(schema(before).equals(schema(after)), "class schema unchanged: " + owner);
        List<MethodNode> changed = new ArrayList<>();
        for (int i = 0; i < before.methods.size(); i++) {
            MethodNode old = before.methods.get(i), updated = after.methods.get(i);
            if (text(old).equals(text(updated))) continue;
            changed.add(old); String expected = hook(owner, old);
            check(expected != null, "only intended target body changed: " + old.name);
            new Analyzer<>(new BasicVerifier()).analyze(owner, updated); checks++;
            List<String> hooks = new ArrayList<>();
            for (var instruction : updated.instructions) if (instruction instanceof MethodInsnNode call && call.owner.equals(BRIDGE)) hooks.add(call.name);
            check(hooks.equals(List.of(expected)), "one exact HEAD or final-RETURN callback: " + owner + "." + old.name);
        }
        check(changed.size() == expectedMethods(owner), "exact intended method count");
        check(Arrays.equals(snapshot, original), "original bytes remain untouched");
        expect(IllegalArgumentException.class, () -> FakeGambleHooksTransformer.prepare(owner, prepared), "duplicate instrumentation rejected");
        expect(IllegalArgumentException.class, () -> FakeGambleHooksTransformer.prepare("other/Class", original), "unsupported class rejected");
        expect(IllegalArgumentException.class, () -> FakeGambleHooksTransformer.prepare(OWNERS.get((OWNERS.indexOf(owner) + 1) % OWNERS.size()), original), "class-name mismatch rejected");
        for (MethodNode method : changed) {
            ClassNode missing = read(original);
            missing.methods.removeIf(value -> value.name.equals(method.name) && value.desc.equals(method.desc));
            expect(IllegalArgumentException.class, () -> FakeGambleHooksTransformer.prepare(owner, write(missing)), "missing target rejected");
        }
        ClassNode startup = read(original); startup.methods.add(new MethodNode(Opcodes.ACC_PRIVATE, "celeste$existing", "()V", null, null));
        expect(IllegalArgumentException.class, () -> FakeGambleHooksTransformer.prepare(owner, write(startup)), "startup Celeste hooks rejected");
        if (world(owner)) {
            var fields = before.fields.stream().filter(field -> field.name.equals(mapped(owner, "world", "field_53069"))).toList();
            check(fields.size() == 1 && fields.getFirst().desc.equals("L" + mapped(owner, "net/minecraft/client/world/ClientWorld", "net/minecraft/class_638") + ";")
                && (fields.getFirst().access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_STATIC)) == (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL),
                "actual world helper field is the original private final client world");
        }
        if (item(owner)) {
            var methods = before.methods.stream().filter(method -> method.name.equals(mapped(owner, "tryMerge", "method_6973")) && method.desc.equals("()V")).toList();
            check(methods.size() == 1 && (methods.getFirst().access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == Opcodes.ACC_PRIVATE,
                "actual merge helper resolves exact private nonstatic no-argument overload");
        }
        execute(owner, changed);
    }

    @SuppressWarnings("try")
    private static void execute(String owner, List<MethodNode> targets) throws Exception {
        Map<String, byte[]> definitions = new HashMap<>();
        for (MethodNode target : targets) {
            for (Type argument : Type.getArgumentTypes(target.desc)) if (argument.getSort() == Type.OBJECT) definitions.put(argument.getClassName(), empty(argument.getInternalName()));
            Type result = Type.getReturnType(target.desc);
            if (result.getSort() == Type.OBJECT) definitions.put(result.getClassName(), empty(result.getInternalName()));
        }
        definitions.put(owner.replace('/', '.'), FakeGambleHooksTransformer.prepare(owner, fixture(owner, targets)));
        Class<?> type = new FixtureLoader(definitions).loadClass(owner.replace('/', '.'));
        Object instance = type.getConstructor().newInstance();
        for (MethodNode target : targets) {
            String hook = hook(owner, target); Method method = declared(type, target.name); Object[] arguments = parameters(method);
            Object result = null;
            if (method.getReturnType() != void.class) {
                result = method.getReturnType() == boolean.class ? Boolean.TRUE : method.getReturnType().getConstructor().newInstance();
                type.getField("result").set(instance, result);
            }
            reset(instance); Object dormant = invoke(method, instance, arguments);
            check(calls(instance) == 1 && dormant == result, "disconnected fixture retains original body and exact return");
            Recorder handler = new Recorder(instance, method, arguments); reset(instance);
            try (var registration = FakeGambleHookBridge.install(handler)) {
                Object active = invoke(method, instance, arguments);
                check(handler.events.equals(List.of(hook + ":" + (tail(hook) ? 1 : 0))), "hook has exact HEAD/TAIL ordering: " + hook);
                check(calls(instance) == 1 && active == result, "unconsumed hook preserves exact original return and one body call");
                if (cancellable(hook)) {
                    reset(instance); handler.events.clear(); handler.cancel = true;
                    RuntimeException neverReached = new IllegalStateException("cancelled body must not run"); type.getField("failure").set(instance, neverReached);
                    check(invoke(method, instance, arguments) == null && calls(instance) == 0 && handler.events.equals(List.of(hook + ":0")),
                        "true cancellation returns before the original body, including its exception path");
                    type.getField("failure").set(instance, null); handler.cancel = false;
                }
                if (nullable(hook)) {
                    for (Object consumed : method.getReturnType() == boolean.class ? List.of(Boolean.FALSE, Boolean.TRUE)
                            : List.of(method.getReturnType().getConstructor().newInstance())) {
                        reset(instance); handler.events.clear(); handler.handled = consumed;
                        type.getField("failure").set(instance, new IllegalStateException("packet-producing body must not run"));
                        check(invoke(method, instance, arguments) == consumed && calls(instance) == 0
                                && handler.events.equals(List.of(hook + ":0")),
                            "owned local result returns before vanilla packet/body work, preserving handled false and true");
                    }
                    handler.handled = null; type.getField("failure").set(instance, null);
                }
                reset(instance); handler.events.clear(); type.getField("early").setBoolean(instance, true);
                check(invoke(method, instance, arguments) == result && calls(instance) == 0, "original early return value retained");
                check(handler.events.equals(tail(hook) ? List.of() : List.of(hook + ":0")), "TAIL excludes early RETURN while HEAD retains ordering");
                type.getField("early").setBoolean(instance, false);
                reset(instance); handler.events.clear(); RuntimeException failure = new IllegalArgumentException("original fixture body failed");
                type.getField("failure").set(instance, failure);
                check(expect(IllegalArgumentException.class, () -> invoke(method, instance, arguments), "original body exception") == failure,
                    "exact original exception remains unwrapped");
                check(handler.events.equals(tail(hook) ? List.of() : List.of(hook + ":0")), "throwing original body has no invented TAIL");
                type.getField("failure").set(instance, null);
                if (hook.equals("worldEvent")) reentrantWorldExecution(instance, method, arguments, handler);
                reset(instance); handler.events.clear(); AtomicReference<Throwable> workerFailure = new AtomicReference<>();
                Thread worker = new Thread(() -> {
                    try {
                        invoke(method, instance, arguments);
                        try { registration.close(); throw new AssertionError("worker closed registration"); }
                        catch (IllegalStateException expected) { /* client-thread ownership */ }
                    } catch (Throwable problem) { workerFailure.set(problem); }
                }, "fake-gamble-fixture-worker");
                worker.start(); worker.join(5000);
                check(!worker.isAlive() && workerFailure.get() == null && calls(instance) == 1 && handler.events.isEmpty(),
                    "worker executes vanilla without dispatching or disconnecting local hooks: " + workerFailure.get());
            }
            reset(instance); handler.events.clear();
            check(invoke(method, instance, arguments) == result && calls(instance) == 1 && handler.events.isEmpty(), "close restores exact original behavior");
        }
    }

    private static void reentrantWorldExecution(Object instance, Method method, Object[] arguments, Recorder handler) throws Exception {
        reset(instance); handler.events.clear(); handler.reenter = true;
        Object[] eventArguments = arguments.clone(); eventArguments[0] = 1001; eventArguments[2] = 17;
        invoke(method, instance, eventArguments);
        check(handler.events.equals(List.of("world:1001:0", "world:1000:0", "world:2000:1")),
            "same receiver supports two synchronous secondary world-event callbacks in order");
        int[] bodyEvents = (int[]) instance.getClass().getField("bodyEvents").get(instance);
        check(calls(instance) == 2 && bodyEvents[0] == 1000 && bodyEvents[1] == 2000,
            "original failed-dispenser event is cancelled while secondary events execute once each");
        handler.reenter = false;
    }

    @SuppressWarnings("try")
    private static void bridgeContract() throws Exception {
        check(!FakeGambleHookBridge.click(1, 2, 3, null, null) && !FakeGambleHookBridge.worldEvent(null, 1001, null, 0)
            && !FakeGambleHookBridge.pickup(null, null) && !FakeGambleHookBridge.pick(null), "dormant cancellable hooks never suppress vanilla");
        FakeGambleHookBridge.itemTick(null); FakeGambleHookBridge.afterSync(); FakeGambleHookBridge.screenClosed(null, null);
        FakeGambleHookBridge.noteDispenser(null); FakeGambleHookBridge.screenRender(null); checks++;
        RuntimeException failure = new IllegalStateException("callback failed");
        BasicHandler handler = new BasicHandler(); handler.failure = failure;
        try (var registration = FakeGambleHookBridge.install(handler)) {
            expect(IllegalStateException.class, () -> FakeGambleHookBridge.install(new BasicHandler()), "duplicate registration rejected");
            check(expect(IllegalStateException.class, () -> FakeGambleHookBridge.pick(null), "callback exception") == failure,
                "callback exception identity propagates");
            handler.failure = null; check(FakeGambleHookBridge.pick(null), "exception unwinds bridge dispatch state cleanly");
        }
        check(!FakeGambleHookBridge.pick(null), "closed bridge remains inert");
        FakeGambleHookBridge.assertVisibleFrom(FakeGambleHooksCheck.class.getClassLoader()); checks++;
        expect(IllegalStateException.class, () -> FakeGambleHookBridge.assertVisibleFrom(null), "invisible canonical bridge rejected");
    }

    private static byte[] fixture(String owner, List<MethodNode> targets) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "bodyCalls", "I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "bodyEvents", "[I", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "early", "Z", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "failure", "Ljava/lang/RuntimeException;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "result", "Ljava/lang/Object;", null, null).visitEnd();
        constructor(writer, true, owner);
        for (MethodNode target : targets) {
            MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, target.name, target.desc, null, null); method.visitCode();
            Label normal = new Label(); method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, "early", "Z");
            method.visitJumpInsn(Opcodes.IFEQ, normal); returnValue(method, owner, target.desc); method.visitLabel(normal);
            Label healthy = new Label(); method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, "failure", "Ljava/lang/RuntimeException;");
            method.visitJumpInsn(Opcodes.IFNULL, healthy); method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, "failure", "Ljava/lang/RuntimeException;");
            method.visitInsn(Opcodes.ATHROW); method.visitLabel(healthy);
            if (world(owner)) {
                method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, "bodyEvents", "[I");
                method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, "bodyCalls", "I");
                method.visitVarInsn(Opcodes.ILOAD, 1); method.visitInsn(Opcodes.IASTORE);
            }
            method.visitVarInsn(Opcodes.ALOAD, 0); method.visitInsn(Opcodes.DUP); method.visitFieldInsn(Opcodes.GETFIELD, owner, "bodyCalls", "I");
            method.visitInsn(Opcodes.ICONST_1); method.visitInsn(Opcodes.IADD); method.visitFieldInsn(Opcodes.PUTFIELD, owner, "bodyCalls", "I");
            returnValue(method, owner, target.desc); method.visitMaxs(0, 0); method.visitEnd();
        }
        writer.visitEnd(); return writer.toByteArray();
    }
    private static void returnValue(MethodVisitor method, String owner, String desc) {
        Type result = Type.getReturnType(desc);
        if (result.getSort() == Type.VOID) method.visitInsn(Opcodes.RETURN);
        else {
            method.visitVarInsn(Opcodes.ALOAD, 0); method.visitFieldInsn(Opcodes.GETFIELD, owner, "result", "Ljava/lang/Object;");
            if (result.getSort() == Type.BOOLEAN) {
                method.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean");
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
                method.visitInsn(Opcodes.IRETURN);
            } else { method.visitTypeInsn(Opcodes.CHECKCAST, result.getInternalName()); method.visitInsn(Opcodes.ARETURN); }
        }
    }
    private static void constructor(ClassWriter writer, boolean events, String owner) {
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null); init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0); init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        if (events) {
            init.visitVarInsn(Opcodes.ALOAD, 0); init.visitIntInsn(Opcodes.BIPUSH, 8); init.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
            init.visitFieldInsn(Opcodes.PUTFIELD, owner, "bodyEvents", "[I");
        }
        init.visitInsn(Opcodes.RETURN); init.visitMaxs(2, 1); init.visitEnd();
    }
    private static byte[] empty(String owner) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        constructor(writer, false, owner); writer.visitEnd(); return writer.toByteArray();
    }

    private static final class Recorder implements FakeGambleHookBridge.Handler {
        final Object instance; final Method worldMethod; final Object[] arguments;
        final List<String> events = new ArrayList<>(); boolean cancel, reenter; Object handled;
        Recorder(Object instance, Method method, Object[] arguments) { this.instance = instance; worldMethod = method; this.arguments = arguments; }
        private void event(String name) {
            try { events.add(name + ":" + calls(instance)); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
        public boolean click(int syncId, int slotId, int button, Object action, Object player) {
            check(arguments[0].equals(syncId) && arguments[1].equals(slotId) && arguments[2].equals(button)
                && arguments[3] == action && arguments[4] == player, "click receives exact integers, action and player"); event("click"); return cancel;
        }
        public boolean worldEvent(Object handler, int eventId, Object pos, int data) {
            check(handler == instance && pos == arguments[1], "world event receives exact receiver and position");
            if (!reenter) { check(arguments[0].equals(eventId) && arguments[2].equals(data), "world event integer inputs preserved"); event("worldEvent"); return cancel; }
            event("world:" + eventId);
            if (eventId != 1001) {
                check(data == (eventId == 1000 ? 17 : 4), "secondary world event payload preserved"); return false;
            }
            try { invoke(worldMethod, instance, 1000, pos, data); invoke(worldMethod, instance, 2000, pos, 4); }
            catch (Exception failure) { throw new AssertionError(failure); }
            return true;
        }
        public boolean pickup(Object item, Object player) { check(item == instance && player == arguments[0], "pickup receives exact entities"); event("pickup"); return cancel; }
        public void itemTick(Object item) { check(item == instance, "item TAIL receives exact item"); event("itemTick"); }
        public void afterSync() { event("afterSync"); }
        public void screenClosed(Object handler, Object player) { check(handler == instance && player == arguments[0], "close HEAD receives exact screen handler/player"); event("screenClosed"); }
        public void noteDispenser(Object hit) { check(hit == arguments[2], "interact HEAD receives original hit parameter three"); event("noteDispenser"); }
        public boolean pick(Object client) { check(client == instance, "pick receives exact client"); event("pick"); return cancel; }
        public void screenRender(Object screen) { check(screen == instance, "render receives exact screen"); event("screenRender"); }
        public Object interactBlock(Object player, Object hand, Object hit) {
            check(player == arguments[0] && hand == arguments[1] && hit == arguments[2], "placement receives original player, hand and hit"); event("interactBlock"); return handled;
        }
        public Object interactItem(Object player, Object hand) {
            check(player == arguments[0] && hand == arguments[1], "item use preserves both arguments"); event("interactItem"); return handled;
        }
        public Object interactEntity(Object player, Object hand) {
            check(player == arguments[0] && hand == arguments[arguments.length - 1], "entity use preserves player and correct hand slot"); event("interactEntity"); return handled;
        }
        public Boolean attackBlock(Object pos, Object direction) {
            check(pos == arguments[0] && direction == arguments[1], "mining start preserves position and face"); event("attackBlock"); return (Boolean) handled;
        }
        public Boolean updateBlockBreakingProgress(Object pos, Object direction) {
            check(pos == arguments[0] && direction == arguments[1], "mining progress preserves position and face"); event("updateBlockBreakingProgress"); return (Boolean) handled;
        }
        public Boolean breakBlock(Object pos) { check(pos == arguments[0], "direct break preserves position"); event("breakBlock"); return (Boolean) handled; }
        public boolean cancelBlockBreaking() { event("cancelBlockBreaking"); return cancel; }
        public Boolean dropSelected(Object player, boolean entireStack) {
            check(player == instance && arguments[0].equals(entireStack), "drop receives correct player and whole-stack flag"); event("dropSelected"); return (Boolean) handled;
        }
        public void serverBlockUpdate(Object world, Object pos, Object state) {
            check(world == instance && pos == arguments[0] && state == arguments[1], "authoritative update receives original world, position and state after body"); event("serverBlockUpdate");
        }
        public void chunkUnloaded(Object world, long chunk) {
            check(world == instance && arguments[0].equals(chunk), "chunk unload preserves full 64-bit coordinate"); event("chunkUnloaded");
        }
        public void beforeSetBlockState(Object world, Object pos) {
            check(world == instance && pos == arguments[0], "placement write captures original world and position before body"); event("beforeSetBlockState");
        }
        public boolean attackEntity(Object player) { check(player == arguments[0], "attack ownership receives original player"); event("attackEntity"); return cancel; }
        public boolean packet(Object packet) { check(packet == arguments[0], "packet guard receives exact packet identity"); event("packet"); return cancel; }
        public boolean creativeStack(Object stack, int slot) { check(stack == arguments[0] && arguments[1].equals(slot), "creative stack preserves exact stack and protocol slot"); event("creativeStack"); return cancel; }
        public boolean creativeDrop(Object stack) { check(stack == arguments[0], "creative drop preserves exact stack"); event("creativeDrop"); return cancel; }
    }
    private static final class BasicHandler implements FakeGambleHookBridge.Handler {
        RuntimeException failure;
        public boolean click(int syncId, int slotId, int button, Object action, Object player) { return true; }
        public boolean worldEvent(Object handler, int eventId, Object pos, int data) { return true; }
        public boolean pickup(Object item, Object player) { return true; }
        public void itemTick(Object item) { }
        public void afterSync() { }
        public void screenClosed(Object handler, Object player) { }
        public void noteDispenser(Object hit) { }
        public boolean pick(Object client) { if (failure != null) throw failure; return true; }
        public void screenRender(Object screen) { }
    }
    private static final class FixtureLoader extends ClassLoader {
        private final Map<String, byte[]> definitions;
        FixtureLoader(Map<String, byte[]> definitions) { super(FakeGambleHooksCheck.class.getClassLoader()); this.definitions = definitions; }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] bytes = definitions.get(name); if (bytes == null) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name); if (type == null) type = defineClass(name, bytes, 0, bytes.length);
                if (resolve) resolveClass(type); return type;
            }
        }
    }
    private static String hook(String owner, MethodNode method) {
        String name = method.name;
        if (interaction(owner)) {
            if (name.equals(mapped(owner, "clickSlot", "method_2906"))) return "click";
            if (name.equals(mapped(owner, "interactBlock", "method_2896"))) return "interactBlock";
            if (name.equals(mapped(owner, "interactItem", "method_2919"))) return "interactItem";
            if (name.equals(mapped(owner, "interactEntity", "method_2905")) || name.equals(mapped(owner, "interactEntityAtLocation", "method_2917"))) return "interactEntity";
            if (name.equals(mapped(owner, "attackEntity", "method_2918"))) return "attackEntity";
            if (name.equals(mapped(owner, "attackBlock", "method_2910"))) return "attackBlock";
            if (name.equals(mapped(owner, "updateBlockBreakingProgress", "method_2902"))) return "updateBlockBreakingProgress";
            if (name.equals(mapped(owner, "breakBlock", "method_2899"))) return "breakBlock";
            if (name.equals(mapped(owner, "cancelBlockBreaking", "method_2925"))) return "cancelBlockBreaking";
            if (name.equals(mapped(owner, "clickCreativeStack", "method_2909"))) return "creativeStack";
            if (name.equals(mapped(owner, "dropCreativeStack", "method_2915"))) return "creativeDrop";
        } else if (world(owner) && name.equals(mapped(owner, "processWorldEvent", "method_62194"))) return "worldEvent";
        else if (player(owner) && name.equals(mapped(owner, "dropSelectedItem", "method_7290"))) return "dropSelected";
        else if (commonNetwork(owner) && name.equals(mapped(owner, "sendPacket", "method_52787"))) return "packet";
        else if (clientWorld(owner)) {
            if (name.equals(mapped(owner, "handleBlockUpdate", "method_41928"))) return "serverBlockUpdate";
            if (name.equals(mapped(owner, "onChunkUnload", "method_62895"))) return "chunkUnloaded";
            if (name.equals(mapped(owner, "setBlockState", "method_30092"))) return "beforeSetBlockState";
        }
        else if (item(owner)) {
            if (name.equals(mapped(owner, "onPlayerCollision", "method_5694"))) return "pickup";
            if (name.equals(mapped(owner, "tick", "method_5773"))) return "itemTick";
        } else if (network(owner) && (name.equals(mapped(owner, "onScreenHandlerSlotUpdate", "method_11109"))
            || name.equals(mapped(owner, "onInventory", "method_11153")))) return "afterSync";
        else if (screenHandler(owner) && name.equals(mapped(owner, "onClosed", "method_7595"))) return "screenClosed";
        else if (client(owner) && name.equals(mapped(owner, "doItemPick", "method_1511"))) return "pick";
        else if (screen(owner) && name.equals(mapped(owner, "render", "method_25394"))) return "screenRender";
        return null;
    }
    private static boolean named(String owner) { return !owner.startsWith("net/minecraft/class_"); }
    private static boolean interaction(String owner) { return owner.endsWith("/ClientPlayerInteractionManager") || owner.endsWith("/class_636"); }
    private static boolean world(String owner) { return owner.endsWith("/WorldEventHandler") || owner.endsWith("/class_9959"); }
    private static boolean item(String owner) { return owner.endsWith("/ItemEntity") || owner.endsWith("/class_1542"); }
    private static boolean network(String owner) { return owner.endsWith("/ClientPlayNetworkHandler") || owner.endsWith("/class_634"); }
    private static boolean screenHandler(String owner) { return owner.endsWith("/ScreenHandler") || owner.endsWith("/class_1703"); }
    private static boolean client(String owner) { return owner.endsWith("/MinecraftClient") || owner.endsWith("/class_310"); }
    private static boolean screen(String owner) { return owner.endsWith("/Generic3x3ContainerScreen") || owner.endsWith("/class_480"); }
    private static boolean player(String owner) { return owner.endsWith("/ClientPlayerEntity") || owner.endsWith("/class_746"); }
    private static boolean clientWorld(String owner) { return owner.endsWith("/ClientWorld") || owner.endsWith("/class_638"); }
    private static boolean commonNetwork(String owner) { return owner.endsWith("/ClientCommonNetworkHandler") || owner.endsWith("/class_8673"); }
    private static String mapped(String owner, String name, String intermediary) { return named(owner) ? name : intermediary; }
    private static int expectedMethods(String owner) { return interaction(owner) ? 12 : clientWorld(owner) ? 3 : item(owner) || network(owner) ? 2 : 1; }
    private static boolean tail(String hook) { return hook.equals("itemTick") || hook.equals("afterSync") || hook.equals("serverBlockUpdate"); }
    private static boolean cancellable(String hook) { return List.of("click", "worldEvent", "pickup", "pick", "cancelBlockBreaking", "attackEntity", "packet", "creativeStack", "creativeDrop").contains(hook); }
    private static boolean nullable(String hook) { return List.of("interactBlock", "interactItem", "interactEntity", "attackBlock", "updateBlockBreakingProgress", "breakBlock", "dropSelected").contains(hook); }
    private static Method declared(Class<?> type, String name) { return Arrays.stream(type.getDeclaredMethods()).filter(method -> method.getName().equals(name)).findFirst().orElseThrow(); }
    private static Object[] parameters(Method method) throws Exception {
        Object[] arguments = new Object[method.getParameterCount()]; Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < arguments.length; i++) {
            if (types[i] == int.class) arguments[i] = 10 + i;
            else if (types[i] == boolean.class) arguments[i] = true;
            else if (types[i] == long.class) arguments[i] = 0xFEDCBA9876543210L;
            else if (types[i] == float.class) arguments[i] = 0.5F;
            else arguments[i] = types[i].getConstructor().newInstance();
        }
        return arguments;
    }
    private static int calls(Object instance) throws ReflectiveOperationException { return instance.getClass().getField("bodyCalls").getInt(instance); }
    private static void reset(Object instance) throws ReflectiveOperationException {
        instance.getClass().getField("bodyCalls").setInt(instance, 0); Arrays.fill((int[]) instance.getClass().getField("bodyEvents").get(instance), 0);
    }
    private static Object invoke(Method method, Object instance, Object... arguments) throws Exception {
        try { return method.invoke(instance, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        }
    }
    private static byte[] resource(String path) throws Exception {
        try (InputStream input = FakeGambleHooksCheck.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing " + path); return input.readAllBytes();
        }
    }
    private static ClassNode read(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES); return node; }
    private static byte[] write(ClassNode node) { ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray(); }
    private static List<String> schema(ClassNode node) {
        List<String> result = new ArrayList<>(); result.add(node.version + ":" + node.access + ":" + node.name + ":" + node.superName + ":" + node.signature + ":" + node.interfaces);
        node.fields.forEach(field -> result.add("F:" + field.access + ":" + field.name + ":" + field.desc + ":" + field.signature));
        node.methods.forEach(method -> result.add("M:" + method.access + ":" + method.name + ":" + method.desc + ":" + method.signature + ":" + method.exceptions)); return result;
    }
    private static String text(MethodNode method) {
        Textifier printer = new Textifier(); method.accept(new TraceMethodVisitor(printer)); StringWriter text = new StringWriter(); printer.print(new PrintWriter(text)); return text.toString();
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static Throwable expect(Class<? extends Throwable> type, Throwing action, String message) {
        checks++; try { action.run(); } catch (Throwable failure) { if (type.isInstance(failure)) return failure; throw new AssertionError(message, failure); }
        throw new AssertionError(message + ": no exception");
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
